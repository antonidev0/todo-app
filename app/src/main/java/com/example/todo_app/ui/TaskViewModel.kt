package com.example.todo_app.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.todo_app.appContainer
import com.example.todo_app.data.Splitter
import com.example.todo_app.data.Task
import com.example.todo_app.data.TaskRepository
import com.example.todo_app.data.TaskWithCounts
import com.example.todo_app.data.TeamStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class TaskViewModel(
    private val repo: TaskRepository,
    private val teamStore: TeamStore,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    /**
     * Camino de tareas abiertas: vacío = lista principal; el último es el que se ve.
     * Se guarda en [SavedStateHandle] para no volver al principio si Android cierra
     * la app mientras está en segundo plano.
     */
    private val path: StateFlow<List<String>> = savedState.getStateFlow(PATH_KEY, emptyList())

    private fun setPath(newPath: List<String>) {
        savedState[PATH_KEY] = ArrayList(newPath)
    }

    private val currentTask: Flow<Task?> = path
        .flatMapLatest { p -> p.lastOrNull()?.let { repo.observeTask(it) } ?: flowOf(null) }

    /** null mientras se leen las tareas por primera vez. */
    private val tasks: Flow<List<TaskWithCounts>?> = path
        .flatMapLatest { p -> repo.observeChildren(p.lastOrNull()) }
        .map<List<TaskWithCounts>, List<TaskWithCounts>?> { it }
        .onStart { emit(null) }

    /** Nombres guardados del equipo; null hasta que se leen del disco. */
    private val team = MutableStateFlow<List<String>?>(null)

    /** Lo que no viene de la base de datos: reparto y error. */
    private data class Transient(val split: List<Splitter.Share>? = null, val error: String? = null)

    private val transient = MutableStateFlow(Transient())

    val uiState: StateFlow<TaskUiState> = combine(
        path,
        currentTask,
        tasks,
        team.onStart { if (team.value == null) team.value = teamStore.load() },
        transient,
    ) { p, current, list, names, extra ->
        TaskUiState(
            current = current,
            tasks = list.orEmpty(),
            loading = list == null,
            canGoBack = p.isNotEmpty(),
            team = names.orEmpty(),
            split = extra.split,
            error = extra.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TaskUiState())

    fun open(task: Task) {
        setPath(path.value + task.id)
    }

    fun goBack() {
        setPath(path.value.dropLast(1))
    }

    fun errorShown() {
        transient.update { it.copy(error = null) }
    }

    /**
     * Lanza [block] y, si falla, avisa con [message] en lugar de cerrar la app.
     * La cancelación se deja pasar para no romper la concurrencia estructurada.
     */
    private fun launchSafely(message: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                transient.update { it.copy(error = message) }
            }
        }
    }

    fun add(title: String, reminderAt: Long?, repeatDays: Int, minutes: Int?) {
        if (title.isBlank()) return
        val parentId = path.value.lastOrNull()
        launchSafely("No se pudo guardar la tarea") {
            repo.addTask(parentId, title, reminderAt, repeatDays, minutes)
        }
    }

    fun edit(task: Task, title: String, reminderAt: Long?, repeatDays: Int, minutes: Int?) {
        if (title.isBlank()) return
        launchSafely("No se pudieron guardar los cambios") {
            repo.editTask(task.id, title, reminderAt, repeatDays, minutes)
        }
    }

    // ---- Repartir el trabajo entre el equipo ----

    fun saveTeam(names: List<String>) {
        val clean = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        teamStore.save(clean)
        team.value = clean
    }

    /** Reparte las subtareas de la tarea abierta entre [people]. */
    fun splitWork(people: List<String>) {
        val parentId = path.value.lastOrNull() ?: return
        if (people.isEmpty()) return
        launchSafely("No se pudo repartir el trabajo") {
            val shares = repo.splitSubtasks(parentId, people)
            transient.update { it.copy(split = shares) }
        }
    }

    fun closeSplit() {
        transient.update { it.copy(split = null) }
    }

    fun toggleDone(task: Task) {
        launchSafely("No se pudo actualizar la tarea") { repo.setDone(task.id, !task.done) }
    }

    fun delete(task: Task) {
        launchSafely("No se pudo borrar la tarea") {
            repo.deleteTask(task.id)
            // Si estábamos dentro de la tarea borrada, salimos de ella.
            val i = path.value.indexOf(task.id)
            if (i >= 0) setPath(path.value.take(i))
        }
    }

    companion object {
        private const val PATH_KEY = "path"

        /** Crea el ViewModel con las piezas del [com.example.todo_app.AppContainer]. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = checkNotNull(this[APPLICATION_KEY]).appContainer
                TaskViewModel(container.taskRepository, container.teamStore, createSavedStateHandle())
            }
        }
    }
}
