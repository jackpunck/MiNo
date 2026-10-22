package com.mino.app

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 序列化契约。
 *
 * 这组测试挡的是**本仓库后果最重的那类错误**：少一个字段的默认值，
 * 旧数据就解析失败，`Store.load` 把它改名成 `.broken`，用户看到的是全部待办
 * 凭空消失。桌面端那条铁律（`#[serde(default)]`）在这里的等价物就是
 * 「`Todo` 的每个属性都要有 Kotlin 默认值」。
 */
class SerdeTest {

    @Test
    fun minimal_todo_parses_with_every_optional_field_defaulted() {
        // 只给 id 和 text —— 老数据里其它字段可能全都不在
        val json = """{"todos":[{"id":"t1","text":"写周报"}]}"""
        val data = MinoJson.decodeFromString<AppData>(json)

        assertEquals(1, data.todos.size)
        val t = data.todos[0]
        assertEquals("t1", t.id)
        assertEquals("写周报", t.text)
        assertEquals(false, t.completed)
        assertEquals(0, t.priority)
        assertEquals("", t.createdDate)
        assertEquals(0L, t.createdAt)
        assertNull(t.completedAt)
        assertNull(t.archivedAt)
        assertEquals(0L, t.order)
        assertEquals("", t.font)
        assertNull(t.fontId)
        assertNull(t.remindAt)
        assertEquals("", t.remindMode)
        assertEquals("", t.remindDate)
        assertEquals("", t.remindedOn)
    }

    @Test
    fun empty_object_parses_as_defaults() {
        val data = MinoJson.decodeFromString<AppData>("{}")
        assertEquals(DATA_VERSION, data.version)
        assertTrue(data.todos.isEmpty())
        assertTrue(data.archives.isEmpty())
        assertEquals(DEFAULT_TEXT_COLOR, data.settings.textColor)
        assertEquals(DEFAULT_FONT_CHAIN, data.settings.fontFamily)
    }

    @Test
    fun reminder_fields_are_camel_case_on_disk() {
        // 必须和 Rust 的 rename_all = "camelCase" 逐字一致，否则两边读不懂同一份文件
        val data = AppData(
            todos = listOf(
                Todo(id = "t1", text = "x", remindAt = "09:00", remindMode = "daily", remindedOn = "2026-10-18"),
            ),
        )
        val json = MinoJson.encodeToString(data)

        assertTrue(json.contains("\"remindAt\""))
        assertTrue(json.contains("\"remindMode\""))
        assertTrue(json.contains("\"remindDate\""))
        assertTrue(json.contains("\"remindedOn\""))
        assertTrue(json.contains("\"createdDate\""))
    }

    @Test
    fun unknown_keys_from_a_newer_writer_are_ignored() {
        // 桌面端将来加字段时，手机端不该因此把整份文件判成损坏
        val json = """{"version":4,"todos":[],"brandNewTopLevel":true,"settings":{"brandNewSetting":1}}"""
        val data = MinoJson.decodeFromString<AppData>(json)
        assertEquals(4, data.version)
        assertTrue(data.todos.isEmpty())
    }

    @Test
    fun reminder_fields_survive_a_round_trip() {
        val original = Todo(
            id = "t1",
            text = "吃药",
            remindAt = "09:00",
            remindMode = "daily",
            remindDate = "",
            remindedOn = "2026-10-18",
        )
        val back = MinoJson.decodeFromString<AppData>(MinoJson.encodeToString(AppData(todos = listOf(original))))
        assertEquals(original, back.todos.single())
    }

    @Test
    fun null_remind_at_survives_a_round_trip() {
        val back = MinoJson.decodeFromString<AppData>(
            MinoJson.encodeToString(AppData(todos = listOf(Todo(id = "t1", text = "x")))),
        )
        assertNull(back.todos.single().remindAt)
    }
}
