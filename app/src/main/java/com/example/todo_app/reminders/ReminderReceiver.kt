package com.example.todo_app.reminders

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.todo_app.MainActivity
import com.example.todo_app.R
import com.example.todo_app.data.Task
import com.example.todo_app.appContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Recibe las alarmas y muestra las notificaciones. Hace de pequeño "bot":
 *  - a la hora del recordatorio te avisa y te pregunta "¿Ya está?";
 *  - si no contestas, a los [FOLLOW_UP_MINUTES] minutos te lo vuelve a preguntar;
 *  - "En 15 min" lo pospone [SNOOZE_MINUTES] minutos;
 *  - "Hecha" marca la tarea como hecha.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                handle(context, intent, taskId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Sin esto, un fallo de la base de datos cerraría la app en segundo plano.
                Log.e(TAG, "No se pudo procesar ${intent.action} de la tarea $taskId", e)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(context: Context, intent: Intent, taskId: String) {
        val repo = context.appContainer.taskRepository
        val scheduler = context.appContainer.reminderScheduler

        when (intent.action) {
            ACTION_SHOW -> {
                var task = repo.getTask(taskId) ?: return
                if (task.deletedAt != null) return
                // Si ya la marcaste como hecha hoy (por ejemplo por la mañana), no molesta.
                val doneToday = repo.isDoneOn(taskId, LocalDate.now())
                if (task.repeatDays != 0) {
                    // Programa la próxima vez que toque, avise o no ahora.
                    task = repo.startNextOccurrence(taskId) ?: return
                }
                if (doneToday) return
                show(context, task, followUp = false)
                scheduler.scheduleFollowUp(taskId, FOLLOW_UP_MINUTES, askAgain = false)
            }

            ACTION_FOLLOW_UP -> {
                val task = repo.getTask(taskId) ?: return
                if (task.deletedAt != null || repo.isDoneOn(taskId, LocalDate.now())) return
                show(context, task, followUp = true)
                if (intent.getBooleanExtra(EXTRA_ASK_AGAIN, false)) {
                    scheduler.scheduleFollowUp(taskId, FOLLOW_UP_MINUTES, askAgain = false)
                }
            }

            ACTION_SNOOZE -> {
                scheduler.cancelNotification(taskId)
                scheduler.scheduleFollowUp(taskId, SNOOZE_MINUTES, askAgain = true)
            }

            ACTION_DONE -> {
                // setDone también quita la notificación.
                repo.setDoneOn(taskId, LocalDate.now(), true)
            }
        }
    }

    private fun show(context: Context, task: Task, followUp: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val code = ReminderScheduler.requestCode(task.id)

        val openApp = PendingIntent.getActivity(
            context,
            code,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = if (followUp) "¿Ya está hecho?" else "⏰ ${task.title}"
        val text = if (followUp) task.title else "¿Ya está? Pulsa «Hecha» cuando termines."

        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .addAction(0, "Hecha", action(context, task.id, ACTION_DONE, code))
            .addAction(0, "En $SNOOZE_MINUTES min", action(context, task.id, ACTION_SNOOZE, code))
            .build()

        NotificationManagerCompat.from(context).notify(code, notification)
    }

    private fun action(context: Context, taskId: String, action: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            code,
            Intent(context, ReminderReceiver::class.java).apply {
                this.action = action
                putExtra(EXTRA_TASK_ID, taskId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        private const val TAG = "ReminderReceiver"

        const val ACTION_SHOW = "com.example.todo_app.SHOW_REMINDER"
        const val ACTION_FOLLOW_UP = "com.example.todo_app.FOLLOW_UP"
        const val ACTION_SNOOZE = "com.example.todo_app.SNOOZE"
        const val ACTION_DONE = "com.example.todo_app.MARK_DONE"
        const val EXTRA_TASK_ID = "taskId"
        const val EXTRA_ASK_AGAIN = "askAgain"

        const val FOLLOW_UP_MINUTES = 30
        const val SNOOZE_MINUTES = 15
    }
}
