package com.example.todo_app.ui

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val reminderFormat = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.forLanguageTag("es-ES"))

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

internal fun formatReminder(millis: Long): String =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()).format(reminderFormat)

internal fun formatTime(millis: Long): String =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()).format(timeFormat)

private val spanish = Locale.forLanguageTag("es-ES")
private val shortDay = java.time.format.DateTimeFormatter.ofPattern("EEE d", spanish)
private val longDay = java.time.format.DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", spanish)

/** "Hoy", "Mañana" o "mié 25"; null = "Sin día". */
internal fun dayChipLabel(date: java.time.LocalDate?, today: java.time.LocalDate): String = when (date) {
    null -> "Sin día"
    today -> "Hoy"
    today.plusDays(1) -> "Mañana"
    else -> date.format(shortDay)
}

/** Título de la lista del día: "Hoy, lunes 23 de noviembre". */
internal fun dayTitle(date: java.time.LocalDate?, today: java.time.LocalDate): String = when (date) {
    null -> "Tareas sin día"
    today -> "Hoy, " + date.format(longDay)
    today.plusDays(1) -> "Mañana, " + date.format(longDay)
    else -> date.format(longDay).replaceFirstChar { it.uppercase() }
}
