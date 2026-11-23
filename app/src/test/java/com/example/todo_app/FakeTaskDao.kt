package com.example.todo_app

import com.example.todo_app.data.Completion
import com.example.todo_app.data.ReminderAlarms
import com.example.todo_app.data.Task
import com.example.todo_app.data.TaskDao
import com.example.todo_app.data.TaskWithCounts
import com.example.todo_app.data.TeamStore
import com.example.todo_app.data.Transactor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Base de datos en memoria que imita las consultas de [TaskDao] para los tests. */
class FakeTaskDao : TaskDao {
    private val rows = MutableStateFlow<Map<String, Task>>(emptyMap())
    private val completions = MutableStateFlow<Set<Completion>>(emptySet())

    val allCompletions: Set<Completion> get() = completions.value

    val all: Map<String, Task> get() = rows.value

    private fun live() = rows.value.values.filter { it.deletedAt == null }

    private fun put(task: Task) {
        rows.value = rows.value + (task.id to task)
    }

    override fun observeChildren(parentId: String?): Flow<List<TaskWithCounts>> = rows.map {
        live().filter { it.parentId == parentId }.map { t ->
            val children = live().filter { it.parentId == t.id }
            TaskWithCounts(t, children.size, children.count { it.done })
        }
    }

    override fun observeTask(id: String): Flow<Task?> = rows.map { it[id] }

    override suspend fun getTask(id: String): Task? = rows.value[id]

    override suspend fun getSubtreeIds(id: String): List<String> =
        listOf(id) + rows.value.values.filter { it.parentId == id }.flatMap { getSubtreeIds(it.id) }

    override suspend fun nextPosition(parentId: String?): Int =
        (rows.value.values.filter { it.parentId == parentId }.maxOfOrNull { it.position } ?: -1) + 1

    override suspend fun getActiveReminders(now: Long): List<Task> = live().filter {
        val at = it.reminderAt ?: return@filter false
        it.repeatDays != 0 || (!it.done && at > now)
    }

    override suspend fun getPendingChildren(parentId: String): List<Task> =
        live().filter { it.parentId == parentId && !it.done }.sortedBy { it.position }

    override suspend fun setAssignee(id: String, assignee: String?, now: Long) {
        rows.value[id]?.let { put(it.copy(assignee = assignee, updatedAt = now)) }
    }

    override suspend fun markPending(ids: List<String>, now: Long) {
        ids.mapNotNull { rows.value[it] }.filter { it.done }
            .forEach { put(it.copy(done = false, completedAt = null, updatedAt = now)) }
    }

    override fun observeAll(): Flow<List<Task>> = rows.map { live() }

    override suspend fun getAll(): List<Task> = live()

    override fun observeCompletionsSince(fromDate: String): Flow<List<Completion>> =
        completions.map { set -> set.filter { it.date >= fromDate } }

    override suspend fun getCompletionsOn(date: String): List<Completion> =
        completions.value.filter { it.date == date }

    override suspend fun insertCompletion(completion: Completion) {
        deleteCompletion(completion.taskId, completion.date)
        completions.value = completions.value + completion
    }

    override suspend fun deleteCompletion(taskId: String, date: String) {
        completions.value = completions.value.filterNot { it.taskId == taskId && it.date == date }.toSet()
    }

    override suspend fun insert(task: Task) = put(task)

    override suspend fun update(task: Task) {
        if (task.id in rows.value) put(task)
    }

    override suspend fun markDeleted(ids: List<String>, now: Long) {
        ids.mapNotNull { rows.value[it] }.forEach { put(it.copy(deletedAt = now, updatedAt = now)) }
    }
}

/** Ejecuta el bloque directamente: la base de datos falsa no tiene transacciones. */
object DirectTransactor : Transactor {
    override suspend fun <R> inTransaction(block: suspend () -> R): R = block()
}

/** Apunta qué alarmas y notificaciones se pidieron, en vez de programarlas. */
class FakeAlarms : ReminderAlarms {
    /** Si es true, programar falla como si Android rechazara la alarma. */
    var failSchedule = false
    val scheduled = mutableListOf<Task>()
    val cancelled = mutableListOf<String>()
    val cancelledFollowUps = mutableListOf<String>()
    val cancelledNotifications = mutableListOf<String>()

    override fun schedule(task: Task) {
        check(!failSchedule) { "fallo simulado" }
        scheduled += task
    }

    override fun cancel(taskId: String) {
        cancelled += taskId
    }

    override fun cancelFollowUp(taskId: String) {
        cancelledFollowUps += taskId
    }

    override fun cancelNotification(taskId: String) {
        cancelledNotifications += taskId
    }
}

/** Equipo guardado en memoria. */
class FakeTeamStore(var names: List<String> = emptyList(), var asked: String? = null) : TeamStore {
    override suspend fun load(): List<String> = names

    override fun save(names: List<String>) {
        this.names = names
    }

    override suspend fun lastAskedDate(): String? = asked

    override fun setLastAskedDate(date: String) {
        asked = date
    }
}
