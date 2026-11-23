package com.example.todo_app.data

import kotlinx.coroutines.flow.Flow

/**
 * Único punto de entrada a las tareas. Se encarga también de programar o cancelar
 * los recordatorios cuando una tarea cambia.
 *
 * Las operaciones que escriben varias filas van dentro de una transacción: o se
 * guardan todas o ninguna, y la lista de la pantalla se actualiza una sola vez.
 * Las alarmas se programan después, cuando los datos ya están guardados.
 */
class TaskRepository(
    private val dao: TaskDao,
    private val transactor: Transactor,
    private val scheduler: ReminderAlarms,
) {
    fun observeChildren(parentId: String?): Flow<List<TaskWithCounts>> = dao.observeChildren(parentId)

    fun observeTask(id: String): Flow<Task?> = dao.observeTask(id)

    suspend fun addTask(parentId: String?, title: String, reminderAt: Long?, repeatDays: Int, minutes: Int?) {
        val task = transactor.inTransaction {
            Task(
                parentId = parentId,
                title = title.trim(),
                reminderAt = reminderAt,
                repeatDays = if (reminderAt == null) 0 else repeatDays,
                minutes = minutes,
                position = dao.nextPosition(parentId),
            ).also { dao.insert(it) }
        }
        scheduler.schedule(task)
    }

    /**
     * Cambia solo los campos del formulario. Se lee la tarea guardada en ese momento
     * para no pisar lo que haya cambiado mientras el diálogo estaba abierto (por
     * ejemplo, si se marcó como hecha desde la notificación o se repartió).
     */
    suspend fun editTask(taskId: String, title: String, reminderAt: Long?, repeatDays: Int, minutes: Int?) {
        val updated = transactor.inTransaction {
            val current = dao.getTask(taskId) ?: return@inTransaction null
            current.copy(
                title = title.trim(),
                reminderAt = reminderAt,
                repeatDays = if (reminderAt == null) 0 else repeatDays,
                minutes = minutes,
                updatedAt = System.currentTimeMillis(),
            ).also { dao.update(it) }
        } ?: return
        scheduler.schedule(updated)
    }

    suspend fun setDone(taskId: String, done: Boolean) {
        val updated = transactor.inTransaction {
            val task = dao.getTask(taskId) ?: return@inTransaction null
            val now = System.currentTimeMillis()
            task.copy(
                done = done,
                completedAt = if (done) now else null,
                updatedAt = now,
            ).also { dao.update(it) }
        } ?: return
        if (done) {
            scheduler.cancelFollowUp(taskId)
            scheduler.cancelNotification(taskId)
        }
        // Si es de una vez y está hecha, esto cancela el recordatorio.
        // Si se repite, deja programada la próxima vez.
        scheduler.schedule(updated)
    }

    /**
     * Ha llegado la hora de una tarea que se repite: vuelve a estar pendiente y su
     * recordatorio pasa a la próxima vez que toque. Devuelve null si la tarea ya no existe.
     */
    suspend fun startNextOccurrence(taskId: String): Task? {
        val updated = transactor.inTransaction {
            val task = dao.getTask(taskId) ?: return@inTransaction null
            val now = System.currentTimeMillis()
            val next = task.copy(
                done = false,
                completedAt = null,
                reminderAt = Recurrence.nextFor(task, now) ?: task.reminderAt,
                updatedAt = now,
            )
            dao.update(next)
            // Por ejemplo "Cierre del viernes": cada semana sus subtareas vuelven a empezar.
            dao.markPending(dao.getSubtreeIds(taskId) - taskId, now)
            next
        } ?: return null
        scheduler.schedule(updated)
        return updated
    }

    /**
     * Reparte las subtareas pendientes de [parentId] entre [people] y guarda a quién
     * le toca cada una. Devuelve el reparto para mostrarlo o compartirlo.
     */
    suspend fun splitSubtasks(parentId: String, people: List<String>): List<Splitter.Share> =
        transactor.inTransaction {
            val items = dao.getPendingChildren(parentId).map {
                Splitter.Item(it.id, it.title, it.minutes ?: Splitter.DEFAULT_MINUTES)
            }
            val shares = Splitter.split(items, people)
            val now = System.currentTimeMillis()
            shares.forEach { share -> share.items.forEach { dao.setAssignee(it.id, share.person, now) } }
            shares
        }

    /** Borra la tarea y todas sus subtareas. */
    suspend fun deleteTask(taskId: String) {
        val ids = transactor.inTransaction {
            dao.getSubtreeIds(taskId).also { dao.markDeleted(it, System.currentTimeMillis()) }
        }
        ids.forEach {
            scheduler.cancel(it)
            scheduler.cancelFollowUp(it)
            scheduler.cancelNotification(it)
        }
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
