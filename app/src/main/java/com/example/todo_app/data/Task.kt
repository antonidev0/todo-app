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
    /** Momento del recordatorio en milisegundos (epoch), o null si no tiene. */
    val reminderAt: Long? = null,
    val position: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long? = null,
)

/** Una tarea junto con cuántas subtareas directas tiene y cuántas están hechas. */
data class TaskWithCounts(
    @Embedded val task: Task,
    @ColumnInfo(name = "childCount") val childCount: Int,
    @ColumnInfo(name = "doneChildCount") val doneChildCount: Int,
)
