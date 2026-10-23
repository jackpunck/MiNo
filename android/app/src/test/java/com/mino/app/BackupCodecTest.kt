package com.mino.app

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class BackupCodecTest {
    private val todo = Todo(
        id = "任务-1", text = "<文字>\n备份", completed = true, priority = 2,
        createdDate = "2024-02-29", createdAt = 123, completedAt = 456, archivedAt = 789,
        order = 42, font = "自定义字体", fontId = "font-1", remindAt = "23:59",
        remindMode = "daily", remindDate = "2024-03-01", remindedOn = "2024-03-02",
    )
    private val data = AppData(settings = Settings(
        alwaysOnTop = false, windowMode = "collapsed", lastCheckDate = "2024-03-02",
        bgType = "image", bgPath = "local/background.png", maskOpacity = 0.6, blur = 3.5,
        textColor = "#123456", fontFamily = "family", fontId = "font-1",
        customFonts = listOf(CustomFont("font-1", "family", "字体", "local/font.ttf")),
        windowPosition = Position(-123, 456), shortcut = "Ctrl+Space", edgeSnap = false,
        edgeThreshold = 13, edgeHandleSize = 7,
    ), todos = listOf(todo), archives = listOf(todo.copy(id = "archive-1")))

    @Test fun roundTripPreservesEveryFieldAndBothThemes() {
        ThemeMode.entries.forEach { theme ->
            val decoded = BackupCodec.decode(BackupCodec.encode(data, theme))
            assertEquals(data, decoded.data)
            assertEquals(theme, decoded.theme)
        }
    }

    @Test fun explicitEmptyBackupIsValid() {
        assertEquals(AppData(), BackupCodec.decode(BackupCodec.encode(AppData(), ThemeMode.DAY)).data)
    }

    @Test fun refusesMissingEnvelopeAndMissingPayloadRatherThanRestoringDefaults() {
        listOf("{}", "{\"todos\":[]}", """{"format":"MiNo Android backup","schemaVersion":1,"theme":"night"}""",
            """{"format":"MiNo Android backup","schemaVersion":1,"theme":"night","data":{}}""")
            .forEach { invalid -> assertThrows(Exception::class.java) { BackupCodec.decode(invalid.toByteArray()) } }
    }

    @Test fun rejectsFutureEnvelopeAndDataVersionsAndUnknownTheme() {
        val json = String(BackupCodec.encode(data, ThemeMode.NIGHT), Charsets.UTF_8)
        listOf(json.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"),
            json.replace("\"version\": $DATA_VERSION", "\"version\": ${DATA_VERSION + 1}"),
            json.replace("\"theme\": \"night\"", "\"theme\": \"system\""))
            .forEach { invalid -> assertThrows(Exception::class.java) { BackupCodec.decode(invalid.toByteArray()) } }
    }

    @Test fun rejectsBlankAndDuplicateIdsIncludingAcrossArchives() {
        listOf(data.copy(todos = listOf(todo.copy(id = "  "))),
            data.copy(todos = listOf(todo, todo)),
            data.copy(archives = listOf(todo)),
            data.copy(settings = data.settings.copy(customFonts = listOf(CustomFont(id = "")))))
            .forEach { invalid -> assertThrows(Exception::class.java) { BackupCodec.encode(invalid, ThemeMode.DAY) } }
    }

    @Test fun validatesCalendarDatesAndClockTimesStrictly() {
        listOf(todo.copy(createdDate = "2023-02-29"), todo.copy(createdDate = "2024-2-29"),
            todo.copy(remindDate = "2024-04-31"), todo.copy(remindedOn = "yesterday"),
            todo.copy(remindAt = "24:00"), todo.copy(remindAt = "9:05"), todo.copy(createdAt = -1))
            .forEach { invalid -> assertThrows(Exception::class.java) {
                BackupCodec.encode(data.copy(todos = listOf(invalid)), ThemeMode.DAY)
            } }
        assertThrows(Exception::class.java) {
            BackupCodec.encode(data.copy(settings = data.settings.copy(lastCheckDate = "2024-13-01")), ThemeMode.DAY)
        }
    }

    @Test fun validationAlsoRunsOnUntrustedDecodedInput() {
        val json = String(BackupCodec.encode(data, ThemeMode.DAY), Charsets.UTF_8)
        assertThrows(Exception::class.java) { BackupCodec.decode(json.replace("23:59", "25:99").toByteArray()) }
        assertThrows(Exception::class.java) { BackupCodec.decode(json.replace("archive-1", "任务-1").toByteArray()) }
    }

    @Test fun boundsActualStreamBytesWithoutTrustingAvailable() {
        var consumed = 0
        val stream = object : InputStream() {
            override fun available() = 0
            override fun read(): Int { consumed++; return ' '.code }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                b.fill(' '.code.toByte(), off, off + len); consumed += len; return len
            }
        }
        assertThrows(Exception::class.java) { BackupCodec.decode(stream) }
        assertTrue(consumed <= BackupCodec.MAX_BYTES + 1)
        assertThrows(Exception::class.java) { BackupCodec.decode(ByteArray(BackupCodec.MAX_BYTES + 1)) }
    }

    @Test fun rejectsMalformedUtf8AndTruncatedJson() {
        assertThrows(Exception::class.java) { BackupCodec.decode(byteArrayOf(0xc3.toByte(), 0x28)) }
        val bytes = BackupCodec.encode(data, ThemeMode.NIGHT)
        assertThrows(Exception::class.java) { BackupCodec.decode(bytes.copyOf(bytes.size - 1)) }
        assertEquals(data, BackupCodec.decode(ByteArrayInputStream(bytes)).data)
    }

    @Test fun acceptsExactlyEightMiBButRejectsOneExtraByte() {
        val bytes = BackupCodec.encode(AppData(), ThemeMode.DAY)
        val padded = ByteArray(BackupCodec.MAX_BYTES) { ' '.code.toByte() }
        bytes.copyInto(padded)
        assertEquals(AppData(), BackupCodec.decode(ByteArrayInputStream(padded)).data)
        assertThrows(Exception::class.java) {
            BackupCodec.decode(ByteArrayInputStream(padded + byteArrayOf(32)))
        }
    }
    @Test fun missingTextInAnOtherwiseValidEnvelopeCannotSilentlyClearContent() {
        val json = String(BackupCodec.encode(AppData(todos = listOf(Todo(id = "a", text = "keep"))), ThemeMode.DAY))
        assertThrows(Exception::class.java) {
            BackupCodec.decode(json.replace("\"text\": \"keep\",", "").toByteArray())
        }
    }
}
