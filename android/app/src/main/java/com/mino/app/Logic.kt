package com.mino.app

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.time.format.SignStyle
import java.time.temporal.ChronoField
import java.util.Locale

/**
 * 业务逻辑 —— 桌面端 `state.rs` + `reminder.rs` 的纯函数移植。
 *
 * 这个文件是整个 Android 工程里**唯一能在 CI 上被真正验证**的部分：
 * 本机没有 JDK/SDK，调度与通知层只能靠实机，而这里的每一条规则都有 JVM 单测。
 * 所以改动时优先保住「函数是纯的」这件事 —— 时间一律从参数进来，不在内部读钟
 * （`today()` / `nowMs()` / `hhmmNow()` 是例外，它们只被外层调用一次）。
 *
 * 移植时逐条对照过原实现，几个**不能想当然**的地方写在各自函数上方。
 */
object Logic {

    /** 归档上限，超出丢最旧的。与 `state.rs` 的 `ARCHIVE_LIMIT` 一致。 */
    const val ARCHIVE_LIMIT = 500

    /**
     * 解析 `YYYY-M-D`，归一化成定长的 `YYYY-MM-DD`。
     *
     * ⚠️ **必须用 STRICT 且用 `ChronoField.YEAR`，不能用 `DateTimeFormatter.ofPattern("yyyy-M-d")`。**
     * 后者在默认的 SMART 解析下会把 `2026-02-30` 悄悄修正成 `2026-02-28` ——
     * 用户输入一个不存在的日期，我们却存下一个别的日期。桌面端（chrono）是拒绝的。
     *
     * 归一化后**必须恰好 10 个字符**：`isDue` 判「是不是它那天」用的是字符串相等，
     * 而 `today` 永远是 10 字符，5 位年份的日期会永远不匹配、静默地不响。
     * 与 Rust 一样，`"26-10-17"` 会被补成 `"0026-10-17"` 并通过长度检查，
     * 随后由 `isPastDate` 拒绝 —— 这是刻意的，两边行为必须一致。
     */
    private val YMD: DateTimeFormatter = DateTimeFormatterBuilder()
        .appendValue(ChronoField.YEAR, 1, 10, SignStyle.NORMAL)
        .appendLiteral('-')
        .appendValue(ChronoField.MONTH_OF_YEAR, 1, 2, SignStyle.NOT_NEGATIVE)
        .appendLiteral('-')
        .appendValue(ChronoField.DAY_OF_MONTH, 1, 2, SignStyle.NOT_NEGATIVE)
        .toFormatter(Locale.ROOT)
        .withResolverStyle(ResolverStyle.STRICT)

    // -----------------------------------------------------------------------
    // 时钟（只在外层调用一次，不要塞进纯函数里）
    // -----------------------------------------------------------------------

    fun today(): String = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

    fun nowMs(): Long = System.currentTimeMillis()

