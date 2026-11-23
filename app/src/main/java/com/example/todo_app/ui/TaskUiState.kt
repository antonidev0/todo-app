package com.example.todo_app.ui

import com.example.todo_app.data.Splitter
import com.example.todo_app.data.Task
import com.example.todo_app.data.TaskWithCounts

/** Todo lo que la pantalla de tareas necesita para dibujarse, en un solo valor. */
data class TaskUiState(
    /** Tarea abierta (vemos sus subtareas), o null en la lista principal. */
    val current: Task? = null,
    val tasks: List<TaskWithCounts> = emptyList(),
    /** true mientras se leen las tareas por primera vez. */
    val loading: Boolean = true,
    val canGoBack: Boolean = false,
    /** Nombres guardados del equipo. */
    val team: List<String> = emptyList(),
    /** Último reparto hecho, para enseñarlo (null = nada que enseñar). */
    val split: List<Splitter.Share>? = null,
    /** Mensaje de error para enseñar una vez, o null. */
    val error: String? = null,
) {
    val hasPendingTasks: Boolean get() = tasks.any { !it.task.done }
}

/** Qué está editando el usuario en el diálogo de tarea. */
sealed interface TaskEditor {
    data object New : TaskEditor
    data class Edit(val task: Task) : TaskEditor
}
