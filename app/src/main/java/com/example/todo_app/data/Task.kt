package com.example.todo_app.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Una tarea. Las subtareas son tareas normales con [parentId] apuntando a su tarea padre,
 * así que se pueden anidar sin límite de niveles.
 *
 * Pensado para sincronizar más adelante (por ejemplo con el reloj Garmin):
 *  - [id] es un UUID en texto, único en cualquier dispositivo.
 *  - [updatedAt] guarda la última modificación, para saber qué versión es la más nueva.
 *  - [deletedAt] marca la tarea como borrada en lugar de eliminarla, para que el borrado
 *    también se pueda sincronizar.
 */
@Entity(
    tableName = "tasks",
    indices = [Index("parentId")]
)
data class Task(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val parentId: String? = null,
    val title: String,
    val notes: String = "",
    val done: Boolean = false,
    val completedAt: Long? = null,
    /**
     * Momento del próximo recordatorio en milisegundos (epoch), o null si no tiene.
     * En las tareas que se repiten es siempre la próxima vez que toca.
     */
    val reminderAt: Long? = null,
    /**
     * Días de la semana en que se repite, como bits: lunes = 1, martes = 2, miércoles = 4...
     * domingo = 64. 0 = no se repite. Ver [Recurrence].
     */
    @ColumnInfo(defaultValue = "0") val repeatDays: Int = 0,
    /** Minutos que se calcula que lleva la tarea (para repartir el trabajo), o null. */
    val minutes: Int? = null,
    /** Persona que tiene que hacerla, o null si no está asignada. */
    val assignee: String? = null,
    /** Orden dentro del día: [Priority.FIRST], [Priority.NORMAL] o [Priority.LAST]. */
    @ColumnInfo(defaultValue = "1") val priority: Int = Priority.NORMAL,
    /**
     * Día de una tarea que no se repite, como "2026-11-23", o null si no tiene día.
     * Las que se repiten usan [repeatDays] y dejan esto en null.
     */
    val date: String? = null,
    val position: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long? = null,
)

val Task.isRecurring: Boolean get() = repeatDays != 0 && reminderAt != null

/** Una tarea junto con cuántas subtareas directas tiene y cuántas están hechas. */
data class TaskWithCounts(
    @Embedded val task: Task,
    @ColumnInfo(name = "childCount") val childCount: Int,
    @ColumnInfo(name = "doneChildCount") val doneChildCount: Int,
)

/** Prioridad de una tarea dentro del día, aunque tenga hora. */
object Priority {
    const val FIRST = 0
    const val NORMAL = 1
    const val LAST = 2
}

/**
 * Una tarea marcada como hecha en un día concreto. Se usa en las tareas que se
 * repiten (y sus subtareas): hacerla el lunes no la marca para el martes.
 */
@Entity(tableName = "completions", primaryKeys = ["taskId", "date"])
data class Completion(
    val taskId: String,
    /** Día en formato "2026-11-23". */
    val date: String,
    val completedAt: Long = System.currentTimeMillis(),
)

/** Lo que se rellena en el formulario de una tarea. */
data class TaskForm(
    val title: String,
    val reminderAt: Long? = null,
    val repeatDays: Int = 0,
    val minutes: Int? = null,
    val priority: Int = Priority.NORMAL,
    /** Día de una tarea que no se repite ("2026-11-23"), o null. */
    val date: String? = null,
)
