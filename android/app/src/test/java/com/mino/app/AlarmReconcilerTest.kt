package com.mino.app

import org.junit.Assert.*
import org.junit.Test

class AlarmReconcilerTest {
    @Test fun replacementNeverCancelsAnExistingLiveReminder() {
        val events = mutableListOf<String>()
        AlarmReconciler.apply(setOf("keep", "removed"), mapOf("keep" to 10L, "new" to 20L),
            { id, _ -> events.add("arm:$id") }, { events.add("cancel:$it") }, { events.add("persist:${it.sorted()}") })
        assertFalse(events.contains("cancel:keep"))
        assertTrue(events.indexOf("arm:new") < events.indexOf("cancel:removed"))
    }
    @Test fun interruptedReplacementLeavesPreviousAlarmsAndRecoveryJournalIntact() {
        var journal = emptySet<String>()
        val alarms = mutableSetOf("keep", "removed")
        assertThrows(IllegalStateException::class.java) {
            AlarmReconciler.apply(setOf("keep", "removed"), mapOf("keep" to 10L, "new" to 20L),
                { _, _ -> throw IllegalStateException("simulated termination") }, { alarms.remove(it) }, { journal = it })
        }
        assertEquals(setOf("keep", "removed"), alarms)
        assertEquals(setOf("keep", "removed", "new"), journal)
    }
    @Test fun successfulReconciliationRemovesOnlyObsoleteIds() {
        val alarms = mutableSetOf("old", "keep")
        var journal = emptySet<String>()
        AlarmReconciler.apply(alarms.toSet(), mapOf("keep" to 10L),
            { id, _ -> alarms.add(id) }, { alarms.remove(it) }, { journal = it })
        assertEquals(setOf("keep"), alarms)
        assertEquals(setOf("keep"), journal)
    }
}
