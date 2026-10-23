package com.mino.app

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class UpdatePolicyTest {
    private fun release() = AndroidUpdate(versionName = "0.5.1", versionCode = 501, minSdk = 26,
        apkUrl = "https://github.com/jackpunck/MiNo/releases/download/v0.5.1/MiNo_android_0.5.1.apk",
        size = 3, sha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    @Test fun acceptsOnlyNewerCompatibleVersions() {
        assertTrue(UpdatePolicy.available(release(), 500, 35))
        assertFalse(UpdatePolicy.available(release(), 501, 35))
        assertFalse(UpdatePolicy.available(release(), 500, 25))
    }
    @Test fun rejectsExternalOrAmbiguousDownloadAddresses() {
        for (url in listOf("http://github.com/jackpunck/MiNo/releases/download/v1/a.apk",
            "https://github.com/attacker/MiNo/releases/download/v1/a.apk",
            "https://github.com/jackpunck/MiNo/releases/download/v1/../a.apk",
            "https://github.com/jackpunck/MiNo/releases/download/v1/a.apk?x=1",
            "https://github.com@evil.test/jackpunck/MiNo/releases/download/v1/a.apk")) {
            assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.validate(release().copy(apkUrl = url)) }
        }
    }
    @Test fun streamedCopyChecksSizeAndDigest() {
        val out = ByteArrayOutputStream()
        UpdatePolicy.copyVerified(ByteArrayInputStream("abc".toByteArray()), out, release())
        assertEquals("abc", out.toString("UTF-8"))
        for (bytes in listOf("ab", "abcd", "abd")) {
            assertThrows(IllegalArgumentException::class.java) {
                UpdatePolicy.copyVerified(ByteArrayInputStream(bytes.toByteArray()), ByteArrayOutputStream(), release())
            }
        }
    }
    @Test fun invalidMetadataNeverReachesDownload() {
        for (info in listOf(release().copy(schema = 2), release().copy(size = -1),
            release().copy(size = 999_999_999), release().copy(sha256 = "bad"), release().copy(versionCode = 0))) {
            assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.validate(info) }
        }
    }
}
