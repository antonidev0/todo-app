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

    suspend fun addTask(parentId: String?, title: String, reminderAt: Long?, repeatDays: Int, minutes: Int?) {
        val task = Task(
            parentId = parentId,
            title = title.trim(),
            reminderAt = reminderAt,
            repeatDays = if (reminderAt == null) 0 else repeatDays,
            minutes = minutes,
            position = dao.nextPosition(parentId),
        )
        dao.insert(task)
        scheduler.schedule(task)
    }

    suspend fun editTask(task: Task, title: String, reminderAt: Long?, repeatDays: Int, minutes: Int?) {
        val updated = task.copy(
            title = title.trim(),
            reminderAt = reminderAt,
            repeatDays = if (reminderAt == null) 0 else repeatDays,
            minutes = minutes,
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
        if (done) scheduler.cancelFollowUp(taskId)
        // Si es de una vez y está hecha, esto cancela el recordatorio.
        // Si se repite, deja programada la próxima vez.
        scheduler.schedule(updated)
    }

    /**
     * Ha llegado la hora de una tarea que se repite: vuelve a estar pendiente y su
     * recordatorio pasa a la próxima vez que toque.
     */
    suspend fun startNextOccurrence(task: Task): Task {
        val now = System.currentTimeMillis()
        val updated = task.copy(
            done = false,
            completedAt = null,
            reminderAt = Recurrence.nextFor(task, now) ?: task.reminderAt,
            updatedAt = now,
        )
        dao.update(updated)
        // Por ejemplo "Cierre del viernes": cada semana sus subtareas vuelven a empezar.
        dao.markPending(dao.getSubtreeIds(task.id) - task.id, now)
        scheduler.schedule(updated)
        return updated
    }

    /**
     * Reparte las subtareas pendientes de [parentId] entre [people] y guarda a quién
     * le toca cada una. Devuelve el reparto para mostrarlo o compartirlo.
     */
    suspend fun splitSubtasks(parentId: String, people: List<String>): List<Splitter.Share> {
        val items = dao.getPendingChildren(parentId).map {
            Splitter.Item(it.id, it.title, it.minutes ?: Splitter.DEFAULT_MINUTES)
        }
        val shares = Splitter.split(items, people)
        val now = System.currentTimeMillis()
        shares.forEach { share -> share.items.forEach { dao.setAssignee(it.id, share.person, now) } }
        return shares
    }

    /** Borra la tarea y todas sus subtareas. */
    suspend fun deleteTask(taskId: String) {
        val ids = dao.getSubtreeIds(taskId)
        ids.forEach {
            scheduler.cancel(it)
            scheduler.cancelFollowUp(it)
        }
        dao.markDeleted(ids, System.currentTimeMillis())
    }

    /** Vuelve a programar todos los recordatorios (tras reiniciar el móvil). */
    suspend fun rescheduleAll() {
        val now = System.currentTimeMillis()
        dao.getActiveReminders(now).forEach { task ->
            val at = task.reminderAt
            if (task.isRecurring && at != null && at <= now) {
                // Se pasó mientras el móvil estaba apagado: saltamos a la próxima vez.
                val updated = task.copy(reminderAt = Recurrence.nextFor(task, now), updatedAt = now)
                dao.update(updated)
                scheduler.schedule(updated)
            } else {
                scheduler.schedule(task)
            }
        }
    }

    suspend fun getTask(id: String): Task? = dao.getTask(id)
}
