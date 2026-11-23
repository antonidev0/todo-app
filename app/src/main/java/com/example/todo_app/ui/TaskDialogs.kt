package com.example.todo_app.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.todo_app.data.Recurrence
import com.example.todo_app.data.Splitter
import com.example.todo_app.data.Priority
import com.example.todo_app.data.TaskForm
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun TaskDialog(
    heading: String,
    initial: TaskForm,
    /** false en las subtareas: van con su tarea principal y no tienen día propio. */
    showDay: Boolean,
    onDismiss: () -> Unit,
    onSave: (TaskForm) -> Unit,
) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val initialTime = initial.reminderAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() }

    // rememberSaveable: lo escrito no se pierde al girar el móvil.
    var title by rememberSaveable { mutableStateOf(initial.title) }
    var repeatDays by rememberSaveable { mutableIntStateOf(initial.repeatDays) }
    var minutesText by rememberSaveable { mutableStateOf(initial.minutes?.toString() ?: "") }
    var priority by rememberSaveable { mutableIntStateOf(initial.priority) }
    /** Día de una tarea de una vez, como "2026-11-23", o null. */
    var dateText by rememberSaveable {
        mutableStateOf(initial.date ?: initial.reminderAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toString() })
    }
    /** Hora del aviso en minutos desde medianoche, o -1 si no hay. */
    var timeMinutes by rememberSaveable { mutableIntStateOf(initialTime?.let { it.hour * 60 + it.minute } ?: -1) }

    val repeating = repeatDays != 0
    val date: LocalDate? = dateText?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val time: LocalTime? = if (timeMinutes >= 0) LocalTime.of(timeMinutes / 60, timeMinutes % 60) else null

    fun pickDate() {
        val start = date ?: LocalDate.now()
        DatePickerDialog(
            context,
            { _, year, month, day -> dateText = LocalDate.of(year, month + 1, day).toString() },
            start.year,
            start.monthValue - 1,
            start.dayOfMonth,
        ).show()
    }

    fun pickTime() {
        val start = time ?: LocalTime.now().plusHours(1).withMinute(0)
        TimePickerDialog(
            context,
            { _, hour, minute -> timeMinutes = hour * 60 + minute },
            start.hour,
            start.minute,
            true,
        ).show()
    }

    /** Cuándo avisar, según lo elegido. */
    fun reminderAt(): Long? {
        val t = time ?: return null
        return if (repeating) {
            Recurrence.next(System.currentTimeMillis(), repeatDays, t.hour, t.minute)
        } else {
            // Sin día pero con hora: se entiende que es hoy.
            val d = date ?: LocalDate.now()
            d.atTime(t).atZone(zone).toInstant().toEpochMilli()
        }
    }

    val at = reminderAt()
    val inPast = !repeating && at != null && at <= System.currentTimeMillis()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(heading) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("¿Qué tienes que hacer?") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = minutesText,
                    onValueChange = { new -> minutesText = new.filter { it.isDigit() }.take(4) },
                    label = { Text("Minutos que lleva (opcional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("Prioridad", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        Priority.FIRST to "Primero",
                        Priority.NORMAL to "Normal",
                        Priority.LAST to "Al final",
                    ).forEach { (value, label) ->
                        OptionChip(label, selected = priority == value, onClick = { priority = value })
                    }
                }

                if (showDay) {
                    Text("Repetir los días", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        DayOfWeek.entries.forEach { day ->
                            DayToggle(
                                letter = Recurrence.dayLetters[day.value - 1],
                                selected = Recurrence.has(repeatDays, day),
                                onClick = { repeatDays = repeatDays xor Recurrence.bit(day) },
                            )
                        }
                    }
                    if (repeating) {
                        Text(
                            "🔁 Cada ${Recurrence.describe(repeatDays)}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "📅 " + (date?.let { formatDay(it) } ?: "Sin día"),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { pickDate() }) { Text("Cambiar") }
                            if (date != null) {
                                TextButton(onClick = { dateText = null }) { Text("Quitar") }
                            }
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (time != null) "⏰ Aviso a las " + time.format(HOUR) else "Sin aviso",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { pickTime() }) { Text(if (time == null) "Poner hora" else "Cambiar") }
                    if (time != null) {
                        TextButton(onClick = { timeMinutes = -1 }) { Text("Quitar") }
                    }
                }
                if (inPast) {
                    Text(
                        "Esa hora ya ha pasado, no sonará.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        TaskForm(
                            title = title,
                            reminderAt = reminderAt(),
                            repeatDays = if (showDay) repeatDays else 0,
                            minutes = minutesText.toIntOrNull()?.takeIf { it > 0 },
                            priority = priority,
                            date = if (showDay && !repeating) (date ?: if (time != null) LocalDate.now() else null)?.toString() else null,
                        )
                    )
                },
                enabled = title.isNotBlank(),
            ) {
                Text("Guardar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}

private val HOUR = DateTimeFormatter.ofPattern("HH:mm")
private val DAY = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", Locale.forLanguageTag("es-ES"))

private fun formatDay(date: LocalDate): String = date.format(DAY)

/** Botón redondeado para elegir una opción (prioridad). */
@Composable
private fun OptionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/** Circulito con la letra del día (L, M, X...), marcado o no. */
@Composable
private fun DayToggle(letter: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(34.dp).clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(letter, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * Elegir quién está hoy. Los nombres se guardan para la próxima vez;
 * marcas solo a los que estáis trabajando ahora.
 */
@Composable
internal fun TeamDialog(
    savedNames: List<String>,
    onDismiss: () -> Unit,
    title: String = "¿Quién está hoy?",
    dismissLabel: String = "Cancelar",
    onSplit: (allNames: List<String>, present: List<String>) -> Unit,
) {
    var names by remember { mutableStateOf(savedNames) }
    var present by remember { mutableStateOf(savedNames.toSet()) }
    var newName by rememberSaveable { mutableStateOf("") }

    fun addName() {
        val name = newName.trim()
        if (name.isEmpty()) return
        if (name !in names) names = names + name
        present = present + name
        newName = ""
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                if (names.isEmpty()) {
                    Text(
                        "Añade los nombres del equipo, tú incluido. Se guardan para la próxima vez.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                names.forEach { name ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = name in present,
                            onCheckedChange = { on -> present = if (on) present + name else present - name },
                        )
                        Text(name, modifier = Modifier.weight(1f))
                        TextButton(onClick = { names = names - name; present = present - name }) { Text("✕") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Nombre") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { addName() }, enabled = newName.isNotBlank()) { Text("Añadir") }
                }
                Text(
                    "Las tareas sin minutos cuentan como ${Splitter.DEFAULT_MINUTES} min.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSplit(names, names.filter { it in present }) },
                enabled = names.any { it in present },
            ) {
                Text("Repartir (${names.count { it in present }})")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissLabel) }
        },
    )
}

/** Muestra quién hace qué, con un botón para mandarlo al grupo. */
@Composable
internal fun SplitResultDialog(
    title: String,
    shares: List<Splitter.Share>,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val longest = shares.maxOfOrNull { it.totalMinutes } ?: 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reparto") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text("Terminaréis en unos $longest min.", style = MaterialTheme.typography.bodyMedium)
                shares.forEach { share ->
                    Column {
                        Text(
                            "👤 ${share.person} · ${share.totalMinutes} min",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (share.items.isEmpty()) {
                            Text("   Nada asignado: ayuda a quien vaya más lento", style = MaterialTheme.typography.bodySmall)
                        }
                        share.items.forEach { item ->
                            Text("   • ${item.title} (${item.minutes} min)", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, Splitter.message(title, shares))
                }
                context.startActivity(Intent.createChooser(send, "Compartir reparto"))
            }) { Text("Compartir") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cerrar") }
        },
    )
}
