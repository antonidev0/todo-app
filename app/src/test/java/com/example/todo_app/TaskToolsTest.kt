package com.example.todo_app

import com.example.todo_app.assistant.TaskTools
import com.example.todo_app.data.Priority
import com.example.todo_app.data.Recurrence
import com.example.todo_app.data.Task
import com.example.todo_app.data.TaskRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class TaskToolsTest {
    private val dao = FakeTaskDao()
    private val alarms = FakeAlarms()
    private val repo = TaskRepository(dao, DirectTransactor, alarms)
    private val zone = ZoneOffset.UTC

    /** Lunes 23 de noviembre de 2026, a las 10:00. */
    private val now = LocalDateTime.of(2026, 11, 23, 10, 0)
    private val tools = TaskTools(repo, { now }, zone)

    private fun at(millis: Long?) = millis?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), zone) }

    @Test
    fun creaUnaTareaConAvisoYSubtareas() = runBlocking {
        val out = tools.run(
            "add_task",
            mapOf("title" to "Hacer la compra", "date" to "2026-11-24", "time" to "18:30",
                "priority" to "primero", "subtasks" to listOf("Pan", "Leche")),
        )
        assertFalse(out.text, out.isError)
        val parent = dao.all.values.single { it.parentId == null }
        assertEquals("Hacer la compra", parent.title)
        assertEquals("2026-11-24", parent.date)
        assertEquals(LocalDateTime.of(2026, 11, 24, 18, 30), at(parent.reminderAt))
        assertEquals(Priority.FIRST, parent.priority)
        assertEquals(listOf("Pan", "Leche"), dao.all.values.filter { it.parentId == parent.id }.sortedBy { it.position }.map { it.title })
        assertTrue(out.text.contains(parent.id))
    }

    @Test
    fun creaUnaQueSeRepite() = runBlocking {
        tools.run("add_task", mapOf("title" to "Sacar la basura", "time" to "08:00", "repeat_days" to listOf("lunes", "jueves")))
        val t = dao.all.values.single()
        assertEquals(Recurrence.bit(DayOfWeek.MONDAY) or Recurrence.bit(DayOfWeek.THURSDAY), t.repeatDays)
        assertNull(t.date)
        assertEquals(LocalDateTime.of(2026, 11, 26, 8, 0), at(t.reminderAt))
    }

    @Test
    fun listaLasDeHoyConSuId() = runBlocking {
        dao.insert(Task(id = "a", title = "Comprar pan", date = "2026-11-23"))
        dao.insert(Task(id = "b", title = "Otra cosa", date = "2026-11-24"))
        val out = tools.run("list_tasks", emptyMap())
        assertTrue(out.text, out.text.contains("«Comprar pan» (id: a)"))
        assertFalse(out.text.contains("Otra cosa"))
        assertTrue(tools.run("list_tasks", mapOf("date" to "2026-11-24")).text.contains("Otra cosa"))
    }

    @Test
    fun marcaHechaCambiaYBorra() = runBlocking {
        dao.insert(Task(id = "a", title = "Llamar a Luis", date = "2026-11-23"))

        tools.run("set_done", mapOf("task_id" to "a", "done" to true))
        assertTrue(dao.all.getValue("a").done)

        tools.run("update_task", mapOf("task_id" to "a", "title" to "Llamar a Luis y a Ana", "time" to "17:00"))
        val changed = dao.all.getValue("a")
        assertEquals("Llamar a Luis y a Ana", changed.title)
        assertEquals(LocalDateTime.of(2026, 11, 23, 17, 0), at(changed.reminderAt))
        assertTrue("lo hecho no se pierde al cambiarla", changed.done)

        tools.run("delete_task", mapOf("task_id" to "a"))
        assertTrue(tools.run("list_tasks", emptyMap()).text.contains("ninguna"))
    }

    @Test
    fun losDatosMalosSeDevuelvenComoError() = runBlocking {
        assertTrue(tools.run("set_done", mapOf("task_id" to "no-existe", "done" to true)).isError)
        assertTrue(tools.run("add_task", mapOf("title" to "X", "date" to "mañana")).isError)
        assertTrue(tools.run("add_task", emptyMap()).isError)
        assertTrue(tools.run("inventada", emptyMap()).isError)
        assertTrue(dao.all.isEmpty())
    }
}
