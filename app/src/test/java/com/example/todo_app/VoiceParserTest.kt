package com.example.todo_app

import com.example.todo_app.data.Priority
import com.example.todo_app.data.VoiceParser
import com.example.todo_app.data.VoiceTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class VoiceParserTest {
    private val zone: ZoneId = ZoneOffset.UTC

    /** Lunes 23 de noviembre de 2026, a las 10:00. */
    private val now = LocalDateTime.of(2026, 11, 23, 10, 0)

    private fun parse(text: String, at: LocalDateTime = now): VoiceTask =
        checkNotNull(VoiceParser.parse(text, at, zone)) { "No se entendió: $text" }

    private fun reminder(t: VoiceTask): LocalDateTime? =
        t.form.reminderAt?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), zone) }

    @Test
    fun recuerdameComprarPanMananaALas9() {
        val t = parse("recuérdame comprar pan mañana a las 9")
        assertEquals("Comprar pan", t.form.title)
        assertEquals(LocalDateTime.of(2026, 11, 24, 9, 0), reminder(t))
        assertEquals("2026-11-24", t.form.date)
        assertTrue(t.hasDay)
    }

    @Test
    fun sinDiaNiHora() {
        val t = parse("Llamar a mamá")
        assertEquals("Llamar a mamá", t.form.title)
        assertNull(t.form.reminderAt)
        assertNull(t.form.date)
        assertFalse(t.hasDay)
    }

    @Test
    fun horaConMinutosYTarde() {
        assertEquals(LocalDateTime.of(2026, 11, 23, 17, 30), reminder(parse("ir al dentista a las 5 y media de la tarde")))
        assertEquals(LocalDateTime.of(2026, 11, 23, 21, 15), reminder(parse("tomar la pastilla a las 21:15")))
        assertEquals(LocalDateTime.of(2026, 11, 23, 13, 0), reminder(parse("comer con Ana a la una")))
        assertEquals(LocalDateTime.of(2026, 11, 23, 18, 45), reminder(parse("salir a las 7 menos cuarto de la tarde")))
        assertEquals(LocalDateTime.of(2026, 11, 23, 22, 0), reminder(parse("sacar al perro a las diez de la noche")))
        assertEquals(LocalDateTime.of(2026, 11, 23, 12, 0), reminder(parse("reunión a mediodía")))
    }

    @Test
    fun unaHoraQueYaHaPasadoHoyEsManana() {
        val t = parse("regar las plantas a las 8")
        assertEquals("Regar las plantas", t.form.title)
        assertEquals(LocalDateTime.of(2026, 11, 24, 8, 0), reminder(t))
    }

    @Test
    fun mananaPorLaTarde() {
        val t = parse("mañana por la tarde a las 4 llamar al banco")
        assertEquals("Llamar al banco", t.form.title)
        assertEquals(LocalDateTime.of(2026, 11, 24, 16, 0), reminder(t))
    }

    @Test
    fun dentroDeUnRato() {
        assertEquals(LocalDateTime.of(2026, 11, 23, 10, 20), reminder(parse("apagar el horno en 20 minutos")))
        assertEquals(LocalDateTime.of(2026, 11, 23, 12, 0), reminder(parse("dentro de dos horas mirar el correo")))
        assertEquals(LocalDateTime.of(2026, 11, 23, 10, 30), reminder(parse("en media hora tender la ropa")))
    }

    @Test
    fun diasDeLaSemanaYFechas() {
        assertEquals("2026-11-27", parse("el viernes pagar el alquiler").form.date)
        assertEquals("2026-11-30", parse("el próximo lunes renovar el DNI").form.date)
        assertEquals("2026-11-25", parse("pasado mañana entregar el informe").form.date)
        assertEquals("2026-12-25", parse("comprar regalos para el 25 de diciembre").form.date)
        assertEquals("2026-12-03", parse("pedir cita el día 3").form.date)
        assertEquals("2026-11-23", parse("hoy limpiar la cocina").form.date)
        assertEquals("Pagar el alquiler", parse("el viernes pagar el alquiler").form.title)
    }

    @Test
    fun seRepite() {
        val t = parse("todos los lunes a las 8 sacar la basura")
        assertEquals("Sacar la basura", t.form.title)
        assertEquals(0b0000001, t.form.repeatDays)
        assertNull(t.form.date)
        assertEquals(LocalDateTime.of(2026, 11, 30, 8, 0), reminder(t))

        assertEquals(0b1111111, parse("cada día beber agua").form.repeatDays)
        assertEquals(0b0011111, parse("de lunes a viernes fichar a las 9").form.repeatDays)
        assertEquals(0b0010101, parse("ir al gimnasio los lunes, miércoles y viernes").form.repeatDays)
        assertEquals(0b1100000, parse("los fines de semana hacer la compra").form.repeatDays)
    }

    @Test
    fun prioridadYMinutos() {
        val urgent = parse("urgente llamar al fontanero")
        assertEquals(Priority.FIRST, urgent.form.priority)
        assertEquals("Llamar al fontanero", urgent.form.title)
        assertEquals(Priority.LAST, parse("ordenar el trastero cuando pueda").form.priority)

        val t = parse("estudiar inglés que dura 45 minutos")
        assertEquals(45, t.form.minutes)
        assertEquals("Estudiar inglés", t.form.title)
        assertEquals(90, parse("limpiar el coche de una hora y media").form.minutes)
    }

    @Test
    fun subtareas() {
        val t = parse("hacer la compra mañana: pan, leche y huevos")
        assertEquals("Hacer la compra", t.form.title)
        assertEquals(listOf("Pan", "Leche", "Huevos"), t.subtasks)
        assertEquals("2026-11-24", t.form.date)

        val c = parse("limpiar la casa con subtareas barrer, fregar y quitar el polvo")
        assertEquals("Limpiar la casa", c.form.title)
        assertEquals(listOf("Barrer", "Fregar", "Quitar el polvo"), c.subtasks)
    }

    @Test
    fun quitaLoQueSeLeDiceALaApp() {
        assertEquals("Comprar pilas", parse("añade comprar pilas").form.title)
        assertEquals("Llamar a Luis", parse("pon una tarea de llamar a Luis").form.title)
        assertEquals("Comprar pan", parse("recuérdame que tengo que comprar pan.").form.title)
        assertEquals("Poner la lavadora", parse("poner la lavadora").form.title)
        assertEquals("Pagar la luz", parse("tengo que pagar la luz").form.title)
    }

    @Test
    fun siNoQuedaNadaNoHayTarea() {
        assertNull(VoiceParser.parse("mañana a las 9", now, zone))
        assertNull(VoiceParser.parse("  ", now, zone))
    }
}
