package com.example.todo_app.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object Notifications {
    const val CHANNEL_ID = "recordatorios"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Recordatorios de tareas",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Avisos a la hora que elegiste para cada tarea"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
