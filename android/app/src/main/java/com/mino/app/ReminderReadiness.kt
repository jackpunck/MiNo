package com.mino.app

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.NotificationManager
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/** Read-only readiness checks. Settings intents are launched only by explicit UI actions. */
object ReminderReadiness {
    fun exactAllowed(context: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
        runCatching { context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() }.getOrDefault(false)

    fun summary(context: Context): String {
        val manager = context.getSystemService(NotificationManager::class.java)
        val allowed = (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) && manager.areNotificationsEnabled()
        val channel = manager.getNotificationChannel(Notifications.CHANNEL_ID)
        val notifications = allowed && (channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE)
        val battery = runCatching {
            context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        }.getOrNull()
        val restricted = if (Build.VERSION.SDK_INT >= 28) runCatching {
            context.getSystemService(ActivityManager::class.java).isBackgroundRestricted
        }.getOrNull() else false
        return "通知：${if (notifications) "已开启" else "未开启"}\n" +
            "精确闹钟：${if (exactAllowed(context)) "已开启" else "未开启（使用近似提醒）"}\n" +
            "电池优化：${when (battery) { true -> "已豁免"; false -> "未豁免"; null -> "无法读取" }}\n" +
            "后台限制：${when (restricted) { true -> "受限，请在应用信息中允许后台运行"; false -> "系统未报告限制"; null -> "无法读取" }}"
    }

    const val APPROXIMATE = "未允许精确闹钟时使用近似提醒。Android 12 及以上通常可能延迟最多约一小时；省电或后台限制下可能更久。"
    const val BACKGROUND = "提醒由系统闹钟触发，不需要应用常驻。切换应用或普通进程回收不等于强行停止；部分厂商清理最近任务可能同时限制后台。请在应用信息中检查电池、后台运行和自启动设置。强行停止后提醒可能无法触发，需要重新打开 MiNo；这些设置不能保证绕过厂商限制。"

    fun exactSettings(context: Context) = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
    fun batterySettings() = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    fun appSettings(context: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
}
