package com.example.todo_app.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.example.todo_app.data.ReminderAlarms
import com.example.todo_app.data.Task

/**
 * Programa y cancela las alarmas de los recordatorios.
 *
 * Cada tarea puede tener dos alarmas:
 *  - la principal, a la hora del recordatorio ([schedule]);
 *  - la de seguimiento, que vuelve a preguntar "¿Ya está?" un rato después
 *    o cuando pulsas "En 15 min" ([scheduleFollowUp]).
 */
class ReminderScheduler(private val context: Context) : ReminderAlarms {

    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    override fun schedule(task: Task) {
        val at = task.reminderAt
        val recurring = task.repeatDays != 0
        val needed = at != null && task.deletedAt == null &&
            at > System.currentTimeMillis() && (recurring || !task.done)
        if (!needed) {
            cancel(task.id)
            return
        }
        setAlarm(at!!, mainIntent(task.id))
    }

    override fun cancel(taskId: String) {
        alarmManager.cancel(mainIntent(taskId))
    }

    /** Vuelve a avisar de [taskId] dentro de [minutes] minutos. */
    fun scheduleFollowUp(taskId: String, minutes: Int, askAgain: Boolean) {
        val at = System.currentTimeMillis() + minutes * 60_000L
        setAlarm(at, followUpIntent(taskId, askAgain))
    }

    override fun cancelFollowUp(taskId: String) {
        alarmManager.cancel(followUpIntent(taskId, askAgain = false))
    }

    override fun cancelNotification(taskId: String) {
        NotificationManagerCompat.from(context).cancel(requestCode(taskId))
    }

    private fun setAlarm(at: Long, pending: PendingIntent) {
        if (canScheduleExact(context)) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } else {
            // Sin permiso de alarmas exactas: puede llegar con unos minutos de retraso.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    private fun mainIntent(taskId: String): PendingIntent =
        broadcast(taskId, requestCode(taskId), ReminderReceiver.ACTION_SHOW)

    private fun followUpIntent(taskId: String, askAgain: Boolean): PendingIntent =
        broadcast(taskId, followUpCode(taskId), ReminderReceiver.ACTION_FOLLOW_UP) {
            putExtra(ReminderReceiver.EXTRA_ASK_AGAIN, askAgain)
        }

    private fun broadcast(
        taskId: String,
        code: Int,
        action: String,
        extras: Intent.() -> Unit = {},
    ): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            this.action = action
            putExtra(ReminderReceiver.EXTRA_TASK_ID, taskId)
            extras()
        }
        return PendingIntent.getBroadcast(
            context,
            code,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        /** Id de la alarma principal y de la notificación de la tarea. */
        fun requestCode(taskId: String): Int = taskId.hashCode()

        private fun followUpCode(taskId: String): Int = taskId.hashCode() xor 0x5A5A5A5A

        fun canScheduleExact(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }
}
