package com.mino.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 业务逻辑回归测试。
 *
 * 每条用例都对应桌面端 `state.rs` / `reminder.rs` 里已有的那条行为 ——
 * 手机端是**第二份实现**，语义一旦分叉，用户看到的就是「同一条任务在电脑上
 * 会响、在手机上不会」这种没法解释的差异。纯函数能验的部分全在这里。
 */
class LogicTest {

    private val today = "2026-10-18"

    // ---------------------------------------------------------------------
    // 时刻与日期
    // ---------------------------------------------------------------------

    @Test
    fun hhmm_is_always_padded_to_two_digits() {
        assertEquals("09:00", Logic.parseHhmm("9:00"))
        assertEquals("09:05", Logic.parseHhmm("9:5"))
        assertEquals("23:59", Logic.parseHhmm(" 23:59 "))
        assertEquals("00:00", Logic.parseHhmm("0:0"))
    }

    @Test
    fun hhmm_rejects_everything_else() {
        // 归一化不是洁癖：不补零的话 "9:00" 和 "10:00" 做字典序比较
        // 会得出「10 点还没到 9 点」，那条提醒永远不响。
        val bad = listOf("", "9", "24:00", "12:60", "09:00:00", "+9:00", "-1:00", "9点", "09：00")
        for (s in bad) {
            assertNull("$s 不该被接受", Logic.parseHhmm(s))
        }
    }

    @Test
    fun ymd_normalizes_and_rejects_impossible_dates() {
        assertEquals("2026-01-05", Logic.parseYmd("2026-1-5"))
        assertEquals("2026-10-18", Logic.parseYmd(" 2026-10-18 "))
        // STRICT 解析：不存在的日期必须拒绝，不能被静默修正成月末
        assertNull(Logic.parseYmd("2026-02-30"))
        assertNull(Logic.parseYmd("2026-13-01"))
        assertNull(Logic.parseYmd("2026/01/05"))
        assertNull(Logic.parseYmd(""))
    }

    @Test
    fun ymd_rejects_anything_that_is_not_exactly_ten_chars() {
        // isDue 判「是不是它那天」用的是字符串相等，而 today 永远是 10 字符。
        // 5 位年份归一化后是 11 字符，必须在这里挡掉，否则它会静默地永不匹配。
        assertNull(Logic.parseYmd("10000-01-01"))
    }

    @Test
    fun ymd_pads_a_two_digit_year_into_the_past() {
        // 与桌面端（chrono 的 %Y）行为一致：26 被补成 0026，
        // 长度检查放行，随后由 isPastDate 拒绝。
        assertEquals("0026-10-17", Logic.parseYmd("26-10-17"))
    }

    @Test
    fun past_date_is_strictly_less_than_today() {
        assertTrue(Logic.isPastDate("2026-10-17", today))
        assertFalse(Logic.isPastDate(today, today))
        assertFalse(Logic.isPastDate("2026-10-19", today))
    }

    // ---------------------------------------------------------------------
    // 提醒模式
    // ---------------------------------------------------------------------

    @Test
    fun unknown_mode_degrades_to_once() {
        assertEquals("daily", Logic.modeOrOnce("daily"))
        assertEquals("daily", Logic.modeOrOnce("DAILY"))
        assertEquals("once", Logic.modeOrOnce(""))
        assertEquals("once", Logic.modeOrOnce("weekly"))
        assertEquals("once", Logic.modeOrOnce("once"))
    }

    @Test
    fun daily_requires_both_the_mode_and_a_time() {
        assertTrue(Logic.isDaily(Todo(remindMode = "daily", remindAt = "09:00")))
        // 手改文件造出来的「daily 但没时间」不算每日 —— 不给它免归档的待遇
        assertFalse(Logic.isDaily(Todo(remindMode = "daily", remindAt = null)))
        assertFalse(Logic.isDaily(Todo(remindMode = "daily", remindAt = "")))
        assertFalse(Logic.isDaily(Todo(remindMode = "once", remindAt = "09:00")))
    }

    // ---------------------------------------------------------------------
    // 到点判定
    // ---------------------------------------------------------------------

    private fun once(at: String = "09:00", date: String = today, reminded: String = "") =
        Todo(id = "t", text = "x", remindAt = at, remindMode = "once", remindDate = date, remindedOn = reminded)

    private fun daily(at: String = "09:00", reminded: String = "") =
        Todo(id = "t", text = "x", remindAt = at, remindMode = "daily", remindDate = "", remindedOn = reminded)

    @Test
    fun due_exactly_at_the_minute() {
        assertTrue(Logic.isDue(once(), today, "09:00"))
    }

    @Test
    fun late_is_still_due_that_is_the_catch_up_semantics() {
        assertTrue(Logic.isDue(once(), today, "23:59"))
    }

    @Test
    fun not_due_one_minute_early() {
        assertFalse(Logic.isDue(once(), today, "08:59"))
    }

