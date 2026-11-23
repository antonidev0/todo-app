package com.example.todo_app.data

/**
 * Lo que el repositorio necesita de los recordatorios. La capa de datos no sabe
 * nada de AlarmManager ni de notificaciones: eso lo pone [com.example.todo_app.reminders.ReminderScheduler].
 * Así el repositorio se puede probar con una versión falsa.
 */
interface ReminderAlarms {
    /** Programa el recordatorio de [task], o lo cancela si ya no hace falta. */
    fun schedule(task: Task)

    fun cancel(taskId: String)

    fun cancelFollowUp(taskId: String)

    /** Quita la notificación de [taskId] si se está mostrando. */
    fun cancelNotification(taskId: String)
}
