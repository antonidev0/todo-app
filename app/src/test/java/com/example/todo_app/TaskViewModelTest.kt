package com.example.todo_app

import androidx.lifecycle.SavedStateHandle
import com.example.todo_app.data.Task
import com.example.todo_app.data.TaskRepository
import com.example.todo_app.ui.TaskUiState
import com.example.todo_app.ui.TaskViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TaskViewModelTest {
    private val dao = FakeTaskDao()
    private val alarms = FakeAlarms()
    private val team = FakeTeamStore(listOf("Ana", "Luis"))
    private val repo = TaskRepository(dao, DirectTransactor, alarms)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(saved: SavedStateHandle = SavedStateHandle()) = TaskViewModel(repo, team, saved)

    /** uiState solo se calcula mientras alguien lo observa, como hace la pantalla. */
    private fun TestScope.observe(state: StateFlow<TaskUiState>): StateFlow<TaskUiState> {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.collect {} }
        return state
    }

    @Test
    fun empiezaCargandoYLuegoEnseñaLasTareas() = runTest {
        dao.insert(Task(id = "a", title = "Comprar pan"))
        val vm = viewModel()
        assertTrue(vm.uiState.value.loading)

        val state = observe(vm.uiState).value
        assertFalse(state.loading)
        assertEquals(listOf("a"), state.tasks.map { it.task.id })
        assertEquals(listOf("Ana", "Luis"), state.team)
    }

    @Test
    fun abrirUnaTareaEnseñaSusSubtareasYSeGuarda() = runTest {
        val padre = Task(id = "padre", title = "Cierre")
        dao.insert(padre)
        dao.insert(Task(id = "hija", parentId = "padre", title = "Barrer"))
        val saved = SavedStateHandle()
        val vm = viewModel(saved)
        val state = observe(vm.uiState)

        vm.open(padre)

        assertTrue(state.value.canGoBack)
        assertEquals("Cierre", state.value.current?.title)
        assertEquals(listOf("hija"), state.value.tasks.map { it.task.id })

        // Si Android cierra la app, un ViewModel nuevo con el mismo estado guardado sigue dentro.
        val restored = observe(viewModel(saved).uiState)
        assertEquals("Cierre", restored.value.current?.title)
    }

    @Test
    fun volverAtrasRegresaALaListaPrincipal() = runTest {
        val padre = Task(id = "padre", title = "Cierre")
        dao.insert(padre)
        val vm = viewModel()
        val state = observe(vm.uiState)

        vm.open(padre)
        vm.goBack()

        assertFalse(state.value.canGoBack)
        assertNull(state.value.current)
    }

    @Test
    fun borrarLaTareaAbiertaSaleDeElla() = runTest {
        val padre = Task(id = "padre", title = "Cierre")
        dao.insert(padre)
        val vm = viewModel()
        val state = observe(vm.uiState)

        vm.open(padre)
        vm.delete(padre)

        assertFalse(state.value.canGoBack)
        assertTrue(state.value.tasks.isEmpty())
    }

    @Test
    fun unFalloAlGuardarSeEnseñaComoMensajeSinCerrarLaApp() = runTest {
        alarms.failSchedule = true
        val vm = viewModel()
        val state = observe(vm.uiState)

        vm.add("Tarea", reminderAt = null, repeatDays = 0, minutes = null)
        assertEquals("No se pudo guardar la tarea", state.value.error)

        vm.errorShown()
        assertNull(state.value.error)
    }

    @Test
    fun repartirEnseñaElRepartoYCerrarloLoQuita() = runTest {
        val padre = Task(id = "padre", title = "Cierre")
        dao.insert(padre)
        dao.insert(Task(id = "hija", parentId = "padre", title = "Barrer", minutes = 10))
        val vm = viewModel()
        val state = observe(vm.uiState)

        vm.open(padre)
        vm.splitWork(listOf("Ana"))
        assertEquals("Ana", state.value.split?.single()?.person)

        vm.closeSplit()
        assertNull(state.value.split)
    }

    @Test
    fun guardarElEquipoQuitaEspaciosYRepetidos() = runTest {
        val vm = viewModel()
        val state = observe(vm.uiState)

        vm.saveTeam(listOf(" Ana ", "Ana", "", "Luis"))

        assertEquals(listOf("Ana", "Luis"), team.names)
        assertEquals(listOf("Ana", "Luis"), state.value.team)
    }
}