    fun hhmmNow(): String = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT))

    // -----------------------------------------------------------------------
    // 提醒的格式与时序
    // -----------------------------------------------------------------------

    /**
     * 校验并补零成 `"HH:MM"`，非法返回 null。
     *
     * 归一化不是洁癖：`"9:00"` 与 `"10:00"` 做字典序比较会得出「10 点还没到 9 点」，
     * 那条提醒就永远不会响。
     *
     * 只按**第一个**冒号切分，所以 `"09:00:00"` 的后半段是 `"00:00"`、不是纯数字 → 拒绝。
     */
    fun parseHhmm(raw: String): String? {
        val s = raw.trim()
        val i = s.indexOf(':')
        if (i < 0) return null

        val h = s.substring(0, i)
        val m = s.substring(i + 1)
        if (h.isEmpty() || m.isEmpty()) return null
        if (!h.all { it in '0'..'9' } || !m.all { it in '0'..'9' }) return null

        // 溢出的数字串在这里返回 null，与 Rust 的 u32::parse 失败一致
        val hh = h.toIntOrNull() ?: return null
        val mm = m.toIntOrNull() ?: return null
        if (hh > 23 || mm > 59) return null

        return String.format(Locale.ROOT, "%02d:%02d", hh, mm)
    }

    fun parseYmd(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        return try {
            val d = LocalDate.parse(s, YMD)
            val out = String.format(Locale.ROOT, "%04d-%02d-%02d", d.year, d.monthValue, d.dayOfMonth)
            if (out.length == 10) out else null
        } catch (_: DateTimeParseException) {
            null
        } catch (_: java.time.DateTimeException) {
            null
        }
    }

    /** 严格小于；今天不算过去。 */
    fun isPastDate(date: String, today: String): Boolean = date < today

    /**
     * 未知模式**降级成 once**。
     * 方向是刻意的：一次性的打扰远小于天天误报。
     */
    fun modeOrOnce(mode: String): String =
        if (mode.equals("daily", ignoreCase = true)) "daily" else "once"

    /**
     * 是不是每日提醒 —— **模式和时间必须同时成立**。
     * 手改文件造出来的「daily 但没时间」不算，不给它免归档的待遇。
     */
    fun isDaily(t: Todo): Boolean =
        t.remindMode.equals("daily", ignoreCase = true) && !t.remindAt.isNullOrEmpty()

    /**
     * 到点判定。五条全满足才算：
     * 未完成 / 有时间 / 今天还没弹过 / `hhmm >= at` / （每日，或 once 的日期就是今天）。
     *
     * `hhmm >= at` 而不是严格相等，就是「补提醒」：合盖一整天之后打开，当天的
     * 提醒照样会响。**刻意不设迟到上限** —— 一条还在列表里的任务，晚上 11 点
     * 告诉你「今天这事还没做」仍然是对的。
     *
     * `once` 过了那一天就不再补：跨天补一次一次性提醒会让人莫名其妙。
     */
    fun isDue(t: Todo, today: String, hhmm: String): Boolean {
        if (t.completed) return false
        val at = t.remindAt ?: return false
        if (at.isEmpty() || t.remindedOn == today || hhmm < at) return false
        if (isDaily(t)) return true
        val day = if (t.remindDate.isEmpty()) t.createdDate else t.remindDate
        return day == today
    }

    // -----------------------------------------------------------------------
    // 数据变更
    // -----------------------------------------------------------------------

    /**
     * 未完成在前 → `order` 升序 → `createdAt` 降序。
     *
     * `sortedWith` 是稳定排序，与 Rust 的 `sort_by` 一致。
     */
    fun sortTodos(todos: List<Todo>): List<Todo> =
        todos.sortedWith(
            compareBy<Todo> { it.completed }
                .thenBy { it.order }
                .thenByDescending { it.createdAt },
        )

    /** 新任务排到最前：取全局最小 order 再减一（空列表时是 -1，与桌面端一致）。 */
    fun nextOrder(todos: List<Todo>): Long = (todos.minOfOrNull { it.order } ?: 0L) - 1L

    /**
     * 跨日结算。没有变化时返回 null（与 `state.rs` 不同：那边返回归档条数，
     * 于是「只重置了每日任务、没有任何任务归档」时调用方不会落盘，`lastCheckDate`
     * 一直滞后。这里返回「有没有变化」，从根上避免那个漂移）。
     *
     * 首次运行（`lastCheckDate` 为空）只记日期、不归档 —— 否则会把用户的全部历史
     * 已完成任务误判成「昨天的」。
     */
    fun rollover(data: AppData, today: String, now: Long): AppData? {
        if (data.settings.lastCheckDate == today) return null
        val firstRun = data.settings.lastCheckDate.isEmpty()

        val keep = ArrayList<Todo>(data.todos.size)
        val archived = ArrayList<Todo>()

        if (firstRun) {
            keep.addAll(data.todos)
        } else {
            for (t in data.todos) {
                when {
                    !t.completed -> keep.add(t)

                    // 每日提醒任务：不归档，重置成新的一天。
                    //
                    // **order 和 createdAt 刻意不动** —— 用户拖出来的顺序是明确意图，
                    // 每天悄悄按「最新」重排等于跟他对抗。
                    // **createdDate 必须改成今天**，否则前端会按 todayKey 给它挂上
                    // 一个 [MM-DD] 前缀，看起来像一条拖了很久的欠账。
                    isDaily(t) -> keep.add(
                        t.copy(completed = false, completedAt = null, createdDate = today),
                    )

                    else -> archived.add(t.copy(archivedAt = now))
                }
            }
        }

        val all = data.archives + archived
        val trimmed = if (all.size > ARCHIVE_LIMIT) all.subList(all.size - ARCHIVE_LIMIT, all.size).toList() else all

        return data.copy(
            settings = data.settings.copy(lastCheckDate = today),
            todos = keep,
            archives = trimmed,
        )
    }

    /** 只碰四个提醒字段；每次设置都清 `remindedOn`（换了时间就是重新武装）。 */
    fun setTodoReminder(
        data: AppData,
        id: String,
        at: String,
        mode: String,
        date: String,
        today: String,
    ): AppData {
        val i = data.todos.indexOfFirst { it.id == id }
        if (i < 0) return data

        val cur = data.todos[i]
        val next = if (at.isEmpty() || mode.isEmpty()) {
            cur.copy(remindAt = null, remindMode = "", remindDate = "", remindedOn = "")
        } else {
            cur.copy(
                remindAt = at,
                remindMode = mode,
                remindDate = when {
                    mode == "daily" -> ""
                    date.isEmpty() -> today
                    else -> date
                },
                remindedOn = "",
            )
        }

        return data.copy(todos = data.todos.toMutableList().also { it[i] = next })
    }

    /**
     * 把一批任务标记成已完成，返回**实际改动的条数**。
     * 已完成的不算、不存在的 id 不算 —— 幂等，调用方靠 0 判断「什么都没变」。
     * 所有条目共用一个时间戳（同一批处理，不该有先后）。
     */
    fun completeTodos(data: AppData, ids: Set<String>, now: Long): Pair<AppData, Int> {
        if (ids.isEmpty()) return data to 0

        var changed = 0
        val todos = data.todos.map { t ->
            if (t.completed || t.id !in ids) {
                t
            } else {
                changed++
                t.copy(completed = true, completedAt = now)
            }
        }
        return (if (changed == 0) data else data.copy(todos = todos)) to changed
    }
}
