package com.mino.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/** Native platform UI. Store snapshots remain authoritative; drafts live only in views. */
class MainActivity : Activity() {
    companion object {
        // One process-wide queue also serializes operations across Activity recreation.
        private val io = Executors.newSingleThreadExecutor { task -> Thread(task, "mino-ui-io") }
        private const val CHANGED = "com.mino.app.TODOS_CHANGED"
        private const val NOTIFICATIONS_REQUEST = 1001
    }
    private var themeMode = ThemeMode.NIGHT
    private lateinit var store: Store
    private lateinit var backupController: BackupController
    private lateinit var updateController: UpdateController
    private lateinit var input: EditText
    private lateinit var list: ListView
    private lateinit var adapter: TodoAdapter
    private lateinit var progress: TextView
    private lateinit var bar: ProgressBar
    private lateinit var clear: Button
    private var alive = true
    private var receiverRegistered = false
    private var highlight: String? = null
    private var scrollToHighlight = false
    private var reminderEditor: ReminderEditor? = null
    private var textDialog: AlertDialog? = null
    private var textEditor: EditText? = null
    private var editingId: String? = null
    private var permissionDialog: AlertDialog? = null
    private var permissionStatus: TextView? = null
    private var exactGuide: AlertDialog? = null
    private var pendingExactGuide = false
    private var notificationRequestPending = false
    private var deferredNotificationRequest = false
    private var resumed = false
    private var restoredReminder: Bundle? = null
    private var restoredText: Bundle? = null
    private var restorePermissions = false
    private var savedScroll: Bundle? = null
    private var adding = false
    private var pendingAddId: String? = null
    private var pendingAddDraft: String? = null
    private var restoredAdd = false
    private val changedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { if (intent?.action == CHANGED) refresh() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        themeMode = ThemePreference.apply(this)
        super.onCreate(savedInstanceState)
        store = Store(applicationContext)
        backupController = BackupController(this) { recreate() }
        updateController = UpdateController(this) { resumed }
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        highlight = savedInstanceState?.getString("highlight") ?: intent.getStringExtra(Reminders.EXTRA_ID)
        scrollToHighlight = savedInstanceState?.getBoolean("scrollHighlight") ?: (highlight != null)
        restoredReminder = savedInstanceState?.getBundle("reminder")
        restoredText = savedInstanceState?.getBundle("edit")
        restorePermissions = savedInstanceState?.getBoolean("permissions") ?: false
        pendingExactGuide = savedInstanceState?.getBoolean("exactGuide") ?: false
        notificationRequestPending = savedInstanceState?.getBoolean("notificationRequest") ?: false
        deferredNotificationRequest = savedInstanceState?.getBoolean("deferredNotificationRequest") ?: false
        savedScroll = savedInstanceState?.getBundle("scroll")
        pendingAddId = savedInstanceState?.getString("addingId")
        pendingAddDraft = savedInstanceState?.getString("addingDraft")
        restoredAdd = pendingAddId != null
        adding = restoredAdd
        buildUi()
        input.setText(savedInstanceState?.getString("draft") ?: "")
        input.setSelection(input.length())
        ContextCompat.registerReceiver(this, changedReceiver, IntentFilter(CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    private fun buildUi() {
        val root = NativeUi.panel(this).apply {
            setBackgroundColor(NativeUi.background(this@MainActivity))
            isFocusableInTouchMode = true
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val edges = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                v.setPadding(dp(20) + edges.left, dp(12) + edges.top, dp(20) + edges.right, dp(12) + edges.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(dp(20) + insets.systemWindowInsetLeft, dp(12) + insets.systemWindowInsetTop, dp(20) + insets.systemWindowInsetRight, dp(12) + insets.systemWindowInsetBottom)
            }
            insets
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(NativeUi.label(this, "MiNo", 30f), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(NativeUi.button(this, "⋮") {}.apply { contentDescription = "外观与提醒设置"; setOnClickListener { showSettingsMenu(this) } }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(header)
        root.addView(NativeUi.label(this, "今天，慢慢来。", 14f).apply { setTextColor(NativeUi.secondary(this@MainActivity)); setPadding(0, 0, 0, dp(20)) })
        val composer = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; background = NativeUi.shape(NativeUi.surface(this@MainActivity)) }
        input = EditText(this).apply {
            hint = "添加一个待办…"; setTextColor(NativeUi.text(this@MainActivity)); setHintTextColor(NativeUi.secondary(this@MainActivity))
            textSize = 16f; isSingleLine = true; imeOptions = EditorInfo.IME_ACTION_DONE
            minHeight = dp(56); setPadding(dp(12), 0, dp(8), 0)
            setOnEditorActionListener { _, action, event ->
                if (action == EditorInfo.IME_ACTION_DONE || (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP)) {
                    addTodo(); true
                } else event?.keyCode == KeyEvent.KEYCODE_ENTER
            }
        }
        composer.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        composer.addView(NativeUi.button(this, "+") { addTodo() }.apply { contentDescription = "添加待办" }, LinearLayout.LayoutParams(dp(48), dp(56)))
        root.addView(composer)
        val counts = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        progress = NativeUi.label(this, "正在读取…", 13f).apply { setTextColor(NativeUi.secondary(this@MainActivity)) }
        counts.addView(progress, LinearLayout.LayoutParams(0, dp(48), 1f))
        clear = NativeUi.button(this, "清除已完成") {
            AlertDialog.Builder(this).setTitle("清除已完成？").setMessage("未完成的任务会保留。")
                .setNegativeButton("取消", null).setPositiveButton("清除") { _, _ ->
                    mutate { data -> data.copy(todos = data.todos.filterNot { it.completed }) }
                }.show()
        }
        counts.addView(clear)
        root.addView(counts)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            progressTintList = android.content.res.ColorStateList.valueOf(NativeUi.accent(this@MainActivity))
        }
        root.addView(bar, LinearLayout.LayoutParams(-1, dp(4)))
        val container = FrameLayout(this)
        list = ListView(this).apply {
            divider = null; cacheColorHint = NativeUi.background(this@MainActivity)
            descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        adapter = TodoAdapter(this, ::toggle, ::editTodo, ::editReminder, ::showMenu)
        list.adapter = adapter
        val empty = NativeUi.label(this, "今天想做些什么？\n从一件小事开始。", 18f).apply {
            gravity = Gravity.CENTER; setTextColor(NativeUi.secondary(this@MainActivity))
        }
        container.addView(list, FrameLayout.LayoutParams(-1, -1))
        container.addView(empty, FrameLayout.LayoutParams(-1, -1))
        list.emptyView = empty
        root.addView(container, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        root.requestFocus(); root.requestApplyInsets()
    }
    private fun dp(value: Int) = NativeUi.dp(this, value)

    override fun onResume() {
        super.onResume()
        resumed = true
        refresh()
        reminderEditor?.refreshStatus()
        permissionStatus?.text = permissionSummary()
        if (deferredNotificationRequest) requestReminderPermission()
        maybeShowExactGuide()
    }
    override fun onPause() {
        resumed = false
        super.onPause()
    }
    private fun refresh() = submit(null)
    private fun mutate(change: (AppData) -> AppData) = submit(change)
    private fun submit(change: ((AppData) -> AppData)?, complete: (Boolean) -> Unit = {}) {
        val app = applicationContext
        val dataStore = store
        io.execute {
            var recovery: String? = null
            try {
                // Registration failure must abort before any data is written.
                recovery = if (change != null) ReminderRecoveryJob.schedule(app) else null
                val data = if (change == null) dataStore.read() else dataStore.transact(change)
                // Context-only scheduler re-reads under its own lock; never schedule a stale snapshot.
                val scheduleFailed = runCatching { Reminders.reschedule(app) }.isFailure
                if (!scheduleFailed && recovery != null) {
                    // Keep protection until all registered operations have reconciled.
                    // Cleanup failure must not report an already committed save as failed.
                    runCatching { ReminderRecoveryJob.complete(app, recovery) }
                } else if (scheduleFailed && change == null) {
                    runCatching { ReminderRecoveryJob.schedule(app).also { ReminderRecoveryJob.abandon(it) } }
                }
                recovery?.let { ReminderRecoveryJob.abandon(it) }
                runOnUiThread {
                    if (alive && !isFinishing && !isDestroyed) {
                        render(data)
                        complete(true)
                        if (scheduleFailed) toast(if (change != null)
                            "数据已保存，但提醒调度失败；已登记系统修复任务，系统允许后台运行时会重试，提醒可能延迟"
                            else "提醒调度失败，请检查提醒权限与后台限制")
                    }
                }
            } catch (_: Exception) {
                recovery?.let { ReminderRecoveryJob.abandon(it) }
                runOnUiThread { if (alive && !isFinishing && !isDestroyed) {
                    complete(false)
                    if (change == null && restoredAdd) { adding = false; restoredAdd = false }
                    toast(if (change == null) "读取失败，请重试；输入内容仍保留" else "未能保存，请重试；输入内容仍保留")
                } }
            }
        }
    }
    private fun render(data: AppData) {
        if (restoredAdd) {
            if (data.todos.any { it.id == pendingAddId } && input.text.toString() == pendingAddDraft) input.setText("")
            adding = false; restoredAdd = false; pendingAddId = null; pendingAddDraft = null
        }
        val first = list.firstVisiblePosition
        val top = list.getChildAt(0)?.top ?: 0
        adapter.todos = data.todos; adapter.highlight = highlight; adapter.notifyDataSetChanged()
        val complete = data.todos.count { it.completed }
        progress.text = "$complete / ${data.todos.size} 已完成"
        clear.isEnabled = complete > 0
        bar.max = data.todos.size.coerceAtLeast(1); bar.progress = complete
        if (scrollToHighlight) {
            val index = data.todos.indexOfFirst { it.id == highlight }
            if (index >= 0) {
                list.setSelection(index); scrollToHighlight = false
                highlight?.let { Notifications.cancel(this, it) }
            }
        } else {
            val saved = savedScroll
            list.setSelectionFromTop(saved?.getInt("position") ?: first, saved?.getInt("top") ?: top)
            savedScroll = null
        }
        restoredText?.let { state ->
            restoredText = null
            data.todos.firstOrNull { it.id == state.getString("id") }?.let { editTodo(it, state.getString("text")) }
        }
        restoredReminder?.let { state ->
            restoredReminder = null
            data.todos.firstOrNull { it.id == state.getString("id") }?.let { editReminder(it, state) }
        }
        if (restorePermissions) { restorePermissions = false; showPermissions() }
    }
    private fun addTodo() {
        val text = input.text.toString().trim()
        if (text.isBlank() || adding) return
        val draft = input.text.toString()
        adding = true
        val id = store.newId()
        pendingAddId = id; pendingAddDraft = draft
        submit({ data -> data.copy(todos = data.todos + Todo(id = id, text = text,
            priority = 1, createdDate = Logic.today(), createdAt = Logic.nowMs(),
            order = Logic.nextOrder(data.todos), font = data.settings.fontFamily, fontId = data.settings.fontId)) }) { success ->
            adding = false
            pendingAddId = null; pendingAddDraft = null
            if (success && input.text.toString() == draft) input.setText("")
        }
    }
    private fun toggle(todo: Todo) {
        mutate { data -> data.copy(todos = data.todos.map { t ->
            if (t.id != todo.id) t else t.copy(completed = !t.completed, completedAt = if (!t.completed) Logic.nowMs() else null)
        }) }
    }
    private fun editTodo(todo: Todo) = editTodo(todo, null)
    private fun editTodo(todo: Todo, draft: String?) {
        if (textDialog != null) return
        editingId = todo.id
        val field = EditText(this).apply {
            setText(draft ?: todo.text); setSelection(length()); minHeight = dp(56)
            setTextColor(NativeUi.text(this@MainActivity)); setPadding(dp(20), dp(12), dp(20), dp(12))
            // Enter inserts a line; only the explicit Save action commits.
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        textEditor = field
        textDialog = AlertDialog.Builder(this).setTitle("编辑待办").setView(field)
            .setNegativeButton("取消", null).setPositiveButton("保存", null).create().also { dialog ->
                dialog.setOnDismissListener { textDialog = null; textEditor = null; editingId = null }
                dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
                dialog.show()
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val value = field.text.toString().trim()
                    if (value.isBlank()) { dialog.dismiss(); return@setOnClickListener }
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                    field.isEnabled = false
                    dialog.setCancelable(false)
                    submit({ data -> data.copy(todos = data.todos.map { if (it.id == todo.id) it.copy(text = value) else it }) }) { success ->
                        if (success) dialog.dismiss() else {
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                            field.isEnabled = true
                            field.error = "保存失败，请重试"
                            dialog.setCancelable(true)
                        }
                    }
                }
            }
    }
    private fun editReminder(todo: Todo) = editReminder(todo, null)
    private fun editReminder(todo: Todo, restored: Bundle?) {
        if (reminderEditor != null) return
        reminderEditor = ReminderEditor(this, todo.id, todo, restored, ::permissionSummary, ::showPermissions,
            { at, mode, date, complete ->
                val today = Logic.today()
                submit({ data ->
                    Logic.setTodoReminder(data, todo.id, at, mode, date, today)
                }) { success ->
                    complete(success)
                    if (success && at.isNotBlank()) {
                        pendingExactGuide = true
                        requestReminderPermission()
                    }
                }
            }, { reminderEditor = null })
        reminderEditor?.show()
    }
    private fun showMenu(todo: Todo, anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("编辑").setOnMenuItemClickListener { editTodo(todo); true }
            menu.add("提醒").setOnMenuItemClickListener { editReminder(todo); true }
            menu.add("删除").setOnMenuItemClickListener {
                AlertDialog.Builder(this@MainActivity).setTitle("删除这条待办？")
                    .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                        mutate { data -> data.copy(todos = data.todos.filterNot { it.id == todo.id }) }
                    }.show(); true
            }
            show()
        }
    }
    private fun showSettingsMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(1, 1, 0, "日间模式").apply { isCheckable = true; isChecked = themeMode == ThemeMode.DAY }
            menu.add(1, 2, 1, "夜晚模式").apply { isCheckable = true; isChecked = themeMode == ThemeMode.NIGHT }
            menu.setGroupCheckable(1, true, true)
            menu.add(0, 3, 2, "提醒权限设置")
            menu.add(0, 4, 3, "导出数据备份")
            menu.add(0, 5, 4, "导入数据备份")
            menu.add(0, 6, 5, "检查更新")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> changeTheme(ThemeMode.DAY)
                    2 -> changeTheme(ThemeMode.NIGHT)
                    3 -> showPermissions()
                    4 -> backupController.export()
                    5 -> backupController.importBackup()
                    6 -> updateController.show()
                }
                true
            }
            show()
        }
    }
    private fun changeTheme(selected: ThemeMode) {
        if (!themeMode.requiresRecreation(selected)) return
        ThemePreference.write(this, selected)
        recreate()
    }
    private fun permissionSummary(): String = ReminderReadiness.summary(this)
    private fun showPermissions() {
        if (permissionDialog != null) return
        val panel = NativeUi.panel(this)
        permissionStatus = NativeUi.label(this, permissionSummary(), 15f)
        panel.addView(permissionStatus)
        panel.addView(NativeUi.label(this, ReminderReadiness.APPROXIMATE + "\n\n" + ReminderReadiness.BACKGROUND, 14f))
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            panel.addView(NativeUi.button(this, "允许通知") { requestReminderPermission() })
        panel.addView(NativeUi.button(this, "系统通知设置") {
            openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        })
        panel.addView(NativeUi.button(this, "精确闹钟设置") {
            if (Build.VERSION.SDK_INT >= 31) openSettings(ReminderReadiness.exactSettings(this))
            else toast("此系统无需单独授予精确闹钟权限")
        })
        panel.addView(NativeUi.button(this, "电池优化设置") { openSettings(ReminderReadiness.batterySettings()) })
        panel.addView(NativeUi.button(this, "应用信息 / 后台与自启动") { openSettings(ReminderReadiness.appSettings(this)) })
        val scroll = ScrollView(this).apply { addView(panel) }
        permissionDialog = AlertDialog.Builder(this).setTitle("提醒权限").setView(scroll).setPositiveButton("完成", null)
            .create().also { dialog -> dialog.setOnDismissListener { permissionDialog = null; permissionStatus = null; maybeShowExactGuide() }; dialog.show() }
    }
    private fun openSettings(intent: Intent) {
        try { startActivity(intent) } catch (_: Exception) { toast("无法打开系统设置，请在应用信息中设置") }
    }
    private fun requestReminderPermission() {
        if (!resumed) { deferredNotificationRequest = true; return }
        deferredNotificationRequest = false
        if (notificationRequestPending) return
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationRequestPending = true
            try {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATIONS_REQUEST)
            } catch (_: Exception) {
                notificationRequestPending = false
                toast("无法请求通知权限，请在提醒权限设置中开启")
                maybeShowExactGuide()
            }
        } else maybeShowExactGuide()
    }
    private fun maybeShowExactGuide() {
        if (!pendingExactGuide || notificationRequestPending || deferredNotificationRequest || !resumed || !alive || isFinishing || isDestroyed || exactGuide != null || permissionDialog != null || restorePermissions) return
        if (ReminderReadiness.exactAllowed(this)) { pendingExactGuide = false; return }
        exactGuide = AlertDialog.Builder(this).setTitle("让提醒更准时")
            .setMessage("提醒已保存。" + ReminderReadiness.APPROXIMATE + "\n\n是否前往系统设置允许精确闹钟？")
            .setPositiveButton("允许精确提醒") { _, _ ->
                pendingExactGuide = false
                if (Build.VERSION.SDK_INT >= 31) openSettings(ReminderReadiness.exactSettings(this))
            }
            .setNegativeButton("保留近似提醒") { _, _ -> pendingExactGuide = false }
            .setOnCancelListener { pendingExactGuide = false }
            .create().also { dialog ->
                dialog.setOnDismissListener { exactGuide = null }
                dialog.show()
            }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATIONS_REQUEST) {
            notificationRequestPending = false
            reminderEditor?.refreshStatus(); permissionStatus?.text = permissionSummary()
            if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) toast("提醒已保存；通知关闭时无法显示，可在提醒权限中开启")
            refresh()
            maybeShowExactGuide()
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        intent.getStringExtra(Reminders.EXTRA_ID)?.let { id -> highlight = id; scrollToHighlight = true; refresh() }
    }
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        backupController.onActivityResult(requestCode, resultCode, data)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("draft", input.text.toString())
        outState.putString("addingId", pendingAddId); outState.putString("addingDraft", pendingAddDraft)
        outState.putString("highlight", highlight); outState.putBoolean("scrollHighlight", scrollToHighlight)
        outState.putBundle("scroll", savedScroll ?: Bundle().apply { putInt("position", list.firstVisiblePosition); putInt("top", list.getChildAt(0)?.top ?: 0) })
        // A second recreation can precede the async read that opens restored dialogs.
        outState.putBundle("reminder", reminderEditor?.state() ?: restoredReminder)
        outState.putBundle("edit", editingId?.let { id ->
            Bundle().apply { putString("id", id); putString("text", textEditor?.text?.toString()) }
        } ?: restoredText)
        outState.putBoolean("permissions", permissionDialog != null || restorePermissions)
        outState.putBoolean("exactGuide", pendingExactGuide)
        outState.putBoolean("notificationRequest", notificationRequestPending)
        outState.putBoolean("deferredNotificationRequest", deferredNotificationRequest)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        alive = false
        updateController.destroy()
        if (receiverRegistered) unregisterReceiver(changedReceiver)
        reminderEditor?.dismiss(); textDialog?.dismiss(); permissionDialog?.dismiss(); exactGuide?.dismiss()
        super.onDestroy()
    }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
}
