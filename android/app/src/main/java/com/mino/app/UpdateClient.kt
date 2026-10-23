package com.mino.app

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Only called by the manual update screen; contains no startup or periodic network work. */
internal class UpdateClient(private val readOverride: ((String) -> ByteArray)? = null) {
    @Volatile private var active: HttpURLConnection? = null
    @Volatile private var cancelled = false
    fun cancel() { cancelled = true; active?.disconnect() }
    fun resetCancellation() { cancelled = false }
    private fun ensureActive() {
        if (cancelled || Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
    }
    companion object {
        const val LATEST = "https://api.github.com/repos/jackpunck/MiNo/releases/latest"
        const val METADATA = "android-update.json"
        private const val MAX_JSON = 256 * 1024
    }
    fun check(): AndroidUpdate? {
        val release = try { MinoJson.parseToJsonElement(String(readSmall(LATEST), Charsets.UTF_8)).jsonObject }
            catch (_: NotPublished) { return null }
        if (release["draft"]?.jsonPrimitive?.booleanOrNull == true ||
            release["prerelease"]?.jsonPrimitive?.booleanOrNull == true) return null
        val assets = release["assets"]?.jsonArray ?: return null
        val metadata = assets.map { it.jsonObject }.singleOrNull {
            it["name"]?.jsonPrimitive?.content == METADATA
        } ?: return null
        val address = metadata["browser_download_url"]?.jsonPrimitive?.content ?: return null
        require(UpdatePolicy.validAssetUrl(address)) { "更新信息地址不可信" }
        val info = UpdatePolicy.validate(MinoJson.decodeFromString<AndroidUpdate>(String(readSmall(address), Charsets.UTF_8)))
        val apk = assets.map { it.jsonObject }.singleOrNull {
            it["browser_download_url"]?.jsonPrimitive?.content == info.apkUrl
        }
        require(apk != null && apk["size"]?.jsonPrimitive?.longOrNull == info.size) { "发布资源与更新信息不一致" }
        return info
    }
    fun download(info: AndroidUpdate, directory: File, progress: (Long) -> Unit): File {
        directory.mkdirs()
        // All production downloads use the same serial executor; discard crash leftovers.
        directory.listFiles()?.filter { it.name.startsWith("update-") && it.extension == "part" }?.forEach { it.delete() }
        val partial = File.createTempFile("update-", ".part", directory)
        val target = File(directory, "update.apk")
        val connection = try { connect(info.apkUrl) }
            catch (e: Exception) { partial.delete(); throw e }
        try {
            connection.inputStream.use { input -> FileOutputStream(partial).use { out ->
                UpdatePolicy.copyVerified(input, out, info) { count -> ensureActive(); progress(count) }
                out.flush(); out.fd.sync()
            } }
            ensureActive()
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return target
        } finally { connection.disconnect(); partial.delete() }
    }
    private fun readSmall(address: String): ByteArray {
        readOverride?.let { reader ->
            return reader(address).also { require(it.size <= MAX_JSON) { "更新信息过大" } }
        }
        val c = connect(address)
        try {
            return c.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                    val n = input.read(buffer)
                    if (n < 0) break
                    require(out.size() + n <= MAX_JSON) { "更新信息过大" }
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
        } finally { c.disconnect() }
    }
    private class NotPublished : IOException()
    private fun connect(address: String): HttpURLConnection {
        var url = URL(address)
        repeat(6) {
            ensureActive()
            require(url.protocol == "https" && url.userInfo == null && url.port == -1 &&
                url.host in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")) { "下载跳转地址不可信" }
            val c = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000; readTimeout = 20_000
                setRequestProperty("User-Agent", "MiNo-Android")
                setRequestProperty("Accept", if (url.host == "api.github.com") "application/vnd.github+json" else "application/octet-stream")
                setRequestProperty("Accept-Encoding", "identity")
            }
            active = c
            try {
                when (c.responseCode) {
                    200 -> return c
                    301, 302, 303, 307, 308 -> {
                        val location = c.getHeaderField("Location") ?: throw IOException("下载跳转失败")
                        url = URL(url, location)
                        c.disconnect()
                    }
                    404 -> { c.disconnect(); throw NotPublished() }
                    403, 429 -> throw IOException("更新服务暂时受限，请稍后重试")
                    else -> throw IOException("更新服务暂时不可用，请稍后重试")
                }
            } catch (e: Exception) { c.disconnect(); throw e }
        }
        throw IOException("更新地址跳转过多")
    }
}
