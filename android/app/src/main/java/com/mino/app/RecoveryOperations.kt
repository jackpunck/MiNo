package com.mino.app

/** All access is serialized by ReminderRecoveryJob's protocol lock. */
internal class RecoveryOperations {
    private val active = mutableSetOf<String>()
    private var dirty = false
    private var version = 0L
    fun begin(token: String) { active.add(token); version++ }
    fun finish(token: String): Boolean = active.remove(token) && active.isEmpty() && !dirty
    fun abandon(token: String) {
        if (active.remove(token)) { dirty = true; version++ }
    }
    fun isIdle(): Boolean = active.isEmpty()
    fun repairStamp(): Long? = if (isIdle()) version else null
    fun repaired(stamp: Long): Boolean {
        if (!isIdle() || version != stamp) return false
        dirty = false
        return true
    }
}
