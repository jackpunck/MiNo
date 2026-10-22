package com.mino.app

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import java.time.LocalDate

/** Draft-only editor: no persistence until the user explicitly saves or removes. */
internal class ReminderEditor(
    private val context: Context,
    val id: String,
    todo: Todo,
    restored: Bundle?,
    private val permissions: () -> String,
    private val openPermissions: () -> Unit,
    private val save: (String, String, String, (Boolean) -> Unit) -> Unit,
    private val closed: () -> Unit,
) {
    private var mode = restored?.getString("mode") ?: if (Logic.isDaily(todo)) "daily" else "once"
    private var date = restored?.getString("date") ?: if (todo.remindAt.isNullOrBlank()) Logic.today()
        else todo.remindDate.ifBlank { todo.createdDate.ifBlank { Logic.today() } }
    private var at = restored?.getString("at") ?: todo.remindAt?.takeIf { Logic.parseHhmm(it) != null } ?: "09:00"
    private val panel = NativeUi.panel(context)
    private val dates = LinearLayout(context)
    private val preview = NativeUi.label(context, "", 18f)
    private val status = NativeUi.button(context, "") { openPermissions() }
    private val once = NativeUi.button(context, "指定日期") { mode = "once"; update() }
    private val daily = NativeUi.button(context, "每天") { mode = "daily"; update() }
    private val pickDate = NativeUi.button(context, "选择日期") { showDate() }
    private val pickTime = NativeUi.button(context, "") { showTime() }
    private var picker: android.app.Dialog? = null
    private var timeEditor: TimeEditor? = null
    private var pickerKind = restored?.getString("picker") ?: ""
    private var pickerState = restored?.getBundle("pickerState")
    private var pastDialog: AlertDialog? = null
    private var pastChoice = restored?.getBoolean("pastChoice") ?: false
    private var busy = false
    val dialog: AlertDialog

    init {
        val segments = LinearLayout(context)
        segments.addView(once, LinearLayout.LayoutParams(0, -2, 1f))
        segments.addView(daily, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(segments)
        dates.addView(NativeUi.button(context, "今天") { date = Logic.today(); update() }, LinearLayout.LayoutParams(0, -2, 1f))
        dates.addView(NativeUi.button(context, "明天") { date = LocalDate.now().plusDays(1).toString(); update() }, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(dates); panel.addView(pickDate); panel.addView(pickTime)
        panel.addView(preview); panel.addView(status)
        panel.addView(NativeUi.label(context, "通知关闭时不会显示提醒；未开启精确闹钟时可能延迟。", 13f).apply { setTextColor(NativeUi.secondary(context)) })
        panel.addView(NativeUi.button(context, "移除提醒") { commit("", "", "") })
        dialog = AlertDialog.Builder(context).setTitle("提醒")
            .setView(ScrollView(context).apply { addView(panel) })
            .setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        dialog.setOnDismissListener { picker?.dismiss(); pastDialog?.dismiss(); closed() }
        update()
    }

    fun show() {
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { validate() }
        when (pickerKind) { "date" -> showDate(); "time" -> showTime() }
        if (pastChoice) choosePast()
    }
    fun refreshStatus() { status.text = permissions() }
    private fun update() {
        once.isSelected = mode == "once"; daily.isSelected = mode == "daily"
        once.alpha = if (mode == "once") 1f else .55f
        daily.alpha = if (mode == "daily") 1f else .55f
        dates.visibility = if (mode == "daily") View.GONE else View.VISIBLE
        pickDate.visibility = dates.visibility
        pickDate.text = date
        pickTime.text = "时间  $at"
        preview.text = if (mode == "daily") {
            "每天 $at" + if (at <= Logic.hhmmNow()) "\n今天时间已过，保存后补提醒" else ""
        } else "${NativeUi.dayLabel(date)}  $at"
        refreshStatus()
    }
    private fun showDate() {
        val d = runCatching { LocalDate.parse(date) }.getOrDefault(LocalDate.now())
        pickerKind = "date"
        val p = DatePickerDialog(context, { _, y, m, day -> date = LocalDate.of(y, m + 1, day).toString(); update() }, d.year, d.monthValue - 1, d.dayOfMonth)
        picker = p
        p.datePicker.minDate = System.currentTimeMillis()
        p.setOnDismissListener { pickerKind = ""; picker = null }
        p.show()
        pickerState?.let { p.onRestoreInstanceState(it) }; pickerState = null
    }
    private fun showTime() {
        pickerKind = "time"
        val editor = TimeEditor(context, at, pickerState,
            { value -> at = value; update() },
            { pickerKind = ""; picker = null; timeEditor = null })
        pickerState = null
        timeEditor = editor
        picker = editor.dialog
        editor.show()
    }
    private fun validate() {
        if (busy) return
        if (mode == "once" && (Logic.parseYmd(date) == null || Logic.isPastDate(date, Logic.today()))) {
            preview.text = "请选择今天或之后的日期"; return
        }
        if (mode == "once" && date == Logic.today() && at <= Logic.hhmmNow()) { choosePast(); return }
        commit(at, mode, if (mode == "daily") "" else date)
    }
    private fun commit(time: String, repeat: String, day: String) {
        if (busy) return
        busy = true
        fun enable(view: View, enabled: Boolean) {
            view.isEnabled = enabled
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) enable(view.getChildAt(i), enabled)
        }
        enable(panel, false)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
        dialog.setCancelable(false)
        save(time, repeat, day) { success ->
            busy = false
            if (success) dialog.dismiss() else {
                enable(panel, true)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                dialog.setCancelable(true)
                preview.text = "保存失败，请重试；修改仍保留。"
            }
        }
    }
    private fun choosePast() {
        pastChoice = true
        pastDialog = AlertDialog.Builder(context).setTitle("今天的时间已过")
            .setMessage("立即提醒，还是改为明天同一时间？")
            .setNegativeButton("取消", null)
            .setNeutralButton("明天") { _, _ ->
                date = LocalDate.now().plusDays(1).toString(); update(); commit(at, "once", date)
            }
            .setPositiveButton("立即提醒") { _, _ -> commit(at, "once", Logic.today()) }
            .create().also { p -> p.setOnDismissListener { pastChoice = false; pastDialog = null }; p.show() }
    }
    fun state(): Bundle {
        return Bundle().apply {
            putString("id", id); putString("mode", mode); putString("date", date); putString("at", at)
            putString("picker", pickerKind); putBoolean("pastChoice", pastChoice)
            putBundle("pickerState", timeEditor?.state() ?: picker?.onSaveInstanceState())
        }
    }
    fun dismiss() { dialog.dismiss() }
}
