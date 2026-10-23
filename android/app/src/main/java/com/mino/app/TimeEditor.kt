package com.mino.app

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.NumberPicker
import java.util.Locale

/** Native 24-hour wheels only. The surrounding reminder still requires Save. */
internal class TimeEditor(
    private val context: Context,
    initial: String,
    restored: Bundle?,
    private val confirmed: (String) -> Unit,
    private val closed: () -> Unit,
) {
    private val hour = wheel(23, "小时（00至23）")
    private val minute = wheel(59, "分钟（00至59）")
    val dialog: AlertDialog

    init {
        val hm = Logic.parseHhmm(initial) ?: "09:00"
        hour.value = restored?.getInt("hour", hm.take(2).toInt()) ?: hm.take(2).toInt()
        minute.value = restored?.getInt("minute", hm.takeLast(2).toInt()) ?: hm.takeLast(2).toInt()
        val row = LinearLayout(context).apply { gravity = Gravity.CENTER }
        row.addView(hour, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(NativeUi.label(context, ":", 28f))
        row.addView(minute, LinearLayout.LayoutParams(0, -2, 1f))
        val panel = NativeUi.panel(context).apply { addView(row) }
        dialog = AlertDialog.Builder(context).setTitle("提醒时间 · 24小时制")
            .setView(panel).setNegativeButton("取消", null).setPositiveButton("确定", null).create()
        dialog.setOnDismissListener { closed() }
    }

    private fun wheel(max: Int, label: String) = NumberPicker(context).apply {
        minValue = 0; maxValue = max; wrapSelectorWheel = true
        displayedValues = (0..max).map { it.toString().padStart(2, '0') }.toTypedArray()
        // Block the NumberPicker's internal EditText as well as any soft keyboard.
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        contentDescription = label
    }

    fun show() {
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            confirmed(String.format(Locale.ROOT, "%02d:%02d", hour.value, minute.value))
            dialog.dismiss()
        }
    }

    fun state() = Bundle().apply {
        putInt("hour", hour.value); putInt("minute", minute.value)
    }
}
