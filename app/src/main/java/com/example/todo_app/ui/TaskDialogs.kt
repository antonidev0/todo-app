package com.example.todo_app.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

@Composable
internal fun TaskDialog(
    heading: String,
    initialTitle: String,
    initialReminder: Long?,
    initialRepeatDays: Int,
    initialMinutes: Int?,
    onDismiss: () -> Unit,
    onSave: (String, Long?, Int, Int?) -> Unit,
) {
    val context = LocalContext.current
    // rememberSaveable: lo escrito no se pierde al girar el móvil.
    var title by rememberSaveable { mutableStateOf(initialTitle) }
    var reminderAt by rememberSaveable { mutableStateOf(initialReminder) }
    var repeatDays by rememberSaveable { mutableIntStateOf(initialRepeatDays) }
    var minutesText by rememberSaveable { mutableStateOf(initialMinutes?.toString() ?: "") }

    fun currentTime(): LocalDateTime = reminderAt?.let {
        LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault())
    } ?: LocalDateTime.now().plusHours(1).withMinute(0)

    fun nextRepeat(days: Int, hour: Int, minute: Int): Long? =
        Recurrence.next(System.currentTimeMillis(), days, hour, minute)

    /** Tarea de una sola vez: elegir día y hora. */
    fun pickDateTime() {
        val start = currentTime()
        DatePickerDialog(
            context,
            { _, year, month, day ->
                TimePickerDialog(
                    context,
                    { _, hour, minute ->
                        reminderAt = LocalDateTime.of(year, month + 1, day, hour, minute)
                            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    },
                    start.hour,
                    start.minute,
                    true,
                ).show()
            },
            start.year,
            start.monthValue - 1,
            start.dayOfMonth,
        ).apply { datePicker.minDate = System.currentTimeMillis() - 1000 }.show()
    }

    /** Tarea que se repite: solo hace falta la hora. */
    fun pickTime() {
        val start = currentTime()
        TimePickerDialog(
            context,
            { _, hour, minute -> reminderAt = nextRepeat(repeatDays, hour, minute) },
            start.hour,
            start.minute,
            true,
        ).show()
    }

    fun toggleDay(day: DayOfWeek) {
        val newDays = repeatDays xor Recurrence.bit(day)
        val at = reminderAt
        if (newDays != 0 && at != null) {
            val t = currentTime()
            reminderAt = nextRepeat(newDays, t.hour, t.minute)
        }
        repeatDays = newDays
    }

    val repeating = repeatDays != 0
    val inPast = !repeating && reminderAt?.let { it <= System.currentTimeMillis() } == true

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

                Text("Repetir los días", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    DayOfWeek.entries.forEach { day ->
                        DayToggle(
                            letter = Recurrence.dayLetters[day.value - 1],
                            selected = Recurrence.has(repeatDays, day),
                            onClick = { toggleDay(day) },
                        )
                    }
                }

                val at = reminderAt
                Text(
                    text = when {
                        repeating && at == null -> "Elige la hora para que te avise"
                        repeating && at != null -> "🔁 Cada ${Recurrence.describe(repeatDays)} a las " +
                            formatTime(at) + "\nPróxima vez: " + formatReminder(at)
                        at != null -> "⏰ " + formatReminder(at)
                        else -> "Sin recordatorio"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (inPast) {
                    Text(
                        "Esa hora ya ha pasado, no sonará.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row {
                    TextButton(onClick = { if (repeating) pickTime() else pickDateTime() }) {
                        Text(
                            when {
                                reminderAt != null -> "Cambiar hora"
                                repeating -> "Elegir hora"
                                else -> "Poner día y hora"
                            }
                        )
                    }
                    if (reminderAt != null || repeating) {
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { reminderAt = null; repeatDays = 0 }) { Text("Quitar") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        title,
                        reminderAt,
                        if (reminderAt == null) 0 else repeatDays,
                        minutesText.toIntOrNull()?.takeIf { it > 0 },
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
        title = { Text("¿Quién está hoy?") },
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
            TextButton(onClick = onDismiss) { Text("Cancelar") }
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
