package com.mino.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 固定单工作线程 + 有界队列；IO 不占主线程，每条 goAsync 都在 finally 结束。 */
private object ReceiverWork {
    private val executor = ThreadPoolExecutor(
        1, 1, 30L, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(32),
        { job -> Thread(job, "mino-reminder-io") }, ThreadPoolExecutor.AbortPolicy(),
    )

    fun submit(pending: BroadcastReceiver.PendingResult, onRejected: () -> Unit = {}, block: () -> Unit) {
        try {
            executor.execute {
                try {
                    block()
                } catch (_: Exception) {
                    // 不记异常内容：路径/解析错误可能携带用户任务文字。
                    Log.e("MiNo/Receiver", "提醒后台操作失败，保留数据等待重试")
                } finally {
                    pending.finish()
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            try {
                onRejected()
            } catch (_: Exception) {
                Log.e("MiNo/Receiver", "队列已满且重新武装失败")
            } finally {
                pending.finish()
            }
            Log.w("MiNo/Receiver", "提醒后台队列已满")
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(Reminders.EXTRA_ID) ?: return
        val app = context.applicationContext
        ReceiverWork.submit(goAsync(), onRejected = { Reminders.retryLater(app, id) }) {
            Reminders.handleFire(app, id)
        }
    }
}

/** 重启、时间/时区、更新及精确闹钟授权变更后重排。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ReminderPolicy.acceptsBootAction(intent.action)) return
        val app = context.applicationContext
        ReceiverWork.submit(goAsync()) { Reminders.reschedule(app) }
    }
}

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(Reminders.EXTRA_ID) ?: return
        val occurrence = intent.getStringExtra(Notifications.EXTRA_OCCURRENCE) ?: return
        val action = intent.action
        if (action != Notifications.ACTION_COMPLETE && action != Notifications.ACTION_DISMISS) return
        val app = context.applicationContext
        ReceiverWork.submit(goAsync()) {
            when (action) {
                // 保存成功后才撤通知；保存失败仍留给用户重试。
                Notifications.ACTION_COMPLETE -> Reminders.completeOnce(app, id, occurrence)
                Notifications.ACTION_DISMISS -> Reminders.dismiss(app, id, occurrence)
            }
        }
    }
}
