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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.example.todo_app.data.Task
import com.example.todo_app.data.TaskWithCounts
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
            onDismiss = { editing = null },
            onSave = { title, reminderAt ->
                if (isNew) vm.add(title, reminderAt) else vm.edit(task, title, reminderAt)
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
                task.reminderAt?.let { add("⏰ " + formatReminder(it)) }
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
    onDismiss: () -> Unit,
    onSave: (String, Long?) -> Unit,
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf(initialTitle) }
    var reminderAt by remember { mutableStateOf(initialReminder) }

    fun pickDateTime() {
        val start = reminderAt?.let {
            LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault())
        } ?: LocalDateTime.now().plusHours(1).withMinute(0)
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

    val inPast = reminderAt?.let { it <= System.currentTimeMillis() } == true

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
                Text(
                    text = reminderAt?.let { "⏰ " + formatReminder(it) } ?: "Sin recordatorio",
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
                    TextButton(onClick = { pickDateTime() }) {
                        Text(if (reminderAt == null) "Poner recordatorio" else "Cambiar hora")
                    }
                    if (reminderAt != null) {
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { reminderAt = null }) { Text("Quitar") }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(title, reminderAt) }, enabled = title.isNotBlank()) {
                Text("Guardar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}

private val reminderFormat = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.forLanguageTag("es-ES"))

private fun formatReminder(millis: Long): String =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()).format(reminderFormat)
