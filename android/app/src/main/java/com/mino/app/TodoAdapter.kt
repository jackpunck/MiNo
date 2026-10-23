package com.mino.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.time.LocalDate

internal object NativeUi {
    private fun color(context: Context, attribute: Int): Int {
        val values = context.obtainStyledAttributes(intArrayOf(attribute))
        return try { values.getColor(0, Color.MAGENTA) } finally { values.recycle() }
    }
    fun background(context: Context) = color(context, android.R.attr.colorBackground)
    fun surface(context: Context) = color(context, R.attr.minoSurface)
    fun accent(context: Context) = color(context, android.R.attr.colorAccent)
    fun text(context: Context) = color(context, android.R.attr.textColorPrimary)
    fun secondary(context: Context) = color(context, android.R.attr.textColorSecondary)
    fun highlight(context: Context) = color(context, R.attr.minoHighlight)
    fun dp(c: Context, value: Int) = (value * c.resources.displayMetrics.density).toInt()
    fun label(c: Context, value: String, size: Float = 16f) = TextView(c).apply {
        text = value; textSize = size; setTextColor(NativeUi.text(c))
    }
    fun button(c: Context, value: String, action: () -> Unit) = Button(c).apply {
        text = value; isAllCaps = false; minHeight = dp(c, 48); minimumWidth = dp(c, 48)
        setTextColor(accent(c)); setOnClickListener { action() }
    }
    fun panel(c: Context) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(c, 20), dp(c, 12), dp(c, 20), dp(c, 12))
    }
    fun shape(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = 18f }
    fun dayLabel(date: String): String {
        val day = runCatching { LocalDate.parse(date) }.getOrNull() ?: return date
        val today = LocalDate.now()
        return when (day) {
            today -> "今天"
            today.plusDays(1) -> "明天"
            else -> "周${"一二三四五六日"[day.dayOfWeek.value - 1]} ${date.takeLast(5)}"
        }
    }
    fun summary(t: Todo): String {
        if (t.remindAt.isNullOrBlank()) return "不提醒"
        if (Logic.isDaily(t)) return if (t.completed) "今日已完成·明天 ${t.remindAt}" else "每天 ${t.remindAt}"
        if (!t.completed && t.remindedOn.isNotBlank()) return "已提醒·待完成"
        return "${dayLabel(t.remindDate.ifBlank { t.createdDate })} ${t.remindAt}"
    }
}

/** A monochrome bell drawn in the native button; no image or font asset. */
internal class BellButton(context: Context) : Button(context) {
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = NativeUi.dp(context, 2).toFloat(); strokeCap = Paint.Cap.ROUND }
    private val bell = android.graphics.Path().apply {
        moveTo(-8f, 6f); lineTo(-6f, 3f); lineTo(-6f, -3f)
        cubicTo(-6f, -11f, 6f, -11f, 6f, -3f)
        lineTo(6f, 3f); lineTo(8f, 6f); close()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        ink.color = currentTextColor
        val unit = resources.displayMetrics.density
        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        canvas.scale(unit, unit)
        val stroke = ink.strokeWidth
        ink.strokeWidth = 1.8f
        canvas.drawPath(bell, ink)
        canvas.drawLine(-2f, 9f, 2f, 9f, ink)
        canvas.drawLine(0f, -10f, 0f, -11f, ink)
        ink.strokeWidth = stroke
        canvas.restore()
    }
}

/** Recycled rows bind callbacks only after all checkbox state has been restored. */
internal class TodoAdapter(
    private val context: Context,
    private val toggle: (Todo) -> Unit,
    private val edit: (Todo) -> Unit,
    private val reminder: (Todo) -> Unit,
    private val menu: (Todo, View) -> Unit,
) : BaseAdapter() {
    var todos: List<Todo> = emptyList()
    var highlight: String? = null
    override fun getCount() = todos.size
    override fun getItem(position: Int) = todos[position]
    override fun getItemId(position: Int) = position.toLong()
    private class Row(val root: LinearLayout, val check: CheckBox, val body: LinearLayout,
                      val title: TextView, val detail: TextView, val bell: Button, val more: Button)
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = convertView?.tag as? Row ?: run {
            val root = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = NativeUi.dp(context, 80)
                setPadding(0, NativeUi.dp(context, 4), 0, NativeUi.dp(context, 4))
            }
            val check = CheckBox(context).apply {
                buttonTintList = ColorStateList.valueOf(NativeUi.accent(context))
                minHeight = NativeUi.dp(context, 48)
            }
            root.addView(check, LinearLayout.LayoutParams(NativeUi.dp(context, 48), NativeUi.dp(context, 48)))
            val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; minimumHeight = NativeUi.dp(context, 48); gravity = Gravity.CENTER_VERTICAL }
            val title = NativeUi.label(context, "")
            val detail = NativeUi.label(context, "", 12f).apply { setTextColor(NativeUi.secondary(context)) }
            body.addView(title); body.addView(detail)
            root.addView(body, LinearLayout.LayoutParams(0, -2, 1f))
            val bell = BellButton(context).apply { contentDescription = "设置提醒"; minimumWidth = NativeUi.dp(context, 48); minimumHeight = NativeUi.dp(context, 48) }
            val more = NativeUi.button(context, "⋮") {}.apply { contentDescription = "更多操作" }
            root.addView(bell, LinearLayout.LayoutParams(NativeUi.dp(context, 48), NativeUi.dp(context, 48)))
            root.addView(more, LinearLayout.LayoutParams(NativeUi.dp(context, 48), NativeUi.dp(context, 48)))
            Row(root, check, body, title, detail, bell, more).also { root.tag = it }
        }
        val todo = getItem(position)
        row.check.setOnCheckedChangeListener(null)
        row.check.isChecked = todo.completed
        row.check.contentDescription = if (todo.completed) "标记未完成" else "标记完成"
        row.check.setOnCheckedChangeListener { _, _ -> toggle(todo) }
        val stale = !todo.completed && todo.remindAt.isNullOrBlank() && todo.createdDate.isNotBlank() && todo.createdDate < Logic.today()
        row.title.text = if (stale) "[${todo.createdDate.takeLast(5)}] ${todo.text}" else todo.text
        row.title.setTextColor(if (todo.completed) NativeUi.secondary(context) else NativeUi.text(context))
        row.title.paintFlags = if (todo.completed) row.title.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG else row.title.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        row.detail.text = NativeUi.summary(todo)
        row.body.setOnClickListener { edit(todo) }
        row.bell.text = ""
        row.bell.setTextColor(if (todo.remindAt.isNullOrBlank()) NativeUi.secondary(context) else NativeUi.accent(context))
        row.bell.setOnClickListener { reminder(todo) }
        row.more.setOnClickListener { menu(todo, row.more) }
        row.root.background = NativeUi.shape(if (todo.id == highlight) NativeUi.highlight(context) else NativeUi.background(context))
        return row.root
    }
}
