package com.example.todo_app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.todo_app.data.Recurrence
import com.example.todo_app.data.Task
import com.example.todo_app.data.TaskWithCounts
import com.example.todo_app.ui.theme.TodoappTheme

/** Une la pantalla con el ViewModel. Toda la interfaz está en [TaskContent]. */
@Composable
fun TaskScreen(vm: TaskViewModel, topBanner: @Composable () -> Unit = {}) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    TaskContent(
        state = state,
        topBanner = topBanner,
        onOpen = vm::open,
        onBack = vm::goBack,
        onToggle = vm::toggleDone,
        onAdd = vm::add,
        onEdit = vm::edit,
        onDelete = vm::delete,
        onSplit = { allNames, present ->
            vm.saveTeam(allNames)
            vm.splitWork(present)
        },
        onCloseSplit = vm::closeSplit,
        onErrorShown = vm::errorShown,
    )
}

/**
 * La pantalla de tareas sin ViewModel: recibe el estado y avisa de lo que hace
 * el usuario. Así se puede previsualizar y probar con cualquier estado.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskContent(
    state: TaskUiState,
    onOpen: (Task) -> Unit,
    onBack: () -> Unit,
    onToggle: (Task) -> Unit,
    onAdd: (title: String, reminderAt: Long?, repeatDays: Int, minutes: Int?) -> Unit,
    onEdit: (task: Task, title: String, reminderAt: Long?, repeatDays: Int, minutes: Int?) -> Unit,
    onDelete: (Task) -> Unit,
    onSplit: (allNames: List<String>, present: List<String>) -> Unit,
    onCloseSplit: () -> Unit,
    onErrorShown: () -> Unit,
    modifier: Modifier = Modifier,
    topBanner: @Composable () -> Unit = {},
) {
    var editing by remember { mutableStateOf<TaskEditor?>(null) }
    var toDelete by remember { mutableStateOf<Task?>(null) }
    var choosingTeam by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    // Enseña cada error una vez y después lo da por visto.
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            onErrorShown()
        }
    }

    BackHandler(enabled = state.canGoBack, onBack = onBack)

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.current?.title ?: "Mis tareas",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (state.canGoBack) {
                        TextButton(onClick = onBack) { Text("‹ Atrás") }
                    }
                },
                actions = {
                    if (state.canGoBack && state.hasPendingTasks) {
                        TextButton(onClick = { choosingTeam = true }) { Text("👥 Repartir") }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { editing = TaskEditor.New }) {
                Text(if (state.canGoBack) "+ Subtarea" else "+ Tarea")
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            topBanner()
            when {
                // Mientras carga no decimos "No tienes tareas", que sería falso.
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.tasks.isEmpty() -> Box(
                    Modifier.fillMaxSize().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (state.canGoBack) "Esta tarea no tiene subtareas.\nPulsa «+ Subtarea» para añadir una."
                        else "No tienes tareas.\nPulsa «+ Tarea» para añadir la primera.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(state.tasks, key = { it.task.id }) { item ->
                        TaskRow(
                            item = item,
                            onOpen = { onOpen(item.task) },
                            onToggle = { onToggle(item.task) },
                            onEdit = { editing = TaskEditor.Edit(item.task) },
                            onDelete = { toDelete = item.task },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    editing?.let { editor ->
        val task = (editor as? TaskEditor.Edit)?.task
        TaskDialog(
            heading = when {
                task != null -> "Editar tarea"
                state.canGoBack -> "Nueva subtarea"
                else -> "Nueva tarea"
            },
            initialTitle = task?.title.orEmpty(),
            initialReminder = task?.reminderAt,
            initialRepeatDays = task?.repeatDays ?: 0,
            initialMinutes = task?.minutes,
            onDismiss = { editing = null },
            onSave = { title, reminderAt, repeatDays, minutes ->
                if (task == null) onAdd(title, reminderAt, repeatDays, minutes)
                else onEdit(task, title, reminderAt, repeatDays, minutes)
                editing = null
            },
        )
    }

    if (choosingTeam) {
        TeamDialog(
            savedNames = state.team,
            onDismiss = { choosingTeam = false },
            onSplit = { allNames, present ->
                onSplit(allNames, present)
                choosingTeam = false
            },
        )
    }

    state.split?.let { shares ->
        SplitResultDialog(
            title = state.current?.title ?: "Tareas",
            shares = shares,
            onDismiss = onCloseSplit,
        )
    }

    toDelete?.let { task ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("¿Borrar «${task.title}»?") },
            text = { Text("También se borrarán todas sus subtareas.") },
            confirmButton = {
                TextButton(onClick = { onDelete(task); toDelete = null }) { Text("Borrar") }
            },
            dismissButton = {
                TextButton(onClick = { toDelete = null }) { Text("Cancelar") }
            },
        )
    }
}

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
                task.assignee?.let { add("👤 $it") }
                task.minutes?.let { add("⏱ $it min") }
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

@Preview(showBackground = true)
@Composable
private fun TaskContentPreview() {
    TodoappTheme {
        TaskContent(
            state = TaskUiState(
                loading = false,
                tasks = listOf(
                    TaskWithCounts(Task(id = "1", title = "Cierre del viernes", repeatDays = 16, reminderAt = 0L), 4, 1),
                    TaskWithCounts(Task(id = "2", title = "Comprar leche", done = true), 0, 0),
                ),
            ),
            onOpen = {}, onBack = {}, onToggle = {}, onAdd = { _, _, _, _ -> },
            onEdit = { _, _, _, _, _ -> }, onDelete = {}, onSplit = { _, _ -> },
            onCloseSplit = {}, onErrorShown = {},
        )
    }
}
