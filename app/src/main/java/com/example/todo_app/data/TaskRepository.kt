package com.example.todo_app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

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

    /** Todas las tareas y lo hecho cada día, listo para mirar por días. */
    fun observePlan(today: LocalDate = LocalDate.now()): Flow<DayPlan> =
        combine(dao.observeAll(), dao.observeCompletionsSince(today.minusDays(7).toString())) { tasks, done ->
            DayPlan(tasks, done)
        }

    /**
     * Guarda una tarea nueva y, si se dan, sus [subtasks] (solo el título), todo en una
     * transacción. Devuelve la tarea guardada.
     */
    suspend fun addTask(parentId: String?, form: TaskForm, subtasks: List<String> = emptyList()): Task {
        val clean = normalize(form)
        val task = transactor.inTransaction {
            val parent = Task(
                parentId = parentId,
                title = clean.title,
                reminderAt = clean.reminderAt,
                repeatDays = clean.repeatDays,
                minutes = clean.minutes,
                priority = clean.priority,
                date = clean.date,
                position = dao.nextPosition(parentId),
            ).also { dao.insert(it) }
            subtasks.map { it.trim() }.filter { it.isNotEmpty() }.forEachIndexed { i, title ->
                dao.insert(Task(parentId = parent.id, title = title, position = i))
            }
            parent
        }
        scheduler.schedule(task)
        return task
    }

    /**
     * Cambia solo los campos del formulario. Se lee la tarea guardada en ese momento
     * para no pisar lo que haya cambiado mientras el diálogo estaba abierto (por
     * ejemplo, si se marcó como hecha desde la notificación o se repartió).
     */
    suspend fun editTask(taskId: String, form: TaskForm) {
        val clean = normalize(form)
        val updated = transactor.inTransaction {
            val current = dao.getTask(taskId) ?: return@inTransaction null
            current.copy(
                title = clean.title,
                reminderAt = clean.reminderAt,
                repeatDays = clean.repeatDays,
                minutes = clean.minutes,
                priority = clean.priority,
                date = clean.date,
                updatedAt = System.currentTimeMillis(),
            ).also { dao.update(it) }
        } ?: return
        scheduler.schedule(updated)
    }

    /**
     * Deja el formulario coherente: las que se repiten no tienen día fijo, y las de
     * una vez con hora van el día de esa hora.
     */
    private fun normalize(form: TaskForm): TaskForm {
        val at = form.reminderAt
        val date = when {
            form.repeatDays != 0 -> null
            at != null -> Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate().toString()
            else -> form.date
        }
        return form.copy(title = form.title.trim(), date = date)
    }

    /**
     * Marca [taskId] como hecha (o no) el día [date]. Si su tarea principal se repite,
     * solo cuenta para ese día; si no, la tarea queda hecha para siempre.
     */
    suspend fun setDoneOn(taskId: String, date: LocalDate, done: Boolean) {
        val perDay = transactor.inTransaction {
            val task = dao.getTask(taskId) ?: return@inTransaction null
            val perDay = DayPlan(dao.getAll(), emptyList()).isPerDay(task)
            if (perDay) {
                if (done) dao.insertCompletion(Completion(taskId, date.toString()))
                else dao.deleteCompletion(taskId, date.toString())
            }
            perDay
        } ?: return
        if (!perDay) {
            setDone(taskId, done)
        } else if (done && date == LocalDate.now()) {
            scheduler.cancelFollowUp(taskId)
            scheduler.cancelNotification(taskId)
        }
    }

    /** ¿Está [taskId] hecha el día [date]? */
    suspend fun isDoneOn(taskId: String, date: LocalDate): Boolean {
        val task = dao.getTask(taskId) ?: return false
        return DayPlan(dao.getAll(), dao.getCompletionsOn(date.toString())).isDone(task, date)
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
            // Lo hecho se guarda por día, así que aquí solo se pasa a la próxima vez.
            val next = task.copy(
                reminderAt = Recurrence.nextFor(task, now) ?: task.reminderAt,
                updatedAt = now,
            )
            dao.update(next)
            next
        } ?: return null
        scheduler.schedule(updated)
        return updated
    }

    /**
     * Reparte entre [people] lo pendiente del día [date]: las tareas principales de ese
     * día si [parentId] es null, o las subtareas de [parentId]. Las tareas con subtareas
     * pendientes se reparten por subtareas. Guarda a quién le toca cada una y devuelve
     * el reparto para mostrarlo o compartirlo.
     */
    suspend fun splitWork(parentId: String?, date: LocalDate?, people: List<String>): List<Splitter.Share> =
        transactor.inTransaction {
            val plan = DayPlan(dao.getAll(), date?.let { dao.getCompletionsOn(it.toString()) }.orEmpty())
            val list = if (parentId == null) plan.topLevelOn(date) else plan.childrenOf(parentId, date)
            val items = plan.pendingWork(list, date).map {
                Splitter.Item(it.id, it.title, it.minutes ?: Splitter.DEFAULT_MINUTES, it.priority)
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
