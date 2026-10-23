package com.mino.app

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class UpdateDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(): Pair<File, AndroidUpdate> {
        val apk = File(context.cacheDir, "updates/device-test.apk")
        apk.parentFile!!.mkdirs()
        File(context.applicationInfo.sourceDir).copyTo(apk, overwrite = true)
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        val installed = UpdateInstaller.installed(context)
        val info = AndroidUpdate(versionName = installed.versionName!!,
            versionCode = UpdateInstaller.code(installed), minSdk = 26,
            apkUrl = "https://github.com/jackpunck/MiNo/releases/download/v0.5.0/a.apk",
            size = apk.length(), sha256 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
        return apk to info
    }
    @Suppress("DEPRECATION")
    @Test fun parsesSignedApkAndBuildsReadOnlyInstallerIntent() {
        val (apk, info) = fixture()
        try {
            val previous = UpdateInstaller.installed(context).apply { versionCode -= 1 }
            UpdateInstaller.validate(context, apk, info, previous)
            val intent = UpdateInstaller.intent(context, apk)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("content", intent.data!!.scheme)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertFalse(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
            context.contentResolver.openInputStream(intent.data!!)!!.use { assertEquals(0x50, it.read()) }
        } finally { apk.delete() }
    }
    @Test fun rejectsDowngradeAndTamperedApkBeforeInstaller() {
        val (apk, info) = fixture()
        try {
            assertThrows(IllegalArgumentException::class.java) { UpdateInstaller.validate(context, apk, info) }
            assertThrows(IllegalArgumentException::class.java) { UpdateInstaller.validate(context, apk, info.copy(sha256 = "0".repeat(64))) }
        } finally { apk.delete() }
    }
    @Test fun providerNeverExposesTodoFile() {
        assertThrows(IllegalArgumentException::class.java) {
            UpdateInstaller.intent(context, File(context.filesDir, "data.json"))
        }
    }
}
