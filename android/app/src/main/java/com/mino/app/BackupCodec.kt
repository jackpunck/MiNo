package com.mino.app

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

/** 纯 JVM 编解码；绝不通过 Store.load 的容错路径解析外来文件。仅包含字段，不打包图片/字体文件。 */
internal object BackupCodec {
    const val MAX_BYTES = 8 * 1024 * 1024
    const val SCHEMA_VERSION = 1
    private const val FORMAT = "MiNo Android backup"
    private val json = Json(MinoJson) { ignoreUnknownKeys = false }

    // 封装字段不设默认值：{} 或裸 data.json 不能被当成「有效空备份」。
    @Serializable
    private data class Envelope(
        val format: String,
        val schemaVersion: Int,
        val theme: String,
        val data: AppData,
    )

    data class Backup(val data: AppData, val theme: ThemeMode)

    fun encode(data: AppData, theme: ThemeMode): ByteArray {
        validate(data)
        val bytes = json.encodeToString(Envelope(FORMAT, SCHEMA_VERSION, theme.preferenceValue, data))
            .toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "备份超过 8 MiB" }
        return bytes
    }

    fun decode(input: InputStream): Backup {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            // 多读一个字节以区分恰好达到上限与超限；不信任 provider 的大小/available。
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - out.size()))
            if (count < 0) break
            if (count == 0) {
                val one = input.read()
                if (one < 0) break
                out.write(one)
            } else out.write(buffer, 0, count)
            require(out.size() <= MAX_BYTES) { "备份超过 8 MiB" }
        }
        return decode(out.toByteArray())
    }

    fun decode(bytes: ByteArray): Backup {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "备份为空或超过 8 MiB" }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val root = json.parseToJsonElement(text) as? JsonObject
            ?: throw IllegalArgumentException("备份必须是对象")
        val payload = root["data"] as? JsonObject
            ?: throw IllegalArgumentException("缺少备份数据")
        require(payload.keys.containsAll(listOf("version", "settings", "todos", "archives"))) {
            "备份数据不完整"
        }
        for (key in listOf("todos", "archives")) {
            val tasks = payload[key] as? JsonArray ?: throw IllegalArgumentException("待办列表格式错误")
            for (task in tasks) {
                val item = task as? JsonObject ?: throw IllegalArgumentException("待办格式错误")
                require((item["id"] as? JsonPrimitive)?.isString == true &&
                    (item["text"] as? JsonPrimitive)?.isString == true) { "待办缺少 id 或正文" }
            }
        }
        val envelope = json.decodeFromJsonElement<Envelope>(root)
        require(envelope.format == FORMAT) { "不是 MiNo 备份" }
        require(envelope.schemaVersion == SCHEMA_VERSION) { "不支持的备份版本，请使用匹配版本的 MiNo" }
        val theme = ThemeMode.entries.firstOrNull { it.preferenceValue == envelope.theme }
            ?: throw IllegalArgumentException("无效主题")
        validate(envelope.data)
        return Backup(envelope.data, theme)
    }

    private fun validate(data: AppData) {
        require(data.version in 1..DATA_VERSION) { "不支持的数据版本" }
        val ids = HashSet<String>()
        (data.todos.asSequence() + data.archives.asSequence()).forEach { todo ->
            require(todo.id.isNotBlank() && ids.add(todo.id)) { "待办 id 为空或重复" }
            date(todo.createdDate)
            date(todo.remindDate)
            date(todo.remindedOn)
            todo.remindAt?.takeIf { it.isNotEmpty() }?.let {
                require(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]").matches(it)) { "提醒时间无效" }
            }
            require(todo.createdAt >= 0 && (todo.completedAt == null || todo.completedAt >= 0) &&
                (todo.archivedAt == null || todo.archivedAt >= 0)) { "时间戳无效" }
        }
        date(data.settings.lastCheckDate)
        val fonts = HashSet<String>()
        data.settings.customFonts.forEach {
            require(it.id.isNotBlank() && fonts.add(it.id)) { "字体 id 为空或重复" }
        }
    }

    private fun date(value: String) {
        // 空串是模型中尚未设定日期的合法默认值。
        if (value.isEmpty()) return
        require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value)) { "日期格式无效" }
        require(LocalDate.parse(value).year in 1..9999) { "日期无效" }
    }
}
