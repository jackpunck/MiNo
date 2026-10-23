package com.mino.app

import kotlinx.serialization.Serializable
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.security.MessageDigest

@Serializable
internal data class AndroidUpdate(
    val schema: Int = 1,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val apkUrl: String,
    val size: Long,
    val sha256: String,
    val notes: String = "",
)

internal object UpdatePolicy {
    const val MAX_APK = 32L * 1024 * 1024
    fun validate(info: AndroidUpdate): AndroidUpdate {
        require(info.schema == 1 && info.versionCode > 0 && info.minSdk >= 26) { "更新信息版本不支持" }
        require(info.versionName.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) { "版本号格式不正确" }
        require(info.size in 1..MAX_APK && info.sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "安装包校验信息不完整" }
        require(info.notes.length <= 8000 && validAssetUrl(info.apkUrl)) { "更新下载地址不可信" }
        return info
    }
    fun validAssetUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 &&
            uri.userInfo == null && uri.query == null && uri.fragment == null &&
            !uri.rawPath.contains('%') && !uri.rawPath.contains("..") &&
            uri.rawPath.matches(Regex("/jackpunck/MiNo/releases/download/[^/]+/[^/]+"))
    }.getOrDefault(false)
    fun available(info: AndroidUpdate, installed: Long, sdk: Int): Boolean =
        validate(info).versionCode > installed && sdk >= info.minSdk

    /** Fixed-size buffer; rejects truncated, oversized or corrupt payloads before installation. */
    fun copyVerified(input: InputStream, output: OutputStream, info: AndroidUpdate,
                     progress: (Long) -> Unit = {}) {
        validate(info)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(16 * 1024)
        var count = 0L
        while (true) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
            val n = input.read(buffer)
            if (n < 0) break
            count += n
            require(count <= info.size) { "安装包大小不符" }
            digest.update(buffer, 0, n)
            output.write(buffer, 0, n)
            progress(count)
        }
        require(count == info.size) { "下载不完整，请重试" }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        require(actual.equals(info.sha256, true)) { "安装包校验失败，请重新下载" }
    }
}
