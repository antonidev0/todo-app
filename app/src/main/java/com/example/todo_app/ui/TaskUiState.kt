package com.example.todo_app.ui

import com.example.todo_app.data.Splitter
import com.example.todo_app.data.Task
import java.time.LocalDate

/** Una fila de la lista: la tarea, si está hecha el día que se ve y sus subtareas. */
data class TaskItem(
    val task: Task,
    val done: Boolean = task.done,
    val childCount: Int = 0,
    val doneChildCount: Int = 0,
)

/** Todo lo que la pantalla de tareas necesita para dibujarse, en un solo valor. */
data class TaskUiState(
    /** Hoy, para poner "Hoy" y "Mañana" en los días. */
    val today: LocalDate = LocalDate.now(),
    /** Día que se está viendo, o null para las tareas sin día. */
    val selectedDate: LocalDate? = today,
    /** Tarea abierta (vemos sus subtareas), o null en la lista del día. */
    val current: Task? = null,
    val tasks: List<TaskItem> = emptyList(),
    /** true mientras se leen las tareas por primera vez. */
    val loading: Boolean = true,
    val canGoBack: Boolean = false,
    /** Nombres guardados del equipo. */
    val team: List<String> = emptyList(),
    /** true para preguntar quién está hoy (una vez al día). */
    val askTeamToday: Boolean = false,
    /** Último reparto hecho, para enseñarlo (null = nada que enseñar). */
    val split: List<Splitter.Share>? = null,
    /** Mensaje de error para enseñar una vez, o null. */
    val error: String? = null,
    /** Aviso para enseñar una vez (por ejemplo, la tarea creada por voz), o null. */
    val notice: Notice? = null,
) {
    val hasPendingTasks: Boolean get() = tasks.any { !it.done }

    /** Se puede repartir dentro de una tarea o en la lista de un día. */
    val canSplit: Boolean get() = hasPendingTasks && (canGoBack || selectedDate != null)
}

/** Qué está editando el usuario en el diálogo de tarea. */
sealed interface TaskEditor {
    data object New : TaskEditor
    data class Edit(val task: Task) : TaskEditor
}

/** Un aviso con opción de deshacer: [undoTaskId] es la tarea que se borraría. */
data class Notice(val text: String, val undoTaskId: String? = null)
