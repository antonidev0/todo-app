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
import com.example.todo_app.data.DayPlan
import com.example.todo_app.data.Splitter
import com.example.todo_app.data.Task
import com.example.todo_app.data.TaskForm
import com.example.todo_app.data.TaskRepository
import com.example.todo_app.data.TeamStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

class TaskViewModel(
    private val repo: TaskRepository,
    private val teamStore: TeamStore,
    private val savedState: SavedStateHandle,
    /** Se puede cambiar en los tests. */
    private val clock: () -> LocalDate = { LocalDate.now() },
) : ViewModel() {

    /**
     * Camino de tareas abiertas: vacío = lista del día; el último es el que se ve.
     * Se guarda en [SavedStateHandle] para no volver al principio si Android cierra
     * la app mientras está en segundo plano.
     */
    private val path: StateFlow<List<String>> = savedState.getStateFlow(PATH_KEY, emptyList())

    private fun setPath(newPath: List<String>) {
        savedState[PATH_KEY] = ArrayList(newPath)
    }

    /** Día que se ve: "2026-11-23", o "" para las tareas sin día. Empieza en hoy. */
    private val dateKey: StateFlow<String> = savedState.getStateFlow(DATE_KEY, clock().toString())

    private val selectedDate: Flow<LocalDate?> = dateKey.map { it.toDateOrNull() }

    /** null mientras se leen las tareas por primera vez. */
    private val plan: Flow<DayPlan?> = repo.observePlan(clock())
        .map<DayPlan, DayPlan?> { it }
        .onStart { emit(null) }

    /** Nombres guardados del equipo; null hasta que se leen del disco. */
    private val team = MutableStateFlow<List<String>?>(null)

    /** Lo que no viene de la base de datos. */
    private data class Transient(
        val split: List<Splitter.Share>? = null,
        val error: String? = null,
        val askTeamToday: Boolean = false,
    )

    private val transient = MutableStateFlow(Transient())

    /** El día, el camino y las tareas juntos (combine admite como mucho 5 flujos). */
    private data class Location(val date: LocalDate?, val path: List<String>)

    private val location: Flow<Location> = combine(selectedDate, path) { d, p -> Location(d, p) }

    val uiState: StateFlow<TaskUiState> = combine(
        location,
        plan,
        team.onStart { if (team.value == null) team.value = teamStore.load() },
        transient,
    ) { loc, dayPlan, names, extra ->
        val parentId = loc.path.lastOrNull()
        val list = dayPlan?.let {
            val tasks = if (parentId == null) it.topLevelOn(loc.date) else it.childrenOf(parentId, loc.date)
            tasks.map { t ->
                val counts = it.withCounts(t, loc.date)
                TaskItem(t, it.isDone(t, loc.date), counts.childCount, counts.doneChildCount)
            }
        }
        TaskUiState(
            today = clock(),
            selectedDate = loc.date,
            current = parentId?.let { dayPlan?.task(it) },
            tasks = list.orEmpty(),
            loading = list == null,
            canGoBack = loc.path.isNotEmpty(),
            team = names.orEmpty(),
            askTeamToday = extra.askTeamToday,
            split = extra.split,
            error = extra.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TaskUiState(today = clock()))

    init {
        // Una vez al día, al abrir la app, pregunta quién está si hay trabajo que repartir.
        viewModelScope.launch {
            try {
                val today = clock()
                if (teamStore.lastAskedDate() == today.toString()) return@launch
                val firstPlan = plan.filterNotNull().first()
                val work = firstPlan.pendingWork(firstPlan.topLevelOn(today), today)
                if (work.size >= 2) transient.update { it.copy(askTeamToday = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Si no se puede leer, simplemente no se pregunta.
            }
        }
    }

    fun selectDate(date: LocalDate?) {
        savedState[DATE_KEY] = date?.toString() ?: ""
        setPath(emptyList())
    }

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

    private fun currentDate(): LocalDate? = dateKey.value.toDateOrNull()

    fun add(form: TaskForm) {
        if (form.title.isBlank()) return
        val parentId = path.value.lastOrNull()
        launchSafely("No se pudo guardar la tarea") { repo.addTask(parentId, form) }
    }

    fun edit(task: Task, form: TaskForm) {
        if (form.title.isBlank()) return
        launchSafely("No se pudieron guardar los cambios") { repo.editTask(task.id, form) }
    }

    /** Marca o desmarca la tarea para el día que se está viendo. */
    fun toggleDone(item: TaskItem) {
        val date = currentDate() ?: clock()
        launchSafely("No se pudo actualizar la tarea") { repo.setDoneOn(item.task.id, date, !item.done) }
    }

    fun delete(task: Task) {
        launchSafely("No se pudo borrar la tarea") {
            repo.deleteTask(task.id)
            // Si estábamos dentro de la tarea borrada, salimos de ella.
            val i = path.value.indexOf(task.id)
            if (i >= 0) setPath(path.value.take(i))
        }
    }

    // ---- Repartir el trabajo entre el equipo ----

    fun saveTeam(names: List<String>) {
        val clean = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        teamStore.save(clean)
        team.value = clean
    }

    /** Reparte lo que se ve: las tareas del día o las subtareas de la tarea abierta. */
    fun splitWork(people: List<String>) {
        if (people.isEmpty()) return
        val parentId = path.value.lastOrNull()
        val date = currentDate()
        if (parentId == null && date == null) return
        launchSafely("No se pudo repartir el trabajo") {
            val shares = repo.splitWork(parentId, date, people)
            transient.update { it.copy(split = shares) }
        }
    }

    /** Respuesta a "¿Quién está hoy?": reparte las tareas de hoy. */
    fun answerTeamToday(allNames: List<String>, present: List<String>) {
        saveTeam(allNames)
        teamStore.setLastAskedDate(clock().toString())
        transient.update { it.copy(askTeamToday = false) }
        if (present.isEmpty()) return
        launchSafely("No se pudo repartir el trabajo") {
            val shares = repo.splitWork(null, clock(), present)
            transient.update { it.copy(split = shares) }
        }
    }

    /** "Ahora no": no vuelve a preguntar hasta mañana. */
    fun skipTeamToday() {
        teamStore.setLastAskedDate(clock().toString())
        transient.update { it.copy(askTeamToday = false) }
    }

    fun closeSplit() {
        transient.update { it.copy(split = null) }
    }

    companion object {
        private const val PATH_KEY = "path"
        private const val DATE_KEY = "date"

        private fun String.toDateOrNull(): LocalDate? =
            if (isEmpty()) null else runCatching { LocalDate.parse(this) }.getOrNull()

        /** Crea el ViewModel con las piezas del [com.example.todo_app.AppContainer]. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = checkNotNull(this[APPLICATION_KEY]).appContainer
                TaskViewModel(container.taskRepository, container.teamStore, createSavedStateHandle())
            }
        }
    }
}
