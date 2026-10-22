package com.mino.app

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 只依赖传入时钟；每日已完成只跳过今天，不丢掉明天的闹钟。 */
object ReminderPolicy {
    internal fun keepsNotification(todo: Todo?): Boolean =
        todo != null && !todo.completed && Logic.parseHhmm(todo.remindAt ?: "" ) != null

    internal fun notificationOccurrence(todo: Todo, day: String): String =
        "$day|${todo.remindAt}|${todo.remindMode}|${todo.remindDate.ifEmpty { if (Logic.isDaily(todo)) "" else todo.createdDate }}"

    /** 持续通知属于实际发送的那一天，而非检查时的今天；改期会清空 remindedOn。 */
    internal fun matchesOccurrence(todo: Todo?, occurrence: String?): Boolean {
        if (todo == null || !keepsNotification(todo) || todo.remindedOn.isBlank() || occurrence == null) return false
        return notificationOccurrence(todo, todo.remindedOn) == occurrence
    }

    internal fun acceptsBootAction(action: String?): Boolean = action in setOf(
        "android.intent.action.BOOT_COMPLETED",
        "android.intent.action.TIMEZONE_CHANGED",
        "android.intent.action.TIME_SET",
        "android.intent.action.MY_PACKAGE_REPLACED",
        "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
    )

    /** null 表示系统未接受；调用方不能写 remindedOn。 */
    internal fun deliver(todo: Todo, day: String, show: (Todo) -> Boolean): Todo? =
        if (show(todo)) todo.copy(remindedOn = day) else null

    fun nextTrigger(todo: Todo, now: Long, zone: ZoneId): Long? {
        val hm = Logic.parseHhmm(todo.remindAt ?: return null) ?: return null
        val hour = hm.substring(0, 2).toInt()
        val minute = hm.substring(3, 5).toInt()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        fun at(day: LocalDate) = day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
        if (Logic.isDaily(todo)) {
            if (todo.completed || todo.remindedOn == today.toString()) return at(today.plusDays(1))
            return at(today).let { if (it <= now) now + 1000L else it }
        }
        if (todo.completed || todo.remindedOn == today.toString()) return null
        val day = Logic.parseYmd(todo.remindDate.ifEmpty { todo.createdDate }) ?: return null
        val date = LocalDate.parse(day)
        if (date < today) return null
        return at(date).let { if (it <= now) now + 1000L else it }
    }
}
