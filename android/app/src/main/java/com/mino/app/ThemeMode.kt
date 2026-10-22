package com.mino.app

/** Stable preference values; intentionally independent of Android/system night mode. */
internal enum class ThemeMode(val preferenceValue: String) {
    DAY("day"), NIGHT("night");

    fun requiresRecreation(selected: ThemeMode) = this != selected

    companion object {
        fun fromPreference(value: String?): ThemeMode = entries.firstOrNull { it.preferenceValue == value } ?: NIGHT
    }
}
