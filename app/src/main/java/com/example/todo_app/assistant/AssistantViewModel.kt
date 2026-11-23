package com.example.todo_app.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.todo_app.appContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Una línea de la conversación. */
data class ChatLine(val text: String, val fromUser: Boolean, val isError: Boolean = false, val id: Long)

data class AssistantUiState(
    val hasKey: Boolean = false,
    val lines: List<ChatLine> = emptyList(),
    /** true mientras Claude piensa o usa las herramientas. */
    val busy: Boolean = false,
    /** Leer en voz alta las respuestas. */
    val speak: Boolean = true,
    /** Respuesta nueva para leer en voz alta una vez, o null. */
    val toSpeak: ChatLine? = null,
)

class AssistantViewModel(
    private val keyStore: ApiKeyStore,
    /** Crea el asistente con una clave. */
    private val newAssistant: (apiKey: String) -> ClaudeAssistant,
) : ViewModel() {

    private val _state = MutableStateFlow(AssistantUiState(hasKey = keyStore.get() != null))
    val state: StateFlow<AssistantUiState> = _state.asStateFlow()

    private var assistant: ClaudeAssistant? = null
    private var nextId = 0L

    private fun line(text: String, fromUser: Boolean, isError: Boolean = false) =
        ChatLine(text, fromUser, isError, nextId++)

    fun send(text: String) {
        val clean = text.trim()
        if (clean.isEmpty() || _state.value.busy) return
        val key = keyStore.get() ?: return
        val current = assistant ?: newAssistant(key).also { assistant = it }
        _state.update { it.copy(lines = it.lines + line(clean, fromUser = true), busy = true) }
        viewModelScope.launch {
            val reply = try {
                line(current.ask(clean).text, fromUser = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                line(e.message ?: "Algo ha fallado.", fromUser = false, isError = true)
            }
            _state.update { it.copy(lines = it.lines + reply, busy = false, toSpeak = reply.takeIf { _ -> it.speak }) }
        }
    }

    fun spoken() {
        _state.update { it.copy(toSpeak = null) }
    }

    fun toggleSpeak() {
        _state.update { it.copy(speak = !it.speak, toSpeak = null) }
    }

    fun newConversation() {
        assistant?.reset()
        _state.update { it.copy(lines = emptyList(), toSpeak = null) }
    }

    fun saveKey(key: String) {
        if (key.isBlank()) return
        keyStore.set(key)
        assistant = null
        _state.update { it.copy(hasKey = true) }
    }

    fun removeKey() {
        keyStore.set(null)
        assistant = null
        _state.update { AssistantUiState(hasKey = false) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = checkNotNull(this[APPLICATION_KEY]).appContainer
                AssistantViewModel(container.apiKeyStore, container::newAssistant)
            }
        }
    }
}
