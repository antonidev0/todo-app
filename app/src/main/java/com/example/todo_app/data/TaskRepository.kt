package com.example.todo_app.data

import android.content.Context
import com.example.todo_app.reminders.ReminderScheduler
import kotlinx.coroutines.flow.Flow

/**
 * Único punto de entrada a las tareas. Se encarga también de programar o cancelar
 * los recordatorios cuando una tarea cambia.
 */
class TaskRepository(context: Context) {
    private val dao = AppDatabase.get(context).taskDao()
    private val scheduler = ReminderScheduler(context.applicationContext)

    fun observeChildren(parentId: String?): Flow<List<TaskWithCounts>> = dao.observeChildren(parentId)

    fun observeTask(id: String): Flow<Task?> = dao.observeTask(id)

    suspend fun addTask(parentId: String?, title: String, reminderAt: Long?) {
        val task = Task(
            parentId = parentId,
            title = title.trim(),
            reminderAt = reminderAt,
            position = dao.nextPosition(parentId),
        )
        dao.insert(task)
        scheduler.schedule(task)
    }

    suspend fun editTask(task: Task, title: String, reminderAt: Long?) {
        val updated = task.copy(
            title = title.trim(),
            reminderAt = reminderAt,
            updatedAt = System.currentTimeMillis(),
        )
        dao.update(updated)
        scheduler.schedule(updated)
    }

    suspend fun setDone(taskId: String, done: Boolean) {
        val task = dao.getTask(taskId) ?: return
        val now = System.currentTimeMillis()
        val updated = task.copy(
            done = done,
            completedAt = if (done) now else null,
            updatedAt = now,
        )
        dao.update(updated)
        scheduler.schedule(updated) // si está hecha, esto cancela el recordatorio
    }

    /** Borra la tarea y todas sus subtareas. */
    suspend fun deleteTask(taskId: String) {
        val ids = dao.getSubtreeIds(taskId)
        ids.forEach { scheduler.cancel(it) }
        dao.markDeleted(ids, System.currentTimeMillis())
    }

    /** Vuelve a programar todos los recordatorios (tras reiniciar el móvil). */
    suspend fun rescheduleAll() {
        dao.getUpcomingReminders(System.currentTimeMillis()).forEach { scheduler.schedule(it) }
    }

    suspend fun getTask(id: String): Task? = dao.getTask(id)
}
