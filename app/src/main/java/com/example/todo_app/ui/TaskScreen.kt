package com.example.todo_app.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.todo_app.data.Recurrence
import com.example.todo_app.data.Task
import com.example.todo_app.data.TaskWithCounts
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskScreen(vm: TaskViewModel, topBanner: @Composable () -> Unit = {}) {
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val current by vm.currentTask.collectAsStateWithLifecycle()
    val canGoBack by vm.canGoBack.collectAsStateWithLifecycle()

    // null = cerrado; Task vacía (id "") = nueva; otra = editar.
    var editing by remember { mutableStateOf<Task?>(null) }
    var toDelete by remember { mutableStateOf<Task?>(null) }

    BackHandler(enabled = canGoBack) { vm.goBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = current?.title ?: "Mis tareas",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (canGoBack) {
                        TextButton(onClick = { vm.goBack() }) { Text("‹ Atrás") }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { editing = NEW_TASK }) {
                Text(if (canGoBack) "+ Subtarea" else "+ Tarea")
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            topBanner()
            if (tasks.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (canGoBack) "Esta tarea no tiene subtareas.\nPulsa «+ Subtarea» para añadir una."
                        else "No tienes tareas.\nPulsa «+ Tarea» para añadir la primera.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(tasks, key = { it.task.id }) { item ->
                        TaskRow(
                            item = item,
                            onOpen = { vm.open(item.task) },
                            onToggle = { vm.toggleDone(item.task) },
                            onEdit = { editing = item.task },
                            onDelete = { toDelete = item.task },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    editing?.let { task ->
        val isNew = task.id == NEW_TASK.id
        TaskDialog(
            heading = when {
                !isNew -> "Editar tarea"
                canGoBack -> "Nueva subtarea"
                else -> "Nueva tarea"
            },
            initialTitle = task.title,
            initialReminder = task.reminderAt,
            initialRepeatDays = task.repeatDays,
            onDismiss = { editing = null },
            onSave = { title, reminderAt, repeatDays ->
                if (isNew) vm.add(title, reminderAt, repeatDays)
                else vm.edit(task, title, reminderAt, repeatDays)
                editing = null
            },
        )
    }

    toDelete?.let { task ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("¿Borrar «${task.title}»?") },
            text = { Text("También se borrarán todas sus subtareas.") },
            confirmButton = {
                TextButton(onClick = { vm.delete(task); toDelete = null }) { Text("Borrar") }
            },
            dismissButton = {
                TextButton(onClick = { toDelete = null }) { Text("Cancelar") }
            },
        )
    }
}

private val NEW_TASK = Task(id = "", title = "")

@Composable
private fun TaskRow(
    item: TaskWithCounts,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val task = item.task
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = task.done, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (task.done) TextDecoration.LineThrough else null,
                color = if (task.done) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
            )
            val details = buildList {
                if (item.childCount > 0) add("${item.doneChildCount}/${item.childCount} subtareas")
                val at = task.reminderAt
                when {
                    at != null && task.repeatDays != 0 ->
                        add("🔁 " + Recurrence.describe(task.repeatDays) + " · " + formatTime(at))
                    at != null -> add("⏰ " + formatReminder(at))
                }
            }
            if (details.isNotEmpty()) {
                Text(
                    text = details.joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = "›",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            TextButton(onClick = { menuOpen = true }) { Text("⋮") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Editar") }, onClick = { menuOpen = false; onEdit() })
                DropdownMenuItem(text = { Text("Borrar") }, onClick = { menuOpen = false; onDelete() })
            }
        }
    }
}

@Composable
private fun TaskDialog(
    heading: String,
    initialTitle: String,
    initialReminder: Long?,
    initialRepeatDays: Int,
    onDismiss: () -> Unit,
    onSave: (String, Long?, Int) -> Unit,
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf(initialTitle) }
    var reminderAt by remember { mutableStateOf(initialReminder) }
    var repeatDays by remember { mutableStateOf(initialRepeatDays) }

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
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("¿Qué tienes que hacer?") },
                    singleLine = true,
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
                onClick = { onSave(title, reminderAt, if (reminderAt == null) 0 else repeatDays) },
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

private val reminderFormat = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.forLanguageTag("es-ES"))

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

private fun formatReminder(millis: Long): String =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()).format(reminderFormat)

private fun formatTime(millis: Long): String =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()).format(timeFormat)
