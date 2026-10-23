package com.mino.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File

internal object UpdateInstaller {
    @Suppress("DEPRECATION")
    fun installed(context: Context): PackageInfo = context.packageManager.getPackageInfo(context.packageName,
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)

    @Suppress("DEPRECATION")
    fun code(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    /** No signing-key rotation in this release channel; exact same signing identity is required. */
    @Suppress("DEPRECATION")
    fun validate(context: Context, file: File, update: AndroidUpdate,
                 current: PackageInfo = installed(context)) {
        val discard = object : java.io.OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
        }
        file.inputStream().use { UpdatePolicy.copyVerified(it, discard, update) }
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val candidate = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: error("无法识别安装包")
        require(candidate.packageName == context.packageName) { "安装包不属于 MiNo" }
        require(code(candidate) == update.versionCode && code(candidate) > code(current) && candidate.versionName == update.versionName) { "安装包版本不符或不是新版" }
        fun signers(info: PackageInfo): Set<String> = (if (Build.VERSION.SDK_INT >= 28)
            info.signingInfo?.apkContentsSigners else info.signatures)?.map { it.toCharsString() }?.toSet().orEmpty()
        val actual = signers(candidate)
        require(actual.isNotEmpty() && actual == signers(current)) { "新版签名与当前版本不同。请先导出备份，按正式版迁移说明安装" }
    }
    fun intent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = android.content.ClipData.newRawUri("MiNo update", uri) }
    }
}

