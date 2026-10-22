package com.mino.app

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.ContextThemeWrapper
import android.widget.EditText
import android.widget.NumberPicker
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimeEditorDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun all(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()

    private fun withHost(block: (PickerTestActivity) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, PickerTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PickerTestActivity
        try { instrumentation.runOnMainSync { block(activity) } }
        finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun wheelsAre24HourAndConfirmPaddedTime() = withHost { activity ->
        var result: String? = null
        val editor = TimeEditor(activity, "09:00", null, { result = it }, {})
        editor.show()
        val wheels = all(editor.dialog.window!!.decorView).filterIsInstance<NumberPicker>()
        assertEquals(2, wheels.size)
        assertEquals(0, wheels[0].minValue); assertEquals(23, wheels[0].maxValue)
        assertEquals(0, wheels[1].minValue); assertEquals(59, wheels[1].maxValue)
        assertEquals("00", wheels[0].displayedValues.first())
        assertEquals("23", wheels[0].displayedValues.last())
        wheels[0].value = 23; wheels[1].value = 59
        editor.dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals("23:59", result)
    }

    @Test fun onlyWheelsAreShownAndKeyboardCannotReceiveFocus() = withHost { activity ->
        val editor = TimeEditor(activity, "00:00", null, {}, {})
        editor.show()
        val views = all(editor.dialog.window!!.decorView)
        assertFalse(views.filterIsInstance<TextView>().any {
            it.text.contains("键盘输入") || it.text.contains("滚动选择")
        })
        val wheels = views.filterIsInstance<NumberPicker>()
        assertEquals(2, wheels.size)
        for (wheel in wheels) {
            assertEquals(ViewGroup.FOCUS_BLOCK_DESCENDANTS, wheel.descendantFocusability)
            for (field in all(wheel).filterIsInstance<EditText>()) assertFalse(field.requestFocus())
        }
        editor.dialog.dismiss()
    }

    @Test fun cancelDoesNotApplyAndDraftSurvivesRecreation() = withHost { activity ->
        var result: String? = null
        val restored = Bundle().apply {
            putInt("hour", 23); putInt("minute", 17)
        }
        val first = TimeEditor(activity, "09:00", restored, { result = it }, {})
        first.show()
        val saved = first.state()
        first.dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertNull(result)
        val second = TimeEditor(activity, "09:00", saved, { result = it }, {})
        second.show()
        assertEquals(23, second.state().getInt("hour"))
        assertEquals(17, second.state().getInt("minute"))
        second.dialog.dismiss()
    }

    @Test fun dayAndNightPalettesKeepNativePickerTextReadable() = withHost { activity ->
        for (style in listOf(R.style.Theme_MiNo_Day, R.style.Theme_MiNo)) {
            val context = ContextThemeWrapper(activity, style)
            assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(
                NativeUi.text(context), NativeUi.background(context)) >= 4.5)
            assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(
                NativeUi.secondary(context), NativeUi.background(context)) >= 4.5)
            val editor = TimeEditor(context, "23:59", null, {}, {})
            editor.show()
            val numberText = all(editor.dialog.window!!.decorView).filterIsInstance<NumberPicker>()
                .flatMap { all(it).filterIsInstance<EditText>() }
            assertEquals(2, numberText.size)
            for (field in numberText) assertTrue("Wheel text must contrast with selected theme",
                androidx.core.graphics.ColorUtils.calculateContrast(field.currentTextColor, NativeUi.background(context)) >= 4.5)
            editor.dialog.dismiss()
        }
    }

    @Test fun themeChoiceIsStoredSeparatelyFromTasks() = withHost { activity ->
        val original = ThemePreference.read(activity)
        try {
            ThemePreference.write(activity, ThemeMode.DAY)
            assertEquals(ThemeMode.DAY, ThemePreference.read(activity.applicationContext))
            ThemePreference.write(activity, ThemeMode.NIGHT)
            assertEquals(ThemeMode.NIGHT, ThemePreference.read(activity.applicationContext))
        } finally { ThemePreference.write(activity, original) }
    }

}