    @Test
    fun already_fired_today_is_not_due() {
        assertFalse(Logic.isDue(once(reminded = today), today, "10:00"))
    }

    @Test
    fun completed_is_never_due() {
        assertFalse(Logic.isDue(once().copy(completed = true), today, "10:00"))
    }

    @Test
    fun daily_fires_every_day() {
        assertTrue(Logic.isDue(daily(), "2026-10-19", "09:00"))
        assertTrue(Logic.isDue(daily(), "2026-12-31", "23:59"))
    }

    @Test
    fun once_does_not_catch_up_after_its_day() {
        // 跨天补一次一次性提醒会让人莫名其妙
        assertFalse(Logic.isDue(once(date = "2026-10-17"), today, "10:00"))
    }

    @Test
    fun once_falls_back_to_created_date_when_no_date_given() {
        val t = once(date = "").copy(createdDate = today)
        assertTrue(Logic.isDue(t, today, "23:59"))
    }

    @Test
    fun no_time_means_never_due() {
        assertFalse(Logic.isDue(Todo(id = "t", text = "x", remindAt = null), today, "23:59"))
        assertFalse(Logic.isDue(Todo(id = "t", text = "x", remindAt = ""), today, "23:59"))
    }

    // ---------------------------------------------------------------------
    // 排序
    // ---------------------------------------------------------------------

    @Test
    fun uncompleted_first_then_order_then_newest() {
        val a = Todo(id = "a", text = "a", order = 0, createdAt = 1)
        val b = Todo(id = "b", text = "b", order = 0, createdAt = 2)
        val c = Todo(id = "c", text = "c", order = -1, createdAt = 0)
        val d = Todo(id = "d", text = "d", completed = true, order = -9, createdAt = 9)

        val sorted = Logic.sortTodos(listOf(a, b, c, d))
        assertEquals(listOf("c", "b", "a", "d"), sorted.map { it.id })
    }

    @Test
    fun all_zero_order_falls_back_to_newest_first() {
        val a = Todo(id = "a", text = "a", createdAt = 1)
        val b = Todo(id = "b", text = "b", createdAt = 2)
        assertEquals(listOf("b", "a"), Logic.sortTodos(listOf(a, b)).map { it.id })
    }

    @Test
    fun next_order_goes_below_everything() {
        assertEquals(-1L, Logic.nextOrder(emptyList()))
        assertEquals(-4L, Logic.nextOrder(listOf(Todo(id = "a", text = "a", order = -3))))
    }

    // ---------------------------------------------------------------------
    // 跨日结算
    // ---------------------------------------------------------------------

    @Test
    fun first_run_only_stamps_the_date_and_never_archives() {
        // 否则升级/恢复数据时会把用户的全部历史已完成任务一次性扫进归档
        val data = AppData(todos = listOf(Todo(id = "a", text = "a", completed = true)))
        val next = Logic.rollover(data, today, 1000)!!

        assertEquals(1, next.todos.size)
        assertTrue(next.archives.isEmpty())
        assertEquals(today, next.settings.lastCheckDate)
    }

    @Test
    fun same_day_is_a_no_op() {
        val data = AppData(settings = Settings(lastCheckDate = today))
        assertNull(Logic.rollover(data, today, 1000))
    }

    @Test
    fun completed_once_todos_are_archived() {
        val data = AppData(
            settings = Settings(lastCheckDate = "2026-10-17"),
            todos = listOf(Todo(id = "a", text = "a", completed = true)),
        )
        val next = Logic.rollover(data, today, 1234)!!

        assertTrue(next.todos.isEmpty())
        assertEquals(1, next.archives.size)
        assertEquals(1234L, next.archives[0].archivedAt)
    }

    @Test
    fun completed_daily_todos_reset_instead_of_archiving() {
        val original = Todo(
            id = "a",
            text = "吃药",
            completed = true,
            completedAt = 55,
            createdDate = "2026-10-17",
            createdAt = 7,
            order = -3,
            remindAt = "09:00",
            remindMode = "daily",
            remindedOn = "2026-10-17",
        )
        val data = AppData(settings = Settings(lastCheckDate = "2026-10-17"), todos = listOf(original))

        val next = Logic.rollover(data, today, 999)!!
        val t = next.todos.single()

        assertTrue(next.archives.isEmpty())
        assertFalse(t.completed)
        assertNull(t.completedAt)
        // 必须改成今天：否则前端会按 todayKey 挂一个 [MM-DD] 前缀，
        // 看起来像一条拖了很久的欠账
        assertEquals(today, t.createdDate)
        // 顺序与创建时间刻意不动 —— 用户拖出来的顺序是明确意图
        assertEquals(7L, t.createdAt)
        assertEquals(-3L, t.order)
        assertEquals("09:00", t.remindAt)
    }

