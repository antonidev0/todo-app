package com.example.todo_app

import com.example.todo_app.data.Task
import com.example.todo_app.data.TaskRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
        repo.editTask("a", "  Nuevo título ", reminderAt = null, repeatDays = 0, minutes = 5)

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
        repo.splitSubtasks("padre", listOf("Ana"))
        repo.editTask("hija", "hija", reminderAt = null, repeatDays = 0, minutes = null)

        assertEquals("Ana", dao.all.getValue("hija").assignee)
    }

    @Test
    fun editarUnaTareaQueYaNoExisteNoHaceNada() = runBlocking {
        repo.editTask("no-existe", "x", reminderAt = null, repeatDays = 0, minutes = null)
        assertTrue(dao.all.isEmpty())
        assertTrue(alarms.scheduled.isEmpty())
    }

    @Test
    fun sinRecordatorioNoSeRepite() = runBlocking {
        repo.addTask(null, "Tarea", reminderAt = null, repeatDays = 0b1111111, minutes = null)
        assertEquals(0, dao.all.values.single().repeatDays)
    }

    @Test
    fun lasTareasNuevasVanAlFinal() = runBlocking {
        repo.addTask(null, "Primera", null, 0, null)
        repo.addTask(null, "Segunda", null, 0, null)
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
    fun nuevaRepeticionVuelveAEmpezarLasSubtareas() = runBlocking {
        val lunes = 1
        dao.insert(task("cierre", done = true).copy(repeatDays = lunes, reminderAt = 1_000L))
        dao.insert(task("barrer", parentId = "cierre", done = true))

        val next = repo.startNextOccurrence("cierre")

        assertNotNull(next)
        assertFalse(dao.all.getValue("cierre").done)
        assertFalse(dao.all.getValue("barrer").done)
        assertTrue(next!!.reminderAt!! > System.currentTimeMillis())
    }

    @Test
    fun repartirGuardaAQuienLeTocaCadaSubtarea() = runBlocking {
        dao.insert(task("padre"))
        dao.insert(task("larga", parentId = "padre", minutes = 30))
        dao.insert(task("corta", parentId = "padre", minutes = 10))
        dao.insert(task("hecha", parentId = "padre", done = true, minutes = 10))

        val shares = repo.splitSubtasks("padre", listOf("Ana", "Luis"))

        assertEquals(2, shares.size)
        assertEquals("Ana", dao.all.getValue("larga").assignee)
        assertEquals("Luis", dao.all.getValue("corta").assignee)
        assertNull(dao.all.getValue("hecha").assignee)
    }
}
