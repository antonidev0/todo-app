package com.example.todo_app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.todo_app.assistant.AssistantModel
import com.example.todo_app.assistant.AssistantUiState
import com.example.todo_app.assistant.Provider
import com.example.todo_app.assistant.AssistantViewModel
import com.example.todo_app.assistant.ChatLine
import java.util.Locale

/** El asistente a pantalla completa: se le habla o se le escribe y contesta en voz alta. */
@Composable
fun AssistantDialog(onDismiss: () -> Unit, onError: (String) -> Unit) {
    val vm: AssistantViewModel = viewModel(factory = AssistantViewModel.Factory)
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Voz del móvil para leer las respuestas.
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine?.language = Locale.getDefault().takeIf { it.language == "es" } ?: Locale.forLanguageTag("es-ES")
                tts = engine
            }
        }
        onDispose {
            engine?.stop()
            engine?.shutdown()
            tts = null
        }
    }
    LaunchedEffect(state.toSpeak, tts) {
        val line = state.toSpeak ?: return@LaunchedEffect
        tts?.speak(line.text, TextToSpeech.QUEUE_FLUSH, null, "respuesta-${line.id}")
        vm.spoken()
    }

    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(vm::send)
    }
    val listen = {
        tts?.stop()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().takeIf { it.language == "es" }?.toLanguageTag() ?: "es-ES")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Dime qué necesitas")
        try {
            voice.launch(intent)
        } catch (e: ActivityNotFoundException) {
            onError("Este móvil no tiene reconocimiento de voz. Instala o activa la app de Google.")
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        AssistantContent(
            state = state,
            onClose = onDismiss,
            onSend = vm::send,
            onListen = listen,
            onToggleSpeak = { tts?.stop(); vm.toggleSpeak() },
            onNewConversation = { tts?.stop(); vm.newConversation() },
            onSaveKey = vm::saveKey,
            onRemoveKey = vm::removeKey,
            onChooseModel = vm::chooseModel,
            onOpenUrl = { url ->
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssistantContent(
    state: AssistantUiState,
    onClose: () -> Unit,
    onSend: (String) -> Unit,
    onListen: () -> Unit,
    onToggleSpeak: () -> Unit,
    onNewConversation: () -> Unit,
    onSaveKey: (String) -> Unit,
    onRemoveKey: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onChooseModel: (AssistantModel) -> Unit,
) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(if (state.hasKey) "Asistente · ${state.model.label}" else "Asistente") },
                navigationIcon = { TextButton(onClick = onClose) { Text("✕") } },
                actions = {
                    if (state.hasKey) {
                        TextButton(onClick = onToggleSpeak) { Text(if (state.speak) "🔊" else "🔇") }
                        TextButton(onClick = onNewConversation) { Text("Nueva") }
                        TextButton(onClick = { showSettings = !showSettings }) { Text("⚙") }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                !state.hasKey -> KeySetup(state.model, onChooseModel, onSaveKey, onOpenUrl)
                showSettings -> Settings(
                    model = state.model,
                    onChooseModel = onChooseModel,
                    onRemoveKey = { onRemoveKey(); showSettings = false },
                    onBack = { showSettings = false },
                )
                else -> Chat(state, onSend, onListen)
            }
        }
    }
}

@Composable
private fun Chat(state: AssistantUiState, onSend: (String) -> Unit, onListen: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(state.lines.size) {
        if (state.lines.isNotEmpty()) listState.animateScrollToItem(state.lines.size - 1)
    }
    Column(Modifier.fillMaxSize()) {
        if (state.lines.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Pulsa 🎤 y dime, por ejemplo:\n«¿Qué tengo mañana?»\n«Añade comprar pan a las seis»\n«Ya he llamado a Luis»",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            ) {
                items(state.lines, key = { it.id }) { Bubble(it) }
            }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Escribe o pulsa 🎤") },
                modifier = Modifier.weight(1f),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (!state.busy) { onSend(text); text = "" }
                }),
            )
            if (text.isBlank()) {
                Button(onClick = onListen, enabled = !state.busy) { Text("🎤") }
            } else {
                Button(onClick = { onSend(text); text = "" }, enabled = !state.busy) { Text("Enviar") }
            }
        }
    }
}

@Composable
private fun Bubble(line: ChatLine) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (line.fromUser) Arrangement.End else Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = when {
                line.isError -> MaterialTheme.colorScheme.errorContainer
                line.fromUser -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Text(line.text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** Lista para elegir el modelo; los de Gemini son gratis. */
@Composable
private fun ModelPicker(model: AssistantModel, onChooseModel: (AssistantModel) -> Unit) {
    Text("Modelo", style = MaterialTheme.typography.titleMedium)
    AssistantModel.entries.forEach { option ->
        Row(
            Modifier.fillMaxWidth().selectable(selected = option == model, onClick = { onChooseModel(option) }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = option == model, onClick = { onChooseModel(option) })
            Column(Modifier.padding(start = 8.dp)) {
                Text(option.label, style = MaterialTheme.typography.bodyLarge)
                Text(option.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun KeySetup(
    model: AssistantModel,
    onChooseModel: (AssistantModel) -> Unit,
    onSaveKey: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    var key by rememberSaveable(model.provider) { mutableStateOf("") }
    val provider = model.provider
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ModelPicker(model, onChooseModel)
        Text("Conectar con ${provider.label}", style = MaterialTheme.typography.titleLarge)
        Text(
            when (provider) {
                Provider.GEMINI ->
                    "Gemini es gratis. Entra en aistudio.google.com con tu cuenta de Google, pulsa «Create API key», " +
                        "copia la clave y pégala aquí. No hace falta tarjeta. Ten en cuenta que, en la versión gratis, " +
                        "Google puede usar lo que le dices para mejorar sus productos."
                Provider.CLAUDE ->
                    "Claude es de pago. Crea una clave en console.anthropic.com (Settings → API keys), añade saldo en " +
                        "Billing y pégala aquí. Es aparte de la suscripción de Claude."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "La clave se guarda solo en este móvil y no se incluye en las copias de seguridad.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { onOpenUrl(provider.keyUrl) }) { Text("Abrir la página de claves") }
        OutlinedTextField(
            value = key,
            onValueChange = { key = it.trim() },
            label = { Text("Clave (empieza por ${provider.keyPrefix})") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { onSaveKey(key) }, enabled = key.startsWith(provider.keyPrefix)) { Text("Guardar") }
    }
}

@Composable
private fun Settings(
    model: AssistantModel,
    onChooseModel: (AssistantModel) -> Unit,
    onRemoveKey: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ModelPicker(model, onChooseModel)
        Text("Al cambiar de modelo empieza una conversación nueva.", style = MaterialTheme.typography.bodySmall)
        Text("Clave de ${model.provider.label}", style = MaterialTheme.typography.titleMedium)
        Button(onClick = onRemoveKey) { Text("Quitar la clave") }
        TextButton(onClick = onBack) { Text("Volver") }
    }
}
