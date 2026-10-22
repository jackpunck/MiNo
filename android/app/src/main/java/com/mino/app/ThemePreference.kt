package com.mino.app

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.View

/** Appearance lives separately from the authoritative todo JSON. No system-mode option. */
internal object ThemePreference {
    private const val FILE = "mino_appearance"
    private const val KEY = "theme_mode"

    fun read(context: Context): ThemeMode {
        val value = try {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)
        } catch (_: ClassCastException) { null }
        return ThemeMode.fromPreference(value)
    }

    fun write(context: Context, mode: ThemeMode) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, mode.preferenceValue).apply()
    }

    /** Call before super.onCreate so native dialogs and pickers inherit the selected theme. */
    @Suppress("DEPRECATION")
    fun apply(activity: Activity): ThemeMode {
        val mode = read(activity)
        activity.setTheme(if (mode == ThemeMode.DAY) R.style.Theme_MiNo_Day else R.style.Theme_MiNo)
        val window = activity.window
        window.statusBarColor = NativeUi.background(activity)
        window.navigationBarColor = NativeUi.background(activity)
        var flags = window.decorView.systemUiVisibility
        flags = if (mode == ThemeMode.DAY) flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            else flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        // LIGHT_NAVIGATION_BAR was added in 27, while this app supports 26.
        if (Build.VERSION.SDK_INT >= 27) {
            flags = if (mode == ThemeMode.DAY) flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                else flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        } else {
            // API 26 only supports light navigation icons.
            window.navigationBarColor = android.graphics.Color.rgb(25, 25, 30)
        }
        window.decorView.systemUiVisibility = flags
        if (Build.VERSION.SDK_INT >= 29) window.decorView.isForceDarkAllowed = false
        return mode
    }
}
