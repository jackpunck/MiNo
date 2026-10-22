package com.mino.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 数据模型 —— 逐字段照搬桌面端的 `src-tauri/src/state.rs`。
 *
 * 两边的 JSON 必须能互相读懂：Rust 侧全部是 `#[serde(rename_all = "camelCase")]`，
 * 所以这里的属性名直接写成 camelCase，不需要任何 `@SerialName`。
 *
 * ⚠️ **每一个字段都必须带 Kotlin 默认值。**
 *
 * 这是桌面端「少一个 `#[serde(default)]` 就会清空用户全部待办」那条铁律的移植版，
 * 而且失败形态一模一样：kotlinx.serialization 缺字段会抛 `MissingFieldException`
 * → 走 `Store.load` 的损坏分支 → 原文件改名 `.broken` → 用户看到所有待办凭空消失。
 * `SerdeTest` 里用只含 `id` / `text` 的最简 JSON 钉住这件事。
 *
 * 反过来，**新增字段时默认值本身也必须让老数据的行为不变**（`order` 默认 0 是
 * 恒等键，排序仍落回 createdAt 倒序），否则等于没迁移。
 */

const val DATA_VERSION = 4
const val DEFAULT_TEXT_COLOR = "#f5f5f7"
const val DEFAULT_MASK_OPACITY = 0.28
const val DEFAULT_BLUR = 6.0

/**
 * 默认字体链。
 *
 * 刻意**不**沿用桌面端的 `"Segoe UI", "Microsoft YaHei", "微软雅黑"` ——
 * 那三个在 Android 上一个都不存在，整条链会静默落到末端。这里换成 Android
 * 上真实存在的 Roboto / 思源黑体。
 */
const val DEFAULT_FONT_CHAIN = "\"Roboto\", \"Noto Sans CJK SC\", system-ui, sans-serif"

@Serializable
data class Todo(
    val id: String = "",
    val text: String = "",
    val completed: Boolean = false,
    /** 数据里保留但当前不参与任何逻辑；新增时写 1，与桌面端一致。 */
    val priority: Int = 0,
    /** 本地日期 `YYYY-MM-DD`。 */
    val createdDate: String = "",
    /** Unix 毫秒。 */
    val createdAt: Long = 0L,
    val completedAt: Long? = null,
    val archivedAt: Long? = null,
    /** 用户排列顺序，越小越靠前。老数据全 0 → 退化为 createdAt 倒序。 */
    val order: Long = 0L,
    val font: String = "",
    val fontId: String? = null,
    /** `"HH:MM"`，已补零。null / 空串 = 没有提醒。 */
    val remindAt: String? = null,
    /** `""` / `"once"` / `"daily"`；未知值按 once 解释。 */
    val remindMode: String = "",
    /** 仅 `once` 使用，`YYYY-MM-DD`；空 = 回落到 createdDate。 */
    val remindDate: String = "",
    /** 最后一次触发的本地日期，防同日重复、防重启重放。 */
    val remindedOn: String = "",
)

@Serializable
data class CustomFont(
    val id: String = "",
    val family: String = "",
    val label: String = "",
    val file: String = "",
)

@Serializable
data class Position(
    val x: Int = 0,
    val y: Int = 0,
)

/**
 * 手机端用不到的字段（快捷键、窗口位置、边缘吸附、自定义字体）照样保留 ——
 * 它们本来就有默认值，删掉只会让两边结构分叉，换不来任何好处。
 */
@Serializable
data class Settings(
    val alwaysOnTop: Boolean = true,
    val windowMode: String = "expanded",
    val lastCheckDate: String = "",
    val bgType: String = "mica",
    val bgPath: String? = null,
    val maskOpacity: Double = DEFAULT_MASK_OPACITY,
    val blur: Double = DEFAULT_BLUR,
    val textColor: String = DEFAULT_TEXT_COLOR,
    val fontFamily: String = DEFAULT_FONT_CHAIN,
    val fontId: String? = null,
    val customFonts: List<CustomFont> = emptyList(),
    val windowPosition: Position? = null,
    val shortcut: String = "Alt+Space",
    val edgeSnap: Boolean = true,
    val edgeThreshold: Int = 10,
    val edgeHandleSize: Int = 4,
)

@Serializable
data class AppData(
    val version: Int = DATA_VERSION,
    val settings: Settings = Settings(),
    val todos: List<Todo> = emptyList(),
    val archives: List<Todo> = emptyList(),
)

/**
 * - `ignoreUnknownKeys`：桌面端将来加字段时，手机端不会因此把文件判成损坏。
 * - `encodeDefaults`：Rust 侧没有任何 `skip_serializing_if`，每个字段都会写出去；
 *   这里保持一致，免得「字段消失」在两种解析器下表现不同。
 */
val MinoJson = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = true
}
