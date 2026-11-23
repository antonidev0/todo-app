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
