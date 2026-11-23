package com.example.todo_app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class TaskViewModel(
    private val repo: TaskRepository,
    private val teamStore: TeamStore,
) : ViewModel() {

    /** Camino de tareas abiertas: vacío = lista principal; el último es el que se ve. */
    private val path = MutableStateFlow<List<String>>(emptyList())

    val canGoBack: StateFlow<Boolean> = path.map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Tarea cuyo contenido (subtareas) estamos viendo, o null en la lista principal. */
    val currentTask: StateFlow<Task?> = path
        .flatMapLatest { p -> p.lastOrNull()?.let { repo.observeTask(it) } ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val tasks: StateFlow<List<TaskWithCounts>> = path
        .flatMapLatest { p -> repo.observeChildren(p.lastOrNull()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun open(task: Task) {
        path.value = path.value + task.id
    }

    fun goBack() {
        path.value = path.value.dropLast(1)
    }

    /** Mensaje de error para enseñar al usuario, o null si no hay ninguno. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun errorShown() {
        _error.value = null
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
                _error.value = message
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

    /** Nombres guardados del equipo; null hasta que se leen del disco. */
    private val _team = MutableStateFlow<List<String>?>(null)
    val team: StateFlow<List<String>> = _team
        .onStart { if (_team.value == null) _team.value = teamStore.load() }
        .map { it.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Último reparto hecho, para enseñarlo en pantalla (null = nada que enseñar). */
    private val _split = MutableStateFlow<List<Splitter.Share>?>(null)
    val split: StateFlow<List<Splitter.Share>?> = _split.asStateFlow()

    fun saveTeam(names: List<String>) {
        val clean = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        teamStore.save(clean)
        _team.value = clean
    }

    /** Reparte las subtareas de la tarea abierta entre [people]. */
    fun splitWork(people: List<String>) {
        val parentId = path.value.lastOrNull() ?: return
        if (people.isEmpty()) return
        launchSafely("No se pudo repartir el trabajo") { _split.value = repo.splitSubtasks(parentId, people) }
    }

    fun closeSplit() {
        _split.value = null
    }

    fun toggleDone(task: Task) {
        launchSafely("No se pudo actualizar la tarea") { repo.setDone(task.id, !task.done) }
    }

    fun delete(task: Task) {
        launchSafely("No se pudo borrar la tarea") {
            repo.deleteTask(task.id)
            // Si estábamos dentro de la tarea borrada, salimos de ella.
            val i = path.value.indexOf(task.id)
            if (i >= 0) path.value = path.value.take(i)
        }
    }

    companion object {
        /** Crea el ViewModel con las piezas del [com.example.todo_app.AppContainer]. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = checkNotNull(this[APPLICATION_KEY]).appContainer
                TaskViewModel(container.taskRepository, container.teamStore)
            }
        }
    }
}
