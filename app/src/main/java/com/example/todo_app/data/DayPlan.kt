package com.example.todo_app.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Qué tareas tocan cada día y si están hechas ese día. Todo es Kotlin puro
 * (sin Android ni base de datos) para poder probarlo fácilmente.
 *
 * Reglas:
 *  - Una tarea principal que se repite sale los días marcados en [Task.repeatDays].
 *  - Una tarea principal que no se repite sale el día de [Task.date] (o "Sin día" si no tiene).
 *  - Las subtareas van con su tarea principal.
 *  - Si la tarea principal se repite, ella y todas sus subtareas se marcan como hechas
 *    por día ([Completion]); si no, con [Task.done].
 */
class DayPlan(
    tasks: List<Task>,
    completions: List<Completion>,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val live = tasks.filter { it.deletedAt == null }
    private val byId = live.associateBy { it.id }
    private val children = live.groupBy { it.parentId }
    private val done = completions.map { it.taskId to it.date }.toSet()

    fun task(id: String): Task? = byId[id]

    /** Tarea principal (sin padre) de la que cuelga [task]. */
    fun rootOf(task: Task): Task {
        var current = task
        var steps = 0
        while (current.parentId != null && steps < MAX_DEPTH) {
            current = byId[current.parentId] ?: break
            steps++
        }
        return current
    }

    /** true si [task] se marca como hecha por día (su tarea principal se repite). */
    fun isPerDay(task: Task): Boolean = rootOf(task).repeatDays != 0

    fun isDone(task: Task, date: LocalDate?): Boolean =
        if (isPerDay(task) && date != null) (task.id to date.toString()) in done else task.done

    /** Tareas principales de [date] (null = las que no tienen día), ya ordenadas. */
    fun topLevelOn(date: LocalDate?): List<Task> =
        sorted(children[null].orEmpty().filter { occursOn(it, date) }, date)

    /** Subtareas directas de [parentId], ya ordenadas. */
    fun childrenOf(parentId: String, date: LocalDate?): List<Task> =
        sorted(children[parentId].orEmpty(), date)

    fun withCounts(task: Task, date: LocalDate?): TaskWithCounts {
        val kids = children[task.id].orEmpty()
        return TaskWithCounts(task, kids.size, kids.count { isDone(it, date) })
    }

    /**
     * Lo que hay que repartir: las tareas pendientes de la lista; si una tiene
     * subtareas pendientes, se reparten esas subtareas sueltas (a cualquier profundidad).
     */
    fun pendingWork(tasks: List<Task>, date: LocalDate?): List<Task> =
        tasks.filter { !isDone(it, date) }.flatMap { task ->
            val pendingKids = children[task.id].orEmpty().filter { !isDone(it, date) }
            if (pendingKids.isEmpty()) listOf(task) else pendingWork(sorted(pendingKids, date), date)
        }

    private fun occursOn(task: Task, date: LocalDate?): Boolean = when {
        task.repeatDays != 0 -> date != null && Recurrence.has(task.repeatDays, date.dayOfWeek)
        else -> task.date == date?.toString()
    }

    /** Pendientes primero; luego prioridad; luego hora; luego el orden en que se crearon. */
    private fun sorted(list: List<Task>, date: LocalDate?): List<Task> = list.sortedWith(
        compareBy<Task>(
            { isDone(it, date) },
            { it.priority },
            { minuteOfDay(it) ?: Int.MAX_VALUE },
            { it.position },
            { it.createdAt },
        )
    )

    private fun minuteOfDay(task: Task): Int? = task.reminderAt?.let {
        val t = Instant.ofEpochMilli(it).atZone(zone).toLocalTime()
        t.hour * 60 + t.minute
    }

    private companion object {
        /** Por seguridad, por si alguna vez hubiera un ciclo de padres. */
        const val MAX_DEPTH = 50
    }
}
