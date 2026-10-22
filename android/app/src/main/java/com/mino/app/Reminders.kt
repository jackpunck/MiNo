package com.mino.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.time.Instant
import java.time.ZoneId

/** 所有重排/发送串行；快照必须在取得锁之后从 Store 读取。 */
object Reminders {
    const val EXTRA_ID = "todo_id"
    const val TODOS_CHANGED = "com.mino.app.TODOS_CHANGED"
    private const val PREFS = "mino_reminders"
    private const val KEY_IDS = "scheduled_ids"
    private val LOCK = Any()
    private const val RETRY_MS = 15 * 60 * 1000L

    fun reschedule(context: Context): Unit = synchronized(LOCK) {
        val app = context.applicationContext
        // 读取失败时保留原有闹钟，不能先撤掉再发现没有可用快照。
        val data = Store(app).read()
        Notifications.reconcile(app, data)
        val am = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getStringSet(KEY_IDS, emptySet())!!.toSet()
        val now = System.currentTimeMillis()
        val fresh = data.todos.mapNotNull { t ->
            ReminderPolicy.nextTrigger(t, now, ZoneId.systemDefault())?.let { t.id to it }
        }.toMap()
        // 先持久化并集：进程中途消失，下轮仍知道该撤哪些旧闹钟。
        check(prefs.edit().putStringSet(KEY_IDS, previous + fresh.keys).commit())
        for (id in previous) cancel(app, am, id)
        for ((id, at) in fresh) arm(app, am, id, at)
        check(prefs.edit().putStringSet(KEY_IDS, fresh.keys).commit())
    }

    /** 兼容迁移中的调用方；故意忽略调用方可能过期的快照。 */
    @Suppress("UNUSED_PARAMETER")
    fun reschedule(context: Context, data: AppData) = reschedule(context)

    internal fun identity(id: String, kind: String): Uri = Uri.Builder()
        .scheme("mino").authority("reminder").appendPath(kind).appendPath(id).build()

    private fun pendingIntent(ctx: Context, id: String): PendingIntent = PendingIntent.getBroadcast(
        ctx, 0, Intent(ctx, ReminderReceiver::class.java).apply {
            action = "com.mino.app.REMIND"
            data = identity(id, "alarm")
            putExtra(EXTRA_ID, id)
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** 接收器队列满时使用：只调用系统闹钟，不读盘、不等待调度锁；到点仍复判数据。 */
    internal fun retryLater(context: Context, id: String) {
        val app = context.applicationContext
        val am = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        arm(app, am, id, System.currentTimeMillis() + RETRY_MS)
    }

    private fun arm(ctx: Context, am: AlarmManager, id: String, at: Long) {
        val pi = pendingIntent(ctx, id)
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun cancel(ctx: Context, am: AlarmManager, id: String) {
        val pi = pendingIntent(ctx, id)
        am.cancel(pi)
        pi.cancel()
        // 清理旧版本没有 data URI 的 PendingIntent；不再创建这种标识。
        PendingIntent.getBroadcast(ctx, id.hashCode(), Intent(ctx, ReminderReceiver::class.java).apply {
            action = "com.mino.app.REMIND"
        }, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
            am.cancel(it)
            it.cancel()
        }
    }

    fun handleFire(context: Context, id: String): Unit = synchronized(LOCK) {
        val app = context.applicationContext
        val store = Store(app)
        val local = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(ZoneId.systemDefault())
        val today = local.toLocalDate().toString()
        val hhmm = "%02d:%02d".format(java.util.Locale.ROOT, local.hour, local.minute)
        // 处理途中被杀或写盘失败仍有下一次唤醒；成功重排会替换它。
        (app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.let {
            arm(app, it, id, System.currentTimeMillis() + RETRY_MS)
        }
        var blocked = false
        var delivered = false
        var retry = true
        try {
            // Store 锁内复判并发送，避免「读完后 UI 已删除/改期，旧通知仍显示」。
            store.transact { data ->
                val t = data.todos.firstOrNull { it.id == id } ?: return@transact data
                if (!Logic.isDue(t, today, hhmm)) return@transact data
                // 通知发送与落盘不能原子提交：发送后崩溃会重试，属于至少一次语义。
                // data.json 的 remindedOn 保留未发送状态；同一 tag 降低重复提示。
                val notified = ReminderPolicy.deliver(t, today) { Notifications.show(app, it, today) }
                if (notified == null) {
                    blocked = true
                    return@transact data
                }
                delivered = true
                data.copy(todos = data.todos.map { if (it.id == id) notified else it })
            }
            retry = blocked
            // transact 正常返回才表示 remindedOn 已保存；让打开中的原生界面重拉。
            if (delivered) app.sendBroadcast(Intent(TODOS_CHANGED).setPackage(app.packageName))
        } finally {
            // 保存失败时 remindedOn 仍未消耗；重新武装同一通知的重试。
            if (!retry) {
                (app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.let { cancel(app, it, id) }
            }
            var scheduled = false
            try {
                reschedule(app)
                scheduled = true
            } finally {
                // 重排可能已经撤掉保护闹钟后才失败；无论失败在哪一步都重新武装。
                if (retry || !scheduled) {
                    (app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.let {
                        arm(app, it, id, System.currentTimeMillis() + RETRY_MS)
                    }
                }
            }
        }
    }

    fun completeOnce(context: Context, id: String, occurrence: String? = null): Unit = synchronized(LOCK) {
        val app = context.applicationContext
        var changed = 0
        var accepted = false
        Store(app).transact { data ->
            val t = data.todos.firstOrNull { it.id == id } ?: return@transact data
            if (Logic.isDaily(t) || !ReminderPolicy.matchesOccurrence(t, occurrence)) return@transact data
            accepted = true
            val (updated, n) = Logic.completeTodos(data, setOf(id), Logic.nowMs())
            changed = n
            updated
        }
        if (accepted && occurrence != null) Notifications.cancelOccurrence(app, id, occurrence)
        reschedule(app)
        if (changed > 0) app.sendBroadcast(Intent(TODOS_CHANGED).setPackage(app.packageName))
    }
    fun dismiss(context: Context, id: String, occurrence: String): Unit = synchronized(LOCK) {
        val app = context.applicationContext
        val todo = Store(app).read().todos.firstOrNull { it.id == id }
        if (todo != null && Logic.isDaily(todo) &&
            ReminderPolicy.matchesOccurrence(todo, occurrence)) {
            Notifications.cancelOccurrence(app, id, occurrence)
        }
    }
}
