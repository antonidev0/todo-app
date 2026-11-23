package com.example.todo_app.assistant

import com.example.todo_app.data.DayPlan
import com.example.todo_app.data.Priority
import com.example.todo_app.data.Recurrence
import com.example.todo_app.data.Task
import com.example.todo_app.data.TaskForm
import com.example.todo_app.data.TaskRepository
import kotlinx.coroutines.flow.first
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Resultado de una herramienta: el texto que se le devuelve a Claude. */
data class ToolOutcome(val text: String, val isError: Boolean = false)

/**
 * Lo que el asistente puede hacer con las tareas. No sabe nada de Claude: recibe el
 * nombre de la herramienta y sus datos ya leídos, y devuelve un texto. Así se prueba
 * sin internet.
 */
class TaskTools(
    private val repo: TaskRepository,
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    suspend fun run(name: String, input: Map<String, Any?>): ToolOutcome = try {
        when (name) {
            LIST_TASKS -> listTasks(input)
            LIST_SUBTASKS -> listSubtasks(input)
            ADD_TASK -> addTask(input)
            UPDATE_TASK -> updateTask(input)
            SET_DONE -> setDone(input)
            DELETE_TASK -> deleteTask(input)
            else -> ToolOutcome("Herramienta desconocida: $name", isError = true)
        }
    } catch (e: BadInput) {
        ToolOutcome(e.message ?: "Datos no válidos", isError = true)
    }

    private class BadInput(message: String) : Exception(message)

    private suspend fun plan(): DayPlan = repo.observePlan(now().toLocalDate()).first()

    private suspend fun listTasks(input: Map<String, Any?>): ToolOutcome {
        val noDate = input["no_date"] == true
        val date = if (noDate) null else input.date("date") ?: now().toLocalDate()
        val p = plan()
        val tasks = p.topLevelOn(date)
        val heading = date?.let { "Tareas del ${it.format(DAY)}" } ?: "Tareas sin día"
        if (tasks.isEmpty()) return ToolOutcome("$heading: ninguna.")
        return ToolOutcome(heading + ":\n" + tasks.joinToString("\n") { describe(p, it, date) })
    }

    private suspend fun listSubtasks(input: Map<String, Any?>): ToolOutcome {
        val p = plan()
        val parent = p.task(input.requireString("task_id")) ?: throw BadInput("No existe esa tarea.")
        val date = input.date("date") ?: now().toLocalDate()
        val children = p.childrenOf(parent.id, date)
        if (children.isEmpty()) return ToolOutcome("«${parent.title}» no tiene subtareas.")
        return ToolOutcome("Subtareas de «${parent.title}»:\n" + children.joinToString("\n") { describe(p, it, date) })
    }

    private suspend fun addTask(input: Map<String, Any?>): ToolOutcome {
        val title = input.requireString("title")
        val parentId = input.string("parent_id")
        if (parentId != null && plan().task(parentId) == null) throw BadInput("No existe la tarea principal $parentId.")
        val repeatDays = if (parentId == null) input.repeatDays() else 0
        val date = if (parentId == null) input.date("date") else null
        val time = input.time("time")
        val form = TaskForm(
            title = title,
            reminderAt = reminderAt(date, time, repeatDays),
            repeatDays = repeatDays,
            minutes = input.int("minutes"),
            priority = input.priority() ?: Priority.NORMAL,
            date = date?.toString(),
        )
        val subtasks = (input["subtasks"] as? List<*>).orEmpty().mapNotNull { (it as? String)?.trim() }
            .filter { it.isNotEmpty() }
        val task = repo.addTask(parentId, form, subtasks)
        return ToolOutcome("Creada: " + describe(plan(), task, task.date?.let { LocalDate.parse(it) }))
    }

    private suspend fun updateTask(input: Map<String, Any?>): ToolOutcome {
        val p = plan()
        val task = p.task(input.requireString("task_id")) ?: throw BadInput("No existe esa tarea.")
        val isSubtask = task.parentId != null
        val oldTime = task.reminderAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() }
        val repeatDays = if (isSubtask) 0 else if (input.containsKey("repeat_days")) input.repeatDays() else task.repeatDays
        val date = when {
            isSubtask -> null
            input.containsKey("date") -> input.date("date")
            input["no_date"] == true -> null
            else -> task.date?.let { LocalDate.parse(it) }
                ?: task.reminderAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        }
        val time = when {
            input["clear_time"] == true -> null
            input.containsKey("time") -> input.time("time")
            else -> oldTime
        }
        val form = TaskForm(
            title = input.string("title") ?: task.title,
            reminderAt = reminderAt(date, time, repeatDays),
            repeatDays = repeatDays,
            minutes = if (input.containsKey("minutes")) input.int("minutes") else task.minutes,
            priority = input.priority() ?: task.priority,
            date = date?.toString(),
        )
        repo.editTask(task.id, form)
        val updated = plan().task(task.id) ?: task
        return ToolOutcome("Cambiada: " + describe(plan(), updated, updated.date?.let { LocalDate.parse(it) }))
    }

    private suspend fun setDone(input: Map<String, Any?>): ToolOutcome {
        val task = plan().task(input.requireString("task_id")) ?: throw BadInput("No existe esa tarea.")
        val done = input["done"] as? Boolean ?: true
        val date = input.date("date") ?: now().toLocalDate()
        repo.setDoneOn(task.id, date, done)
        return ToolOutcome(if (done) "Marcada como hecha: «${task.title}»." else "Marcada como pendiente: «${task.title}».")
    }

    private suspend fun deleteTask(input: Map<String, Any?>): ToolOutcome {
        val task = plan().task(input.requireString("task_id")) ?: throw BadInput("No existe esa tarea.")
        repo.deleteTask(task.id)
        return ToolOutcome("Borrada «${task.title}» y sus subtareas.")
    }

    /** Cuándo avisar: la próxima vez si se repite, o ese día (hoy si no hay día) a esa hora. */
    private fun reminderAt(date: LocalDate?, time: LocalTime?, repeatDays: Int): Long? {
        val t = time ?: return null
        val nowMillis = now().atZone(zone).toInstant().toEpochMilli()
        if (repeatDays != 0) return Recurrence.next(nowMillis, repeatDays, t.hour, t.minute, zone)
        return (date ?: now().toLocalDate()).atTime(t).atZone(zone).toInstant().toEpochMilli()
    }

    /** Una línea por tarea, con todo lo que Claude necesita para hablar de ella. */
    private fun describe(p: DayPlan, task: Task, date: LocalDate?): String {
        val parts = mutableListOf<String>()
        parts += (if (p.isDone(task, date)) "[hecha] " else "[pendiente] ") + "«${task.title}» (id: ${task.id})"
        val at = task.reminderAt?.let { Instant.ofEpochMilli(it).atZone(zone) }
        when {
            task.repeatDays != 0 -> parts += "se repite " + Recurrence.describe(task.repeatDays) +
                (at?.let { " a las " + it.format(HOUR) } ?: "")
            at != null -> parts += "aviso el ${at.toLocalDate().format(DAY)} a las ${at.format(HOUR)}"
            task.date != null -> parts += "día ${LocalDate.parse(task.date).format(DAY)}"
        }
        when (task.priority) {
            Priority.FIRST -> parts += "prioridad: primero"
            Priority.LAST -> parts += "prioridad: al final"
        }
        task.minutes?.let { parts += "$it min" }
        task.assignee?.let { parts += "asignada a $it" }
        val counts = p.withCounts(task, date)
        if (counts.childCount > 0) parts += "${counts.doneChildCount}/${counts.childCount} subtareas hechas"
        return "- " + parts.joinToString(" · ")
    }

    // ---- Leer los datos que manda Claude ----

    private fun Map<String, Any?>.string(key: String): String? = (this[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }

    private fun Map<String, Any?>.requireString(key: String): String = string(key) ?: throw BadInput("Falta $key.")

    private fun Map<String, Any?>.int(key: String): Int? = (this[key] as? Number)?.toInt()?.takeIf { it > 0 }

    private fun Map<String, Any?>.date(key: String): LocalDate? {
        val s = string(key) ?: return null
        return runCatching { LocalDate.parse(s) }.getOrElse { throw BadInput("Fecha no válida: $s (usa AAAA-MM-DD).") }
    }

    private fun Map<String, Any?>.time(key: String): LocalTime? {
        val s = string(key) ?: return null
        return runCatching { LocalTime.parse(if (s.length == 4) "0$s" else s) }
            .getOrElse { throw BadInput("Hora no válida: $s (usa HH:MM).") }
    }

    private fun Map<String, Any?>.priority(): Int? = when (string("priority")) {
        "primero" -> Priority.FIRST
        "normal" -> Priority.NORMAL
        "al_final" -> Priority.LAST
        null -> null
        else -> throw BadInput("Prioridad no válida.")
    }

    private fun Map<String, Any?>.repeatDays(): Int = (this["repeat_days"] as? List<*>).orEmpty().fold(0) { acc, d ->
        val day = DAYS[d as? String] ?: throw BadInput("Día no válido: $d")
        acc or Recurrence.bit(day)
    }

    companion object {
        const val LIST_TASKS = "list_tasks"
        const val LIST_SUBTASKS = "list_subtasks"
        const val ADD_TASK = "add_task"
        const val UPDATE_TASK = "update_task"
        const val SET_DONE = "set_done"
        const val DELETE_TASK = "delete_task"

        /** Herramientas que cambian las tareas (las demás solo las leen). */
        val WRITES = setOf(ADD_TASK, UPDATE_TASK, SET_DONE, DELETE_TASK)

        val DAYS = mapOf(
            "lunes" to DayOfWeek.MONDAY, "martes" to DayOfWeek.TUESDAY, "miercoles" to DayOfWeek.WEDNESDAY,
            "jueves" to DayOfWeek.THURSDAY, "viernes" to DayOfWeek.FRIDAY, "sabado" to DayOfWeek.SATURDAY,
            "domingo" to DayOfWeek.SUNDAY,
        )

        private val spanish = java.util.Locale.forLanguageTag("es-ES")
        private val DAY = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM yyyy", spanish)
        private val HOUR = DateTimeFormatter.ofPattern("HH:mm")
    }
}
