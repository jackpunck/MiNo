package com.mino.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.ProgressBar
import android.widget.TextView
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.concurrent.Future

/** The Activity owns this screen; closing it cancels work and never starts an installer later. */
internal class UpdateController(private val activity: Activity, private val isForeground: () -> Boolean) {
    companion object {
        // Serialize cache writes across Activity recreation; idle workers are released.
        private val executor = java.util.concurrent.ThreadPoolExecutor(0, 1, 30L,
            java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.LinkedBlockingQueue<Runnable>()) {
            Thread(it, "mino-update")
        }
    }
    private val app = activity.applicationContext
    private val client = UpdateClient()
    private val directory = File(app.cacheDir, "updates")
    private val metadata get() = File(directory, "android-update.json")
    private val apk get() = File(directory, "update.apk")
    private var task: Future<*>? = null
    private var dialog: AlertDialog? = null
    private var status: TextView? = null
    private var progress: ProgressBar? = null
    private var candidate: AndroidUpdate? = null
    private var ready = false
    private var busy = false
    private var generation = 0

    fun show() {
        if (dialog != null) return
        val current = UpdateInstaller.installed(app)
        val panel = NativeUi.panel(activity)
        status = NativeUi.label(activity, "当前版本 ${current.versionName}\n仅点击检查更新时联网。", 15f)
        progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = android.view.View.GONE }
        panel.addView(status); panel.addView(progress)
        val scroll = android.widget.ScrollView(activity).apply { addView(panel) }
        val window = AlertDialog.Builder(activity).setTitle("检查更新").setView(scroll)
            .setNegativeButton("关闭", null).setNeutralButton("检查更新", null).setPositiveButton("下载新版", null)
            .create()
        dialog = window
        window.setOnDismissListener {
            generation++
            client.cancel()
            task?.cancel(true)
            busy = false
            dialog = null
            status = null
            progress = null
        }
        window.show()
        window.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { check() }
        window.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { if (ready) install() else download() }
        candidate = null
        ready = false
        buttons()
        work({
            if (!apk.isFile || !metadata.isFile || metadata.length() > 256 * 1024) null
            else runCatching {
                val info = MinoJson.decodeFromString<AndroidUpdate>(metadata.readText())
                UpdateInstaller.validate(app, apk, info)
                info
            }.getOrNull()
        }) { cached ->
            if (cached != null) {
                candidate = cached; ready = true
                status?.text = "新版 ${cached.versionName} 已下载并校验，可点击安装。\n覆盖安装会保留现有数据。"
            }
        }
    }
    private fun buttons() {
        val window = dialog ?: return
        window.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = !busy
        window.getButton(AlertDialog.BUTTON_POSITIVE).apply {
            text = if (ready) "安装新版" else "下载新版"
            isEnabled = !busy && candidate != null
        }
    }
    private fun check() {
        candidate = null; ready = false
        status?.text = "正在检查…"
        work({ client.check() }) { info ->
            val current = UpdateInstaller.code(UpdateInstaller.installed(app))
            when {
                info == null -> status?.text = "暂未发布 Android 更新。"
                info.minSdk > Build.VERSION.SDK_INT -> status?.text = "新版需要更高的 Android 系统版本。"
                !UpdatePolicy.available(info, current, Build.VERSION.SDK_INT) -> status?.text = "当前已是最新版本。"
                else -> {
                    candidate = info
                    status?.text = "发现新版 ${info.versionName}（${(info.size + 1023) / 1024} KB）\n\n${info.notes.ifBlank { "本次版本改进" }}\n\n覆盖安装保留数据，也可以先从设置导出备份。"
                }
            }
        }
    }
    private fun download() {
        val info = candidate ?: return
        status?.text = "正在下载 ${info.versionName}…"
        progress?.visibility = android.view.View.VISIBLE
        val ticket = generation
        work({
            var percent = -1
            val file = client.download(info, directory) { count ->
                val next = (count * 100 / info.size).toInt()
                if (next != percent) {
                    percent = next
                    activity.runOnUiThread { if (ticket == generation) progress?.progress = next }
                }
            }
            try { UpdateInstaller.validate(app, file, info) }
            catch (e: Exception) { file.delete(); metadata.delete(); throw e }
            val tmp = File(directory, "android-update.json.tmp")
            tmp.writeText(MinoJson.encodeToString(info))
            java.nio.file.Files.move(tmp.toPath(), metadata.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            file
        }) {
            ready = true
            progress?.visibility = android.view.View.GONE
            status?.text = "下载并校验完成。点击安装新版，由系统确认安装。\n现有数据会保留。"
        }
    }
    private fun install() {
        val info = candidate ?: return
        if (!app.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity).setTitle("允许安装新版")
                .setMessage("需要在系统设置中允许 MiNo 安装应用。返回后再次点击“安装新版”。")
                .setNegativeButton("取消", null).setPositiveButton("前往设置") { _, _ ->
                    runCatching { activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${app.packageName}"))) }.onFailure { status?.text = "无法打开安装权限设置，请在应用信息中设置。" }
                }.show()
            return
        }
        work({
            try { UpdateInstaller.validate(app, apk, info) }
            catch (e: Exception) { apk.delete(); metadata.delete(); throw e }
        }, onFailure = { ready = false }) {
            if (!isForeground()) {
                status?.text = "安装包已校验。返回 MiNo 后再次点击安装新版。"
                return@work
            }
            try { activity.startActivity(UpdateInstaller.intent(app, apk)) }
            catch (_: Exception) { status?.text = "无法打开系统安装界面，请检查安装权限。" }
        }
    }
    private fun <T> work(block: () -> T, onFailure: () -> Unit = {}, done: (T) -> Unit) {
        if (busy) return
        busy = true; buttons()
        val ticket = generation
        task = executor.submit {
            if (Thread.currentThread().isInterrupted) return@submit
            client.resetCancellation()
            val result = runCatching(block)
            activity.runOnUiThread {
                if (ticket != generation || dialog == null || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                busy = false
                result.onSuccess(done).onFailure { error ->
                    onFailure()
                    progress?.visibility = android.view.View.GONE
                    // Never display untrusted network/parser exception bodies.
                    status?.text = if (error is IllegalArgumentException || error is IllegalStateException)
                        error.message?.takeIf { it.length <= 160 } ?: "更新校验失败，请重试。"
                    else "连接或下载失败，请检查网络后重试。"
                }
                buttons()
            }
        }
    }
    fun destroy() { dialog?.dismiss(); client.cancel(); task?.cancel(true) }
}
