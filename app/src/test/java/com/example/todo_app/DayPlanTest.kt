package com.example.todo_app

import com.example.todo_app.data.Completion
import com.example.todo_app.data.DayPlan
import com.example.todo_app.data.Priority
import com.example.todo_app.data.Recurrence
import com.example.todo_app.data.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

class DayPlanTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val lunes = LocalDate.of(2026, 11, 23)
    private val martes = lunes.plusDays(1)

    private fun at(date: LocalDate, hour: Int) =
        date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun cadaTareaSaleSuDia() {
        val soloLunes = Recurrence.bit(DayOfWeek.MONDAY)
        val plan = DayPlan(
            listOf(
                Task(id = "repite", title = "Prenotaciones", repeatDays = soloLunes),
                Task(id = "una-vez", title = "Azúcar", date = martes.toString()),
                Task(id = "sin-dia", title = "Algún día"),
                Task(id = "hija", parentId = "repite", title = "Subtarea"),
            ),
            emptyList(),
            zone,
        )
        assertEquals(listOf("repite"), plan.topLevelOn(lunes).map { it.id })
        assertEquals(listOf("una-vez"), plan.topLevelOn(martes).map { it.id })
        assertEquals(listOf("sin-dia"), plan.topLevelOn(null).map { it.id })
    }

    @Test
    fun ordenaPorPrioridadYLuegoPorHora() {
        val plan = DayPlan(
            listOf(
                Task(id = "final", title = "Fregar", date = "2026-11-23", priority = Priority.LAST, reminderAt = at(lunes, 9)),
                Task(id = "tarde", title = "Máquina", date = "2026-11-23", reminderAt = at(lunes, 18)),
                Task(id = "pronto", title = "Prenotaciones", date = "2026-11-23", reminderAt = at(lunes, 16)),
                Task(id = "sin-hora", title = "Barrer", date = "2026-11-23"),
                Task(id = "primero", title = "Abrir caja", date = "2026-11-23", priority = Priority.FIRST),
            ),
            emptyList(),
            zone,
        )
        assertEquals(
            listOf("primero", "pronto", "tarde", "sin-hora", "final"),
            plan.topLevelOn(lunes).map { it.id },
        )
    }

    @Test
    fun lasDiariasYSusSubtareasSeMarcanPorDia() {
        val tasks = listOf(
            Task(id = "cierre", title = "Cierre", repeatDays = 0b1111111),
            Task(id = "barrer", parentId = "cierre", title = "Barrer", done = true),
        )
        val plan = DayPlan(tasks, listOf(Completion("cierre", lunes.toString())), zone)
        val cierre = tasks[0]
        val barrer = tasks[1]

        assertTrue(plan.isDone(cierre, lunes))
        assertFalse(plan.isDone(cierre, martes))
        // En una subtarea de una diaria, el "done" viejo ya no cuenta.
        assertFalse(plan.isDone(barrer, lunes))
        // Las hechas van al final de la lista.
        assertEquals(cierre, plan.topLevelOn(lunes).last())
    }
}
