package com.mino.app

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class UpdateClientTest {
    private val base = "https://github.com/jackpunck/MiNo/releases/download/v0.5.1/"
    private val info = AndroidUpdate(versionName = "0.5.1", versionCode = 501, minSdk = 26,
        apkUrl = base + "MiNo_android_0.5.1.apk", size = 1234, sha256 = "a".repeat(64))
    private fun release(size: Long = info.size) = """{"assets":[
        {"name":"android-update.json","browser_download_url":"${base}android-update.json"},
        {"name":"MiNo_android_0.5.1.apk","browser_download_url":"${info.apkUrl}","size":$size}
    ]}""".toByteArray()
    @Test fun fetchesMetadataOnlyWhenExplicitlyChecked() {
        val requests = mutableListOf<String>()
        val client = UpdateClient { url ->
            requests.add(url)
            if (url == UpdateClient.LATEST) release() else MinoJson.encodeToString(info).toByteArray()
        }
        assertTrue(requests.isEmpty())
        assertEquals(info, client.check())
        assertEquals(listOf(UpdateClient.LATEST, base + "android-update.json"), requests)
    }
    @Test fun desktopOnlyReleaseDoesNotPretendThereIsAnAndroidUpdate() {
        assertNull(UpdateClient { """{"assets":[]}""".toByteArray() }.check())
    }
    @Test fun rejectsManifestThatDisagreesWithPublishedAssets() {
        val client = UpdateClient { url ->
            if (url == UpdateClient.LATEST) release(999) else MinoJson.encodeToString(info).toByteArray()
        }
        assertThrows(IllegalArgumentException::class.java) { client.check() }
    }
    @Test fun neverFetchesAnExternalManifest() {
        var requests = 0
        val client = UpdateClient {
            requests++
            """{"assets":[{"name":"android-update.json","browser_download_url":"https://evil.test/a"}]}""".toByteArray()
        }
        assertThrows(IllegalArgumentException::class.java) { client.check() }
        assertEquals(1, requests)
    }
}
