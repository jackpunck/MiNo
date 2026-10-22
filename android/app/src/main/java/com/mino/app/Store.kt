package com.mino.app

import android.content.Context

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption



import java.util.concurrent.atomic.AtomicInteger

/**
 * 数据唯一权威 + 原子持久化。
 *
 * 对齐桌面端 `state.rs` 的 `load` / `save` / `migrate`：
 * 临时文件 → flush → fsync → rename。Rust 那边靠 `fs::rename`（Windows 上是
 * `MoveFileEx`）保证覆盖是原子的；Android 上同一文件系统里的 `rename(2)` 同样原子。
 *
 * ⚠️ **所有读写都在同一把锁里。** 三个入口会在不同线程上同时摸这份文件：
 *   - 原生界面：数据工作线程
 *   - 闹钟到点：接收器工作线程
 *   - `onResume`：数据刷新
 * 不加锁就是「两次读改写各自从同一份快照出发，后者整份覆盖前者」——
 * 桌面端 `DATA_LOCK` 存在的理由一字不差。`synchronized` 的监视器是可重入的，
 * 所以在外层持锁时调用下面那些 private 方法不会自锁。
 */
class Store internal constructor(private val directory: File) {
    constructor(context: Context) : this(context.applicationContext.filesDir)

    companion object {
        private const val FILE_NAME = "data.json"
        private val LOCK = Any()

        /**
         * 序号放在 companion 里而不是每个实例一份：`Store` 在 MainActivity、
         * 闹钟接收器、通知动作接收器里各 new 过一个，实例自带的计数器会在
         * 「同一毫秒里两个实例各加一条」时撞 id。毫秒 + 全局序号才真的不撞。
         */
        private val SEQ = AtomicInteger(0)
    }

    /** 毫秒 + 自增序号：同一毫秒内连续添加也不会撞 id。 */
    fun newId(): String = "todo_${Logic.nowMs()}_${SEQ.getAndIncrement()}"

    /**
     * 读 + 跨日结算 + 改 + 排序 + 写。**整段持锁。**
     * 返回落盘之后的完整快照 —— 前端靠它整体重绘。
     */
    fun transact(block: (AppData) -> AppData): AppData = synchronized(LOCK) {
        val current = load()
        val rolled = Logic.rollover(current, Logic.today(), Logic.nowMs()) ?: current
        val changed = block(rolled)
        val next = changed.copy(todos = Logic.sortTodos(changed.todos))
        save(next)
        next
    }

    /**
     * 只读快照。仍会顺带跑一次跨日结算（同桌面端的 `current()`），
     * 但**只有真的变了才落盘** —— 与那边 `if rollover > 0 { save }` 相比，
     * 这里判的是「有没有变化」而不是归档条数，所以「只重置了每日任务」也会落盘。
     */
    fun read(): AppData = synchronized(LOCK) {
        val current = load()
        val rolled = Logic.rollover(current, Logic.today(), Logic.nowMs())
        if (rolled != null) {
            val next = rolled.copy(todos = Logic.sortTodos(rolled.todos))
            save(next)
            next
        } else {
            current.copy(todos = Logic.sortTodos(current.todos))
        }
    }

    fun dataPath(): String = File(directory, FILE_NAME).absolutePath

    // -----------------------------------------------------------------------

    private fun load(): AppData {
        val f = File(directory, FILE_NAME)


        // IO 失败不能冒充空数据，否则下一次 transact 会抹掉原文件。
        val bytes = try {
            Files.readAllBytes(f.toPath())
        } catch (_: java.nio.file.NoSuchFileException) {
            return AppData()
        }
        return try {
            migrate(MinoJson.decodeFromString<AppData>(String(bytes, Charsets.UTF_8)))
        } catch (e: Exception) {
            backupBroken(bytes)
            throw java.io.IOException("data.json 损坏；已保留原件和备份，停止写入", e)
        }
    }
    /**
     * 读时迁移。手机端只保留两件仍然有意义的事：
     * 字体链与文字颜色为空时补默认值。桌面端的字体前缀改名、旧目录接管
     * 都是 Windows 的历史包袱，这里不做。
     */
    private fun migrate(d: AppData): AppData {
        var s = d.settings
        if (s.fontFamily.isBlank()) s = s.copy(fontFamily = DEFAULT_FONT_CHAIN)
        if (s.textColor.isBlank()) s = s.copy(textColor = DEFAULT_TEXT_COLOR)
        return d.copy(version = DATA_VERSION, settings = s)
    }

    private fun save(data: AppData) {
        val f = File(directory, FILE_NAME)
        val tmp = File(directory, "$FILE_NAME.tmp")

        try {
            FileOutputStream(tmp).use { out ->
                out.write(MinoJson.encodeToString(data).toByteArray(Charsets.UTF_8))
                out.flush()
                // sync 掉到内核的缓冲区，rename 才是真的「新的那份已经落地」
                out.fd.sync()
            }

            try {
                Files.move(
                    tmp.toPath(),
                    f.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            throw java.io.IOException("保存 data.json 失败", e)
        }
    }

    private fun backupBroken(bytes: ByteArray) {
        // 同一损坏内容只留一份；重试不会不断复制。原件仍留原位，后续写入失败关闭。
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val target = File(directory, "$FILE_NAME.$hash.broken")
        if (target.exists() && target.readBytes().contentEquals(bytes)) return
        val tmp = File(directory, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

}