    @Test
    fun uncompleted_daily_todos_are_left_alone() {
        val t = Todo(id = "a", text = "吃药", createdDate = "2026-10-17", remindAt = "09:00", remindMode = "daily")
        val data = AppData(settings = Settings(lastCheckDate = "2026-10-17"), todos = listOf(t))

        assertEquals(t, Logic.rollover(data, today, 999)!!.todos.single())
    }

    @Test
    fun archives_are_capped_at_limit_dropping_the_oldest() {
        val old = (0 until Logic.ARCHIVE_LIMIT).map { Todo(id = "old$it", text = "x") }
        val data = AppData(
            settings = Settings(lastCheckDate = "2026-10-17"),
            todos = listOf(Todo(id = "new", text = "x", completed = true)),
            archives = old,
        )

        val next = Logic.rollover(data, today, 1)!!

        assertEquals(Logic.ARCHIVE_LIMIT, next.archives.size)
        assertEquals("old1", next.archives.first().id)
        assertEquals("new", next.archives.last().id)
    }

    // ---------------------------------------------------------------------
    // 设置提醒
    // ---------------------------------------------------------------------

    @Test
    fun clearing_removes_all_four_fields() {
        val data = AppData(
            todos = listOf(
                Todo(id = "a", text = "x", remindAt = "09:00", remindMode = "daily", remindedOn = today),
            ),
        )
        val t = Logic.setTodoReminder(data, "a", "", "", "", today).todos.single()

        assertNull(t.remindAt)
        assertEquals("", t.remindMode)
        assertEquals("", t.remindDate)
        assertEquals("", t.remindedOn)
    }

    @Test
    fun half_a_reminder_is_also_a_clear() {
        val data = AppData(todos = listOf(Todo(id = "a", text = "x", remindAt = "09:00", remindMode = "daily")))
        assertNull(Logic.setTodoReminder(data, "a", "09:00", "", "", today).todos.single().remindAt)
        assertNull(Logic.setTodoReminder(data, "a", "", "daily", "", today).todos.single().remindAt)
    }

    @Test
    fun setting_a_new_time_rearms_even_if_it_already_fired_today() {
        val data = AppData(
            todos = listOf(
                Todo(id = "a", text = "x", remindAt = "08:00", remindMode = "once", remindDate = today, remindedOn = today),
            ),
        )
        val t = Logic.setTodoReminder(data, "a", "20:00", "once", "", today).todos.single()

        assertEquals("20:00", t.remindAt)
        // 不重新武装的话，用户改完时间就再也不会响了 —— 一个改完没反应的静默失效
        assertEquals("", t.remindedOn)
        // 没给日期 → 今天
        assertEquals(today, t.remindDate)
    }

    @Test
    fun daily_never_keeps_a_date() {
        val data = AppData(todos = listOf(Todo(id = "a", text = "x")))
        val t = Logic.setTodoReminder(data, "a", "09:00", "daily", "2026-12-01", today).todos.single()
        assertEquals("", t.remindDate)
    }

    @Test
    fun unknown_id_is_a_no_op() {
        val data = AppData(todos = listOf(Todo(id = "a", text = "x")))
        assertEquals(data, Logic.setTodoReminder(data, "nope", "09:00", "once", "", today))
    }

    // ---------------------------------------------------------------------
    // 批量完成
    // ---------------------------------------------------------------------

    @Test
    fun completes_only_the_listed_uncompleted_ones() {
        val data = AppData(
            todos = listOf(
                Todo(id = "a", text = "a"),
                Todo(id = "b", text = "b", completed = true),
                Todo(id = "c", text = "c"),
            ),
        )

        val (next, n) = Logic.completeTodos(data, setOf("a", "b"), 42)

        assertEquals(1, n)
        assertEquals(true, next.todos.first { it.id == "a" }.completed)
        assertEquals(42L, next.todos.first { it.id == "a" }.completedAt)
        assertEquals(false, next.todos.first { it.id == "c" }.completed)
    }

    @Test
    fun completing_twice_counts_nothing_the_second_time() {
        val data = AppData(todos = listOf(Todo(id = "a", text = "a")))

        val (once, n1) = Logic.completeTodos(data, setOf("a"), 1)
        val (twice, n2) = Logic.completeTodos(once, setOf("a"), 2)

        assertEquals(1, n1)
        // 0 是「什么都没变」的信号，调用方靠它决定要不要通知前端重绘
        assertEquals(0, n2)
        // 时间戳不该被第二次覆盖
        assertEquals(1L, twice.todos.single().completedAt)
    }

    @Test
    fun missing_ids_do_not_count_as_changes() {
        val data = AppData(todos = listOf(Todo(id = "a", text = "a")))
        val (_, n) = Logic.completeTodos(data, setOf("zzz"), 1)
        assertEquals(0, n)
    }

    @Test
    fun an_empty_id_list_is_always_a_no_op() {
        val data = AppData(todos = listOf(Todo(id = "a", text = "a")))
        val (next, n) = Logic.completeTodos(data, emptySet(), 1)
        assertEquals(0, n)
        assertEquals(data, next)
    }
}
