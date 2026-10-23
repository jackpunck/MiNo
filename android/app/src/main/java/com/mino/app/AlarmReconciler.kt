package com.mino.app

/** System replacement retains live alarms if reconciliation is interrupted. */
internal object AlarmReconciler {
    fun apply(previous: Set<String>, next: Map<String, Long>, arm: (String, Long) -> Unit,
              cancel: (String) -> Unit, persist: (Set<String>) -> Unit) {
        persist(previous + next.keys)
        for ((id, at) in next) arm(id, at)
        for (id in previous - next.keys) cancel(id)
        persist(next.keys)
    }
}
