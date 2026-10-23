package com.mino.app

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ReminderPolicyTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun ms(s: String) = ZonedDateTime.parse(s).toInstant().toEpochMilli()
    private val now = ms("2026-10-22T10:00:00+08:00")
    private val daily = Todo(remindAt = "09:00", remindMode = "daily")

    @Test fun completedDailySchedulesTomorrow() {
        assertEquals(ms("2026-10-23T09:00:00+08:00"), ReminderPolicy.nextTrigger(daily.copy(completed = true), now, zone))
    }
    @Test fun completedOnceNeverSchedules() {
        assertNull(ReminderPolicy.nextTrigger(daily.copy(completed = true, remindMode = "once"), now, zone))
    }
    @Test fun overdueDailyCatchesUpToday() {
        assertEquals(now + 1000, ReminderPolicy.nextTrigger(daily, now, zone))
    }
    @Test fun deliveredDailySchedulesTomorrow() {
        assertEquals(ms("2026-10-23T09:00:00+08:00"), ReminderPolicy.nextTrigger(daily.copy(remindedOn = "2026-10-22"), now, zone))
    }
    @Test fun onceNeverCatchesUpAcrossDays() {
        assertNull(ReminderPolicy.nextTrigger(daily.copy(remindMode = "once", createdDate = "2026-10-21"), now, zone))
    }
    @Test fun onceCatchesUpWithinItsDate() {
        assertEquals(now + 1000, ReminderPolicy.nextTrigger(daily.copy(remindMode = "once", createdDate = "2026-10-22"), now, zone))
    }
    @Test fun invalidTimeDoesNotSchedule() {
        assertNull(ReminderPolicy.nextTrigger(daily.copy(remindAt = "25:00"), now, zone))
    }
    @Test fun dailyUsesCalendarDaysAcrossDst() {
        val z = ZoneId.of("America/New_York")
        val n = ms("2026-03-07T10:00:00-05:00")
        assertEquals(ms("2026-03-08T09:00:00-04:00"), ReminderPolicy.nextTrigger(daily.copy(completed = true), n, z))
    }
    @Test fun deniedDeliveryDoesNotConsumeReminder() {
        assertNull(ReminderPolicy.deliver(daily, "2026-10-22") { false })
        assertEquals("", daily.remindedOn)
    }
    @Test fun successfulDeliveryMarksOnlyAfterAcceptance() {
        val updated = ReminderPolicy.deliver(daily.copy(id = "Aa"), "2026-10-22") {
            assertEquals("", it.remindedOn)
            assertEquals("Aa", it.id)
            true
        }
        assertEquals("2026-10-22", updated!!.remindedOn)
    }
    @Test fun throwingDeliveryDoesNotReturnConsumedStatus() {
        assertThrows(IllegalStateException::class.java) {
            ReminderPolicy.deliver(daily, "2026-10-22") { throw IllegalStateException("blocked") }
        }
    }
    @Test fun onlyCurrentIncompleteReminderKeepsItsNotification() {
        assertFalse(ReminderPolicy.keepsNotification(null))
        assertFalse(ReminderPolicy.keepsNotification(daily.copy(completed = true)))
        assertFalse(ReminderPolicy.keepsNotification(daily.copy(remindAt = null)))
        assertFalse(ReminderPolicy.keepsNotification(daily.copy(remindAt = "")))
        assertTrue(ReminderPolicy.keepsNotification(daily))
    }
    @Test fun occurrenceIsStableOnRetryAndChangesTheNextDay() {
        val first = ReminderPolicy.notificationOccurrence(daily, "2026-10-22")
        assertEquals(first, ReminderPolicy.notificationOccurrence(daily, "2026-10-22"))
        assertNotEquals(first, ReminderPolicy.notificationOccurrence(daily, "2026-10-23"))
    }
    @Test fun oldOnceActionCannotCompleteRescheduledTask() {
        val old = daily.copy(remindMode = "once", remindDate = "2026-10-22", remindedOn = "2026-10-22")
        val occurrence = ReminderPolicy.notificationOccurrence(old, "2026-10-22")
        assertTrue(ReminderPolicy.matchesOccurrence(old, occurrence))
        assertFalse(ReminderPolicy.matchesOccurrence(old.copy(remindDate = "2026-10-23"), occurrence))
        assertFalse(ReminderPolicy.matchesOccurrence(old.copy(remindAt = "11:00"), occurrence))
        assertFalse(ReminderPolicy.matchesOccurrence(old.copy(remindMode = "daily"), occurrence))
    }
    @Test fun oldDailyDismissCannotDismissNewDay() {
        val occurrence = ReminderPolicy.notificationOccurrence(daily, "2026-10-22")
        assertFalse(ReminderPolicy.matchesOccurrence(daily.copy(remindedOn = "2026-10-23"), occurrence))
        assertFalse(ReminderPolicy.matchesOccurrence(daily.copy(remindedOn = "2026-10-22"), null))
        assertFalse(ReminderPolicy.matchesOccurrence(null, occurrence))
        assertFalse(ReminderPolicy.matchesOccurrence(daily.copy(completed = true), occurrence))
    }
    @Test fun bootActionsAreExplicitlyAllowlisted() {
        val actions = listOf("android.intent.action.BOOT_COMPLETED", "android.intent.action.TIMEZONE_CHANGED",
            "android.intent.action.TIME_SET", "android.intent.action.MY_PACKAGE_REPLACED",
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")
        assertTrue(actions.all { ReminderPolicy.acceptsBootAction(it) })
        assertFalse(ReminderPolicy.acceptsBootAction(null))
        assertFalse(ReminderPolicy.acceptsBootAction("com.other.SPURIOUS"))
    }
    @Test fun deliveredOnceRemainsActionableAfterMidnightUnlessReminderChanged() {
        val delivered = Todo(id = "once", remindAt = "09:00", remindMode = "once",
            remindDate = "2026-10-22", remindedOn = "2026-10-22")
        val occurrence = ReminderPolicy.notificationOccurrence(delivered, delivered.remindedOn)
        val data = AppData(settings = Settings(lastCheckDate = "2026-10-22"), todos = listOf(delivered))
        val tomorrow = Logic.rollover(data, "2026-10-23", now + 86400000)!!.todos.single()
        assertTrue(ReminderPolicy.matchesOccurrence(tomorrow, occurrence))
        assertFalse(ReminderPolicy.matchesOccurrence(tomorrow.copy(remindedOn = ""), occurrence))
        val changed = Logic.setTodoReminder(data, "once", "10:00", "once", "2026-10-23", "2026-10-23")
        assertEquals("", changed.todos.single().remindedOn)
        assertFalse(ReminderPolicy.matchesOccurrence(changed.todos.single(), occurrence))
    }
    @Test fun yesterdayDailyDismissIsValidOnlyUntilAnotherOccurrenceIsDelivered() {
        val previous = daily.copy(remindedOn = "2026-10-22")
        val occurrence = ReminderPolicy.notificationOccurrence(previous, previous.remindedOn)
        assertTrue(ReminderPolicy.matchesOccurrence(previous, occurrence))
        assertFalse(ReminderPolicy.matchesOccurrence(previous.copy(remindedOn = "2026-10-23"), occurrence))
    }
}
