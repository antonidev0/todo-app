package com.example.todo_app

import com.example.todo_app.data.Priority
import com.example.todo_app.data.Task
import com.example.todo_app.data.TaskForm
import com.example.todo_app.data.TaskRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TaskRepositoryTest {
    private val dao = FakeTaskDao()
    private val alarms = FakeAlarms()
    private val repo = TaskRepository(dao, DirectTransactor, alarms)

    private fun task(id: String, parentId: String? = null, done: Boolean = false, minutes: Int? = null) =
        Task(id = id, parentId = parentId, title = id, done = done, minutes = minutes)

    @Test
    fun editarNoDeshaceLoMarcadoMientrasElDialogoEstabaAbierto() = runBlocking {
        dao.insert(task("a"))
        // Se abre el diálogo con la tarea pendiente; mientras, se marca hecha desde la notificación.
        repo.setDone("a", true)
        repo.editTask("a", TaskForm("  Nuevo título ", minutes = 5))

        val saved = dao.all.getValue("a")
        assertTrue(saved.done)
        assertNotNull(saved.completedAt)
        assertEquals("Nuevo título", saved.title)
        assertEquals(5, saved.minutes)
    }

    @Test
    fun editarConservaLaPersonaAsignada() = runBlocking {
        dao.insert(task("padre"))
        dao.insert(task("hija", parentId = "padre"))
        repo.splitWork("padre", null, listOf("Ana"))
        repo.editTask("hija", TaskForm("hija"))

        assertEquals("Ana", dao.all.getValue("hija").assignee)
    }

    @Test
    fun editarUnaTareaQueYaNoExisteNoHaceNada() = runBlocking {
        repo.editTask("no-existe", TaskForm("x"))
        assertTrue(dao.all.isEmpty())
        assertTrue(alarms.scheduled.isEmpty())
    }

    @Test
    fun unaTareaPuedeRepetirseSinHoraYNoTieneDiaFijo() = runBlocking {
        repo.addTask(null, TaskForm("Tarea", repeatDays = 0b1111111, date = "2026-11-23"))
        val saved = dao.all.values.single()
        assertEquals(0b1111111, saved.repeatDays)
        assertNull(saved.date)
    }

    @Test
    fun unaTareaDeUnaVezConHoraVaElDiaDeEsaHora() = runBlocking {
        val at = LocalDate.of(2026, 11, 25).atTime(16, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        repo.addTask(null, TaskForm("Prenotaciones", reminderAt = at, date = "2026-11-23"))
        assertEquals("2026-11-25", dao.all.values.single().date)
    }

    @Test
    fun lasTareasNuevasVanAlFinal() = runBlocking {
        repo.addTask(null, TaskForm("Primera"))
        repo.addTask(null, TaskForm("Segunda"))
        val byTitle = dao.all.values.associateBy { it.title }
        assertEquals(0, byTitle.getValue("Primera").position)
        assertEquals(1, byTitle.getValue("Segunda").position)
    }

    @Test
    fun marcarHechaQuitaElSeguimientoYLaNotificacion() = runBlocking {
        dao.insert(task("a"))
        repo.setDone("a", true)
        assertEquals(listOf("a"), alarms.cancelledFollowUps)
        assertEquals(listOf("a"), alarms.cancelledNotifications)
    }

    @Test
    fun borrarIncluyeLasSubtareasYSusAvisos() = runBlocking {
        dao.insert(task("padre"))
        dao.insert(task("hija", parentId = "padre"))
        dao.insert(task("nieta", parentId = "hija"))
        dao.insert(task("otra"))

        repo.deleteTask("padre")

        val deleted = dao.all.values.filter { it.deletedAt != null }.map { it.id }.toSet()
        assertEquals(setOf("padre", "hija", "nieta"), deleted)
        assertEquals(setOf("padre", "hija", "nieta"), alarms.cancelled.toSet())
        assertEquals(setOf("padre", "hija", "nieta"), alarms.cancelledNotifications.toSet())
        assertNull(dao.all.getValue("otra").deletedAt)
    }

    @Test
    fun enLasQueSeRepitenHechaSoloCuentaParaEseDia() = runBlocking {
        val todosLosDias = 0b1111111
        dao.insert(task("cierre").copy(repeatDays = todosLosDias))
        dao.insert(task("barrer", parentId = "cierre"))
        val lunes = LocalDate.of(2026, 11, 23)
        val martes = lunes.plusDays(1)

        repo.setDoneOn("cierre", lunes, true)
        repo.setDoneOn("barrer", lunes, true)

        assertTrue(repo.isDoneOn("cierre", lunes))
        assertTrue(repo.isDoneOn("barrer", lunes))
        assertFalse(repo.isDoneOn("cierre", martes))
        assertFalse(repo.isDoneOn("barrer", martes))
        // La tarea en sí no queda hecha para siempre.
        assertFalse(dao.all.getValue("cierre").done)

        repo.setDoneOn("cierre", lunes, false)
        assertFalse(repo.isDoneOn("cierre", lunes))
    }

    @Test
    fun enLasDeUnaVezHechaEsParaSiempre() = runBlocking {
        dao.insert(task("a").copy(date = "2026-11-23"))
        repo.setDoneOn("a", LocalDate.of(2026, 11, 23), true)
        assertTrue(dao.all.getValue("a").done)
        assertTrue(dao.allCompletions.isEmpty())
    }

    @Test
    fun nuevaRepeticionPasaALaProximaVez() = runBlocking {
        val lunes = 1
        dao.insert(task("cierre").copy(repeatDays = lunes, reminderAt = 1_000L))

        val next = repo.startNextOccurrence("cierre")

        assertNotNull(next)
        assertTrue(next!!.reminderAt!! > System.currentTimeMillis())
    }

    @Test
    fun repartirGuardaAQuienLeTocaCadaSubtarea() = runBlocking {
        dao.insert(task("padre"))
        dao.insert(task("larga", parentId = "padre", minutes = 30))
        dao.insert(task("corta", parentId = "padre", minutes = 10))
        dao.insert(task("hecha", parentId = "padre", done = true, minutes = 10))

        val shares = repo.splitWork("padre", null, listOf("Ana", "Luis"))

        assertEquals(2, shares.size)
        assertEquals("Ana", dao.all.getValue("larga").assignee)
        assertEquals("Luis", dao.all.getValue("corta").assignee)
        assertNull(dao.all.getValue("hecha").assignee)
    }

    @Test
    fun repartirElDiaIncluyeLasTareasYLasSubtareasPendientes() = runBlocking {
        val lunes = LocalDate.of(2026, 11, 23)
        val todosLosDias = 0b1111111
        dao.insert(task("vasos", minutes = 20).copy(repeatDays = todosLosDias))
        dao.insert(task("azucar", minutes = 5).copy(date = "2026-11-23"))
        dao.insert(task("cierre").copy(repeatDays = todosLosDias))
        dao.insert(task("barrer", parentId = "cierre", minutes = 15))
        dao.insert(task("basura", parentId = "cierre", minutes = 5).copy(priority = Priority.LAST))
        dao.insert(task("otro-dia", minutes = 50).copy(date = "2026-11-24"))
        repo.setDoneOn("azucar", lunes, true)

        val shares = repo.splitWork(null, lunes, listOf("Ana", "Luis"))

        val repartidas = shares.flatMap { s -> s.items.map { it.id } }.toSet()
        // "cierre" se reparte por subtareas; lo hecho y lo de otro día no entra.
        assertEquals(setOf("vasos", "barrer", "basura"), repartidas)
        assertEquals(listOf(20, 20), shares.map { it.totalMinutes }.sorted())
        // La de "Al final" va la última en la lista de quien la tenga.
        val conBasura = shares.single { s -> s.items.any { it.id == "basura" } }
        assertEquals("basura", conBasura.items.last().id)
    }
}
