package com.example.todo_app.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.todo_app.data.Task

/** Programa y cancela las alarmas exactas de los recordatorios. */
class ReminderScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    /** Programa el recordatorio de [task], o lo cancela si ya no hace falta. */
    fun schedule(task: Task) {
        val at = task.reminderAt
        if (at == null || task.done || task.deletedAt != null || at <= System.currentTimeMillis()) {
            cancel(task.id)
            return
        }
        val pending = pendingIntent(task.id)
        if (canScheduleExact(context)) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } else {
            // Sin permiso de alarmas exactas: puede llegar con unos minutos de retraso.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    fun cancel(taskId: String) {
        alarmManager.cancel(pendingIntent(taskId))
    }

    private fun pendingIntent(taskId: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_SHOW
            putExtra(ReminderReceiver.EXTRA_TASK_ID, taskId)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode(taskId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        fun requestCode(taskId: String): Int = taskId.hashCode()

        fun canScheduleExact(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }
}
