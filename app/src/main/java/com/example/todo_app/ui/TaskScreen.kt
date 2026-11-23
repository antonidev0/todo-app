package com.example.todo_app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Surface
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
import com.example.todo_app.data.Priority
import com.example.todo_app.data.TaskForm
import com.example.todo_app.ui.theme.TodoappTheme
import java.time.LocalDate

/** Une la pantalla con el ViewModel. Toda la interfaz está en [TaskContent]. */
@Composable
fun TaskScreen(vm: TaskViewModel, topBanner: @Composable () -> Unit = {}) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    TaskContent(
        state = state,
        topBanner = topBanner,
        onSelectDate = vm::selectDate,
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
        onAnswerTeamToday = vm::answerTeamToday,
        onSkipTeamToday = vm::skipTeamToday,
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
    onSelectDate: (LocalDate?) -> Unit,
    onOpen: (Task) -> Unit,
    onBack: () -> Unit,
    onToggle: (TaskItem) -> Unit,
    onAdd: (TaskForm) -> Unit,
    onEdit: (Task, TaskForm) -> Unit,
    onDelete: (Task) -> Unit,
    onSplit: (allNames: List<String>, present: List<String>) -> Unit,
    onAnswerTeamToday: (allNames: List<String>, present: List<String>) -> Unit,
    onSkipTeamToday: () -> Unit,
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
                        text = state.current?.title ?: dayTitle(state.selectedDate, state.today),
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
                    if (state.canSplit) {
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
            if (!state.canGoBack) {
                DayStrip(selected = state.selectedDate, today = state.today, onSelect = onSelectDate)
            }
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
                        text = when {
                            state.canGoBack -> "Esta tarea no tiene subtareas.\nPulsa «+ Subtarea» para añadir una."
                            state.selectedDate == null -> "No hay tareas sin día."
                            else -> "No hay tareas este día.\nPulsa «+ Tarea» para añadir una."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(state.tasks, key = { it.task.id }) { item ->
                        TaskRow(
                            item = item,
                            onOpen = { onOpen(item.task) },
                            onToggle = { onToggle(item) },
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
        val isSubtask = if (task != null) task.parentId != null else state.canGoBack
        TaskDialog(
            heading = when {
                task != null -> "Editar tarea"
                state.canGoBack -> "Nueva subtarea"
                else -> "Nueva tarea"
            },
            initial = task?.let {
                TaskForm(it.title, it.reminderAt, it.repeatDays, it.minutes, it.priority, it.date)
            } ?: TaskForm(title = "", date = if (isSubtask) null else state.selectedDate?.toString()),
            showDay = !isSubtask,
            onDismiss = { editing = null },
            onSave = { form ->
                if (task == null) onAdd(form) else onEdit(task, form)
                editing = null
            },
        )
    }

    if (state.askTeamToday && !choosingTeam) {
        TeamDialog(
            title = "¿Quién trabaja hoy?",
            savedNames = state.team,
            dismissLabel = "Ahora no",
            onDismiss = onSkipTeamToday,
            onSplit = onAnswerTeamToday,
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
            title = state.current?.title ?: dayTitle(state.selectedDate, state.today),
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
    item: TaskItem,
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
        Checkbox(checked = item.done, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (item.done) TextDecoration.LineThrough else null,
                color = if (item.done) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
            )
            val details = buildList {
                when (task.priority) {
                    Priority.FIRST -> add("⬆ Primero")
                    Priority.LAST -> add("⬇ Al final")
                }
                task.assignee?.let { add("👤 $it") }
                task.minutes?.let { add("⏱ $it min") }
                if (item.childCount > 0) add("${item.doneChildCount}/${item.childCount} subtareas")
                val at = task.reminderAt
                when {
                    task.repeatDays != 0 ->
                        add("🔁 " + Recurrence.describe(task.repeatDays) + (at?.let { " · " + formatTime(it) } ?: ""))
                    at != null -> add("⏰ " + formatTime(at))
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

/** Fila de días: Hoy, Mañana, los 5 siguientes y "Sin día". */
@Composable
private fun DayStrip(selected: LocalDate?, today: LocalDate, onSelect: (LocalDate?) -> Unit) {
    val days: List<LocalDate?> = (0L..6L).map { today.plusDays(it) } + listOf(null)
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(days) { day ->
            val isSelected = day == selected
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { onSelect(day) },
            ) {
                Text(
                    dayChipLabel(day, today),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
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
                    TaskItem(Task(id = "1", title = "Cierre", repeatDays = 48, priority = Priority.LAST), false, 4, 1),
                    TaskItem(Task(id = "2", title = "Comprar leche"), true),
                ),
            ),
            onSelectDate = {}, onOpen = {}, onBack = {}, onToggle = {}, onAdd = {},
            onEdit = { _, _ -> }, onDelete = {}, onSplit = { _, _ -> },
            onAnswerTeamToday = { _, _ -> }, onSkipTeamToday = {},
            onCloseSplit = {}, onErrorShown = {},
        )
    }
}
