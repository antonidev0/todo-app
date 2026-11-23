package com.example.todo_app

import com.example.todo_app.data.Recurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId

class RecurrenceTest {
    private val zone = ZoneId.of("Europe/Berlin")

    private fun millis(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    private val mondayAndTuesday = Recurrence.bit(DayOfWeek.MONDAY) or Recurrence.bit(DayOfWeek.TUESDAY)

    @Test
    fun mismoDiaSiLaHoraNoHaPasado() {
        // Lunes 23/11/2026 a las 10:00 -> lunes a las 16:00
        val next = Recurrence.next(millis("2026-11-23T10:00"), mondayAndTuesday, 16, 0, zone)
        assertEquals(millis("2026-11-23T16:00"), next)
    }

    @Test
    fun saltaAlSiguienteDiaMarcado() {
        // Lunes a las 17:00 -> martes a las 16:00
        val next = Recurrence.next(millis("2026-11-23T17:00"), mondayAndTuesday, 16, 0, zone)
        assertEquals(millis("2026-11-24T16:00"), next)
    }

    @Test
    fun daLaVueltaALaSemana() {
        // Martes a las 17:00 -> lunes siguiente a las 16:00
        val next = Recurrence.next(millis("2026-11-24T17:00"), mondayAndTuesday, 16, 0, zone)
        assertEquals(millis("2026-11-30T16:00"), next)
    }

    @Test
    fun soloUnDiaALaMismaHoraEsLaSemanaQueViene() {
        val monday = Recurrence.bit(DayOfWeek.MONDAY)
        val next = Recurrence.next(millis("2026-11-23T16:00"), monday, 16, 0, zone)
        assertEquals(millis("2026-11-30T16:00"), next)
    }

    @Test
    fun sinDiasNoHayProximaVez() {
        assertNull(Recurrence.next(millis("2026-11-23T10:00"), 0, 16, 0, zone))
    }

    @Test
    fun describe() {
        assertEquals("lun, mar", Recurrence.describe(mondayAndTuesday))
        assertEquals("todos los días", Recurrence.describe(0b1111111))
    }
}
