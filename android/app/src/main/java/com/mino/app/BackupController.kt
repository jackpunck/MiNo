package com.mino.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.time.LocalDate
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Activity 只需转发结果；onRestored 在主线程调用，由 Activity 负责 recreate。 */
internal class BackupController(activity: Activity, onRestored: () -> Unit) {
    companion object {
        private const val EXPORT = 24011
        private const val IMPORT = 24012
    }

    private val owner = WeakReference(activity)
    private val app = activity.applicationContext
    // 回调可能捕获 Activity，与 owner 一起只在存活检查后使用。
    private val restored = onRestored
    private val main = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    // 独立串行队列，空闲后释放线程；不要求 Activity 新增销毁回调。
    private val executor = ThreadPoolExecutor(0, 1, 30L, TimeUnit.SECONDS,
        LinkedBlockingQueue<Runnable>()) { task -> Thread(task, "mino-backup") }

    fun export() = select(EXPORT, Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "application/json"
        putExtra(Intent.EXTRA_TITLE, "MiNo-${LocalDate.now()}.json")
    })

    fun importBackup() = select(IMPORT, Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    })

    @Suppress("DEPRECATION")
    private fun select(code: Int, intent: Intent) {
        ui { activity ->
            if (busy.compareAndSet(false, true)) {
                try { activity.startActivityForResult(intent, code) }
                catch (_: Exception) { finish("无法打开系统文件选择器") }
            }
        }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != EXPORT && requestCode != IMPORT) return false
        if (resultCode != Activity.RESULT_OK) { busy.set(false); return true }
        val uri = data?.data
        if (uri == null || uri.scheme != "content") {
            finish("未取得有效文件")
            return true
        }
        busy.set(true)
        executor.execute {
            if (requestCode == EXPORT) writeExport(uri) else readImport(uri)
        }
        return true
    }

    private fun writeExport(uri: Uri) {
        try {
            val bytes = BackupCodec.encode(Store(app).read(), ThemePreference.read(app))
            app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes); it.flush() }
                ?: error("No output stream")
            finish("备份已导出")
        } catch (_: Exception) { finish("导出失败，请检查所选位置是否可写") }
    }

    private fun readImport(uri: Uri) {
        try {
            val backup = app.contentResolver.openInputStream(uri)?.use { BackupCodec.decode(it) }
                ?: error("No input stream")
            ui { activity ->
                try {
                    AlertDialog.Builder(activity).setTitle("覆盖本地数据？")
                        .setMessage("备份包含 ${backup.data.todos.size} 条待办、${backup.data.archives.size} 条归档。\n" +
                            "导入将覆盖全部本地待办、归档、设置和主题，不能合并。导入前会保留旧本地快照。")
                        .setNegativeButton("取消") { _, _ -> busy.set(false) }
                        .setPositiveButton("确认覆盖") { _, _ ->
                            // 对话框显示后 Activity 仍可能已销毁。
                            if (!activity.isFinishing && !activity.isDestroyed) {
                                executor.execute { restore(backup) }
                            } else busy.set(false)
                        }.setOnCancelListener { busy.set(false) }.show()
                } catch (_: Exception) {
                    finish("无法显示覆盖确认，本地数据未更改")
                }
            }
        } catch (_: Exception) {
            // 不回显解析器异常：异常可能带有用户任务正文。
            finish("无法导入：文件损坏、版本不支持或超过 8 MiB；本地数据未更改")
        }
    }

    private fun restore(backup: BackupCodec.Backup) {
        var recovery: String? = null
        var saved = false
        try {
            val token = ReminderRecoveryJob.schedule(app)
            recovery = token
            Store(app).transact { current ->
                // 在 Store 的锁内先持久保存旧数据和主题，失败就不覆盖。每次保留独立文件。
                val bytes = BackupCodec.encode(current, ThemePreference.read(app))
                val snapshot = File.createTempFile("mino-before-import-", ".json", app.filesDir)
                FileOutputStream(snapshot).use { out -> out.write(bytes); out.flush(); out.fd.sync() }
                backup.data
            }
            saved = true
            ThemePreference.write(app, backup.theme)
            Reminders.reschedule(app)
            ReminderRecoveryJob.complete(app, token)
            recovery = null
            finish("备份已导入", notifyRestored = true)
        } catch (_: Exception) {
            recovery?.let { ReminderRecoveryJob.abandon(it) }
            finish(if (saved) "数据已导入，后续设置或提醒处理失败；系统将重试提醒修复"
                else "导入失败，未覆盖本地数据", notifyRestored = saved)
        }
    }

    private fun finish(message: String, notifyRestored: Boolean = false) {
        busy.set(false)
        ui { activity ->
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            if (notifyRestored) restored()
        }
    }

    private fun ui(block: (Activity) -> Unit) {
        main.post {
            val activity = owner.get()
            if (activity != null && !activity.isFinishing && !activity.isDestroyed) block(activity)
            else busy.set(false)
        }
    }
}
