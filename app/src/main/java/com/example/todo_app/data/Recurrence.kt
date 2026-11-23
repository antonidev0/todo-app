package com.example.todo_app.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Utilidades para las tareas que se repiten ciertos días de la semana. */
object Recurrence {

    /** Letra corta de cada día, de lunes a domingo. */
    val dayLetters = listOf("L", "M", "X", "J", "V", "S", "D")
    private val dayNames = listOf("lun", "mar", "mié", "jue", "vie", "sáb", "dom")

    fun bit(day: DayOfWeek): Int = 1 shl (day.value - 1)

    fun has(days: Int, day: DayOfWeek): Boolean = days and bit(day) != 0

    /**
     * Próxima vez, estrictamente después de [after], que cae en uno de [days]
     * a la hora [hour]:[minute]. Si [days] es 0 devuelve null.
     */
    fun next(after: Long, days: Int, hour: Int, minute: Int, zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (days == 0) return null
        val start = LocalDateTime.ofInstant(Instant.ofEpochMilli(after), zone).toLocalDate()
        for (offset in 0..7L) {
            val date = start.plusDays(offset)
            if (!has(days, date.dayOfWeek)) continue
            val candidate = date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
            if (candidate > after) return candidate
        }
        return null
    }

    /** Siguiente vez de una tarea que se repite, conservando su hora. */
    fun nextFor(task: Task, after: Long = System.currentTimeMillis()): Long? {
        val at = task.reminderAt ?: return null
        val time = LocalDateTime.ofInstant(Instant.ofEpochMilli(at), ZoneId.systemDefault())
        return next(after, task.repeatDays, time.hour, time.minute)
    }

    /** Texto como "lun, mié, vie" o "todos los días". */
    fun describe(days: Int): String = when (days) {
        0b1111111 -> "todos los días"
        0b0011111 -> "de lunes a viernes"
        0b1100000 -> "fines de semana"
        else -> DayOfWeek.entries.filter { has(days, it) }.joinToString(", ") { dayNames[it.value - 1] }
    }
}
