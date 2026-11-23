package com.example.todo_app

import androidx.lifecycle.SavedStateHandle
import com.example.todo_app.data.Task
import com.example.todo_app.data.TaskForm
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
import org.junit.Assert.assertNotNull
import java.time.LocalDate

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

    private val today = LocalDate.of(2026, 11, 23)

    private fun viewModel(saved: SavedStateHandle = SavedStateHandle()) =
        TaskViewModel(repo, team, saved, clock = { today }, now = { today.atTime(10, 0) })

    /** Tarea de hoy, para que salga en la lista del día. */
    private fun todayTask(id: String, title: String, minutes: Int? = null) =
        Task(id = id, title = title, date = today.toString(), minutes = minutes)

    /** uiState solo se calcula mientras alguien lo observa, como hace la pantalla. */
    private fun TestScope.observe(state: StateFlow<TaskUiState>): StateFlow<TaskUiState> {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.collect {} }
        return state
    }

    @Test
    fun empiezaCargandoYLuegoEnseñaLasTareas() = runTest {
        dao.insert(todayTask("a", "Comprar pan"))
        val vm = viewModel()
        assertTrue(vm.uiState.value.loading)

        val state = observe(vm.uiState).value
        assertFalse(state.loading)
        assertEquals(listOf("a"), state.tasks.map { it.task.id })
        assertEquals(listOf("Ana", "Luis"), state.team)
    }

    @Test
    fun abrirUnaTareaEnseñaSusSubtareasYSeGuarda() = runTest {
        val padre = todayTask("padre", "Cierre")
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
        val padre = todayTask("padre", "Cierre")
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
        val padre = todayTask("padre", "Cierre")
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

        vm.add(TaskForm("Tarea"))
        assertEquals("No se pudo guardar la tarea", state.value.error)

        vm.errorShown()
        assertNull(state.value.error)
    }

    @Test
    fun repartirEnseñaElRepartoYCerrarloLoQuita() = runTest {
        val padre = todayTask("padre", "Cierre")
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

    @Test
    fun cadaDiaEnseñaSusTareas() = runTest {
        dao.insert(todayTask("hoy", "Hoy"))
        dao.insert(Task(id = "mañana", title = "Mañana", date = today.plusDays(1).toString()))
        dao.insert(Task(id = "sin-dia", title = "Sin día"))
        val vm = viewModel()
        val state = observe(vm.uiState)

        assertEquals(listOf("hoy"), state.value.tasks.map { it.task.id })
        vm.selectDate(today.plusDays(1))
        assertEquals(listOf("mañana"), state.value.tasks.map { it.task.id })
        vm.selectDate(null)
        assertEquals(listOf("sin-dia"), state.value.tasks.map { it.task.id })
    }

    @Test
    fun marcarHechaUnaTareaDiariaSoloLaMarcaEseDia() = runTest {
        dao.insert(Task(id = "diaria", title = "Limpiar máquina", repeatDays = 0b1111111))
        val vm = viewModel()
        val state = observe(vm.uiState)

        vm.toggleDone(state.value.tasks.single())
        assertTrue(state.value.tasks.single().done)

        vm.selectDate(today.plusDays(1))
        assertFalse(state.value.tasks.single().done)
    }

    @Test
    fun preguntaQuienEstaHoyYRepartirLoQuita() = runTest {
        dao.insert(todayTask("a", "Barrer", minutes = 10))
        dao.insert(todayTask("b", "Vasos", minutes = 10))
        val vm = viewModel()
        val state = observe(vm.uiState)
        assertTrue(state.value.askTeamToday)

        vm.answerTeamToday(listOf("Ana", "Luis"), listOf("Ana", "Luis"))

        assertFalse(state.value.askTeamToday)
        assertEquals(today.toString(), team.asked)
        assertEquals(2, state.value.split?.size)
        // Un ViewModel nuevo el mismo día ya no pregunta.
        assertFalse(observe(viewModel().uiState).value.askTeamToday)
    }

    @Test
    fun noPreguntaSiNoHayTrabajoQueRepartir() = runTest {
        dao.insert(todayTask("a", "Solo una"))
        val state = observe(viewModel().uiState)
        assertFalse(state.value.askTeamToday)
    }

    // ---- Añadir hablando ----

    @Test
    fun porVozSinDiaVaAlDiaQueSeVe() = runTest {
        val vm = viewModel()
        val state = observe(vm.uiState)

        vm.addFromVoice("recuérdame comprar pan")

        val saved = dao.all.values.single()
        assertEquals("Comprar pan", saved.title)
        assertEquals(today.toString(), saved.date)
        assertEquals(listOf("Comprar pan"), state.value.tasks.map { it.task.title })
        assertEquals("Añadida «Comprar pan» · hoy", state.value.notice?.text)
    }

    @Test
    fun porVozConDiaYSubtareasSeVaAEseDia() = runTest {
        val vm = viewModel()
        val state = observe(vm.uiState)

        vm.addFromVoice("hacer la compra mañana a las 9: pan y leche")

        val parent = dao.all.values.single { it.parentId == null }
        assertEquals("Hacer la compra", parent.title)
        assertEquals(today.plusDays(1).toString(), parent.date)
        assertNotNull(parent.reminderAt)
        assertEquals(listOf("Pan", "Leche"), dao.all.values.filter { it.parentId == parent.id }.sortedBy { it.position }.map { it.title })
        assertEquals(today.plusDays(1), state.value.selectedDate)
        assertEquals("Añadida «Hacer la compra» · mañana a las 09:00 · 2 subtareas", state.value.notice?.text)
        assertTrue(parent.id in alarms.scheduled.map { it.id })
    }

    @Test
    fun porVozDentroDeUnaTareaCreaUnaSubtarea() = runTest {
        val padre = todayTask("padre", "Cierre")
        dao.insert(padre)
        val vm = viewModel()
        val state = observe(vm.uiState)
        vm.open(padre)

        vm.addFromVoice("mañana barrer la terraza")

        val child = dao.all.values.single { it.parentId == "padre" }
        assertEquals("Barrer la terraza", child.title)
        assertNull(child.date)
        assertEquals(listOf("Barrer la terraza"), state.value.tasks.map { it.task.title })
    }

    @Test
    fun deshacerLoDichoPorVozLoBorra() = runTest {
        val vm = viewModel()
        val state = observe(vm.uiState)
        vm.addFromVoice("llamar a Luis")

        vm.undoVoice(checkNotNull(state.value.notice?.undoTaskId))
        vm.noticeShown()

        assertTrue(state.value.tasks.isEmpty())
        assertNull(state.value.notice)
    }

    @Test
    fun siNoSeEntiendeNadaAvisa() = runTest {
        val vm = viewModel()
        val state = observe(vm.uiState)

        vm.addFromVoice("mañana a las 9")

        assertTrue(dao.all.isEmpty())
        assertEquals("No he entendido qué añadir: «mañana a las 9»", state.value.error)
    }
}
