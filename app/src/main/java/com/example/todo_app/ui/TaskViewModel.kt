package com.example.todo_app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.todo_app.data.Splitter
import com.example.todo_app.data.Task
import com.example.todo_app.data.TaskRepository
import com.example.todo_app.data.TaskWithCounts
import com.example.todo_app.data.TeamStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class TaskViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = TaskRepository(app)

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

    val depth: StateFlow<Int> = path.map { it.size }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    fun open(task: Task) {
        path.value = path.value + task.id
    }

    fun goBack() {
        path.value = path.value.dropLast(1)
    }

    fun add(title: String, reminderAt: Long?, repeatDays: Int, minutes: Int?) {
        if (title.isBlank()) return
        val parentId = path.value.lastOrNull()
        viewModelScope.launch { repo.addTask(parentId, title, reminderAt, repeatDays, minutes) }
    }

    fun edit(task: Task, title: String, reminderAt: Long?, repeatDays: Int, minutes: Int?) {
        if (title.isBlank()) return
        viewModelScope.launch { repo.editTask(task.id, title, reminderAt, repeatDays, minutes) }
    }

    // ---- Repartir el trabajo entre el equipo ----

    private val teamStore = TeamStore(app)

    /** Nombres guardados del equipo. */
    private val _team = MutableStateFlow(teamStore.load())
    val team: StateFlow<List<String>> = _team.asStateFlow()

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
        viewModelScope.launch { _split.value = repo.splitSubtasks(parentId, people) }
    }

    fun closeSplit() {
        _split.value = null
    }

    fun toggleDone(task: Task) {
        viewModelScope.launch { repo.setDone(task.id, !task.done) }
    }

    fun delete(task: Task) {
        viewModelScope.launch {
            repo.deleteTask(task.id)
            // Si estábamos dentro de la tarea borrada, salimos de ella.
            val i = path.value.indexOf(task.id)
            if (i >= 0) path.value = path.value.take(i)
        }
    }
}
