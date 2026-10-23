package com.mino.app

import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.os.Bundle
import android.util.Log
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Only compiled into com.mino.app.probe; logs counts/times, never task content. */
class ReminderProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val operation = intent.getStringExtra("operation") ?: "seed"
        Thread {
            val store = Store(applicationContext)
            if (operation == "network") {
                runCatching {
                    val connection = java.net.URL(UpdateClient.LATEST).openConnection() as java.net.HttpURLConnection
                    connection.connectTimeout = 15000
                    connection.readTimeout = 15000
                    connection.setRequestProperty("User-Agent", "MiNo-Android")
                    connection.setRequestProperty("Accept", "application/vnd.github+json")
                    try { Log.i("MiNoProbe", "update-api-http=${connection.responseCode}") }
                    finally { connection.disconnect() }
                }
                try { Log.i("MiNoProbe", "update-metadata-present=${UpdateClient().check() != null}") }
                catch (e: Exception) {
                    // Diagnosis contains exception type names only, never response bodies or user data.
                    Log.i("MiNoProbe", "network-failure=${e.javaClass.simpleName};cause=${e.cause?.javaClass?.simpleName}")
                }
            } else if (operation == "seed" || operation == "saveOnly") {
                val next = LocalDateTime.now().plusMinutes(1).withSecond(0).withNano(0)
                ReminderRecoveryJob.schedule(applicationContext)
                store.transact { AppData(settings = Settings(lastCheckDate = Logic.today()), todos = listOf(
                    Todo(id = "probe_once", text = "MiNo test", createdDate = Logic.today(),
                        remindAt = next.format(DateTimeFormatter.ofPattern("HH:mm")),
                        remindDate = next.toLocalDate().toString(), remindMode = "once"))) }
                if (operation != "saveOnly") Reminders.reschedule(applicationContext)
                Log.i("MiNoProbe", "seeded;due=$next;exact=${getSystemService(AlarmManager::class.java).canScheduleExactAlarms()}")
            } else {
                val data = store.read()
                val sent = data.todos.count { it.remindedOn.isNotEmpty() }
                val active = getSystemService(NotificationManager::class.java).activeNotifications.size
                Log.i("MiNoProbe", "sent=$sent;notifications=$active")
            }
            runOnUiThread { finishAndRemoveTask() }
        }.start()
    }
}
