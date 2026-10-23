package com.mino.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * 每条任务用完整 id 作通知 tag。同一次提醒重试只更新内容；换一天先撤旧通知，
 * 避免旧卡片还挂着时 setOnlyAlertOnce 抑制新一天的提示。
 * ongoing/autoCancel 表达保留意图；能否划走、是否横幅最终由系统和用户设置决定。
 */
object Notifications {

    const val CHANNEL_ID = "reminders"

    /** 通知上的「完成」：标记为已完成（只对「仅一次」的任务有效） */
    const val ACTION_COMPLETE = "com.mino.app.COMPLETE"

    /** 通知上的「知道」：只关掉这条，不动任务（「每天」的任务走这条） */
    const val ACTION_DISMISS = "com.mino.app.DISMISS"

    private const val TAG = "MiNo/Notifications"
    internal const val EXTRA_OCCURRENCE = "com.mino.app.REMINDER_OCCURRENCE"

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return

        val ch = NotificationChannel(
            CHANNEL_ID,
            ctx.getString(R.string.notif_channel_name),
            // 请求较高重要性；用户的渠道配置和系统策略决定实际提示方式。
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = ctx.getString(R.string.notif_channel_desc)
            enableVibration(true)
            setShowBadge(true)
        }
        nm.createNotificationChannel(ch)
    }

    fun show(ctx: Context, todo: Todo, day: String = Logic.today()): Boolean {
        ensureChannel(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return false
        if (!nm.areNotificationsEnabled()) return false
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        val channel = nm.getNotificationChannel(CHANNEL_ID) ?: return false
        if (channel.importance == NotificationManager.IMPORTANCE_NONE) return false
        if (android.os.Build.VERSION.SDK_INT >= 28 && channel.group != null &&
            nm.getNotificationChannelGroup(channel.group)?.isBlocked == true) return false

        val once = !Logic.isDaily(todo)

        // 「仅一次」：点掉 = 做完了（对齐桌面版点 ×）
        // 「每天」：点掉只是不看了，任务不该被划掉，所以连按钮文案都不一样
        val action = if (once) ACTION_COMPLETE else ACTION_DISMISS
        val label = ctx.getString(
            if (once) R.string.notif_action_complete else R.string.notif_action_dismiss,
        )

        // 点正文 = 打开应用看看。**不标记完成** —— 那是 open_reminder 的语义。
        val open = PendingIntent.getActivity(
            ctx,
            0,
            Intent(ctx, MainActivity::class.java).apply {
                data = Reminders.identity(todo.id, "open")
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(Reminders.EXTRA_ID, todo.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val occurrence = ReminderPolicy.notificationOccurrence(todo, day)
        val primary = PendingIntent.getBroadcast(
            ctx,
            // data URI 包含完整 id 和动作；不依赖有碰撞的哈希
            0,
            Intent(ctx, NotificationActionReceiver::class.java).apply {
                this.action = action
                data = Reminders.identity(todo.id, action).buildUpon().appendPath(occurrence).build()
                putExtra(EXTRA_OCCURRENCE, occurrence)
                putExtra(Reminders.EXTRA_ID, todo.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ctx.getString(R.string.notif_title))
            .setContentText(todo.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(todo.text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            // 请求持续显示；Android 版本与用户设置仍可允许移除。
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .addExtras(android.os.Bundle().apply { putString(EXTRA_OCCURRENCE, occurrence) })
            .setContentIntent(open)
            .addAction(0, label, primary)
            .build()

        try {
            val old = nm.activeNotifications.firstOrNull { it.tag == todo.id && it.id == 0 }
            if (old != null && old.notification.extras.getString(EXTRA_OCCURRENCE) != occurrence) {
                nm.cancel(todo.id, 0)
            }
            nm.notify(todo.id, 0, n)
            return true
        } catch (e: SecurityException) {
            // 没有通知权限（Android 13+ 用户拒了）。静默降级：界面照常可用，
            // 提醒不响。用户随时可以在系统设置里补授权。
            Log.w(TAG, "没有通知权限，提醒无法显示")
            return false
        }
    }

    fun cancel(ctx: Context, id: String) {
        ctx.getSystemService(NotificationManager::class.java)?.cancel(id, 0)
    }
    /** 不依赖 scheduled_ids：一次性提醒发过后已不在排程名单，但通知仍可能挂着。 */
    internal fun reconcile(ctx: Context, data: AppData) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val todos = data.todos.associateBy { it.id }
        for (active in nm.activeNotifications) {
            if (active.notification.channelId != CHANNEL_ID) continue
            val todo = active.tag?.let { todos[it] }
            val occurrence = active.notification.extras.getString(EXTRA_OCCURRENCE)
            if (!ReminderPolicy.matchesOccurrence(todo, occurrence)) nm.cancel(active.tag, active.id)
        }
    }
    /** 排队中的旧动作不能撤掉同一任务的新通知。调用方持 Reminders 锁。 */
    internal fun cancelOccurrence(ctx: Context, id: String, occurrence: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val active = nm.activeNotifications.firstOrNull { it.tag == id && it.id == 0 } ?: return
        if (active.notification.extras.getString(EXTRA_OCCURRENCE) == occurrence) nm.cancel(id, 0)
    }
}
