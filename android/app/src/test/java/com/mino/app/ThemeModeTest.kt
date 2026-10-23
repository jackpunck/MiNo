package com.mino.app

import org.junit.Assert.*
import org.junit.Test

class ThemeModeTest {
    @Test fun absentOrUnknownPreferenceKeepsExistingNightDefault() {
        for (value in listOf(null, "", "system", "DAY", "unknown")) {
            assertEquals(ThemeMode.NIGHT, ThemeMode.fromPreference(value))
        }
    }
    @Test fun explicitChoicesRoundTripWithoutSystemMode() {
        assertEquals(2, ThemeMode.entries.size)
        for (mode in ThemeMode.entries) assertEquals(mode, ThemeMode.fromPreference(mode.preferenceValue))
        assertEquals(ThemeMode.DAY, ThemeMode.fromPreference("day"))
        assertEquals(ThemeMode.NIGHT, ThemeMode.fromPreference("night"))
    }
    @Test fun selectingCurrentModeDoesNotRecreate() {
        assertFalse(ThemeMode.DAY.requiresRecreation(ThemeMode.DAY))
        assertFalse(ThemeMode.NIGHT.requiresRecreation(ThemeMode.NIGHT))
        assertTrue(ThemeMode.DAY.requiresRecreation(ThemeMode.NIGHT))
        assertTrue(ThemeMode.NIGHT.requiresRecreation(ThemeMode.DAY))
    }
}
