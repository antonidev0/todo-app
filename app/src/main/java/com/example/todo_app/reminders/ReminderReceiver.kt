package com.example.todo_app.reminders

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.todo_app.MainActivity
import com.example.todo_app.R
import com.example.todo_app.data.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Recibe la alarma y muestra la notificación. También gestiona el botón
 * "Hecha" de la notificación.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = TaskRepository(context)
                when (intent.action) {
                    ACTION_SHOW -> {
                        val task = repo.getTask(taskId)
                        if (task != null && !task.done && task.deletedAt == null) {
                            show(context, taskId, task.title)
                        }
                    }
                    ACTION_DONE -> {
                        repo.setDone(taskId, true)
                        context.getSystemService(NotificationManager::class.java)
                            .cancel(ReminderScheduler.requestCode(taskId))
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun show(context: Context, taskId: String, title: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val code = ReminderScheduler.requestCode(taskId)

        val openApp = PendingIntent.getActivity(
            context,
            code,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val markDone = PendingIntent.getBroadcast(
            context,
            code,
            Intent(context, ReminderReceiver::class.java).apply {
                action = ACTION_DONE
                putExtra(EXTRA_TASK_ID, taskId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Recordatorio")
            .setContentText(title)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .addAction(0, "Hecha", markDone)
            .build()

        NotificationManagerCompat.from(context).notify(code, notification)
    }

    companion object {
        const val ACTION_SHOW = "com.example.todo_app.SHOW_REMINDER"
        const val ACTION_DONE = "com.example.todo_app.MARK_DONE"
        const val EXTRA_TASK_ID = "taskId"
    }
}
