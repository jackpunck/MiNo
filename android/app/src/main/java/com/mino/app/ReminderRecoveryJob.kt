package com.mino.app

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One coalesced, persisted repair job, not a polling or resident service. */
class ReminderRecoveryJob : JobService() {
    companion object {
        const val JOB_ID = 20301
        private val executor = Executors.newSingleThreadExecutor { Thread(it, "mino-repair") }
        private val lock = Any()
        private val operations = RecoveryOperations()

        /** Register before changing data, so process death cannot lose the repair request. */
        fun schedule(context: Context): String = synchronized(lock) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
                ?: error("System job scheduler unavailable")
            val generation = java.util.UUID.randomUUID().toString()
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, ReminderRecoveryJob::class.java))
                .setPersisted(true).setMinimumLatency(15_000L).setOverrideDeadline(60_000L)
                .setExtras(PersistableBundle().apply { putString("generation", generation) }).build()
            operations.begin(generation)
            try {
                check(scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS) { "Cannot register reminder repair" }
            } catch (error: Exception) {
                operations.finish(generation)
                throw error
            }
            generation
        }
        /** Keep the guard until ALL registered operations have reconciled, in either order. */
        fun complete(context: Context, generation: String) = synchronized(lock) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return@synchronized
            if (operations.finish(generation)) {
                scheduler.cancel(JOB_ID)
            }
        }
        /** Release failed work but leave its persisted system repair request intact. */
        fun abandon(generation: String) = synchronized(lock) { operations.abandon(generation) }
        private fun repairStamp() = synchronized(lock) { operations.repairStamp() }
        private fun repaired(stamp: Long) = synchronized(lock) { operations.repaired(stamp) }
    }
    private var stopped = AtomicBoolean(false)
    override fun onStartJob(params: JobParameters): Boolean {
        val cancelled = AtomicBoolean(false)
        stopped = cancelled
        executor.execute {
            val stamp = repairStamp()
            val failed = stamp == null || runCatching {
                Reminders.reschedule(applicationContext, shouldContinue = { !cancelled.get() })
            }.isFailure || cancelled.get() || !repaired(stamp)
            if (failed) Log.w("MiNo/Recovery", "提醒修复等待系统重试")
            else Log.i("MiNo/Recovery", "后台提醒修复完成")
            if (!cancelled.get()) jobFinished(params, failed)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        stopped.set(true)
        return true
    }
}
