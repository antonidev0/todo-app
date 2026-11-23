package com.example.todo_app.assistant

import com.anthropic.client.AnthropicClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.OverloadedException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

/**
 * Conversación con Claude sobre las tareas. Claude decide qué herramientas de
 * [TaskTools] usar (ver, crear, cambiar, marcar hechas o borrar tareas) y contesta en
 * español, en frases cortas para poder leerlas en voz alta.
 *
 * La conversación vive mientras está abierta la pantalla del asistente.
 */
class ClaudeAssistant(
    /** Hace la llamada a la API. En la app es el cliente de Anthropic; en los tests, uno falso. */
    private val createMessage: (MessageCreateParams) -> Message,
    private val tools: TaskTools,
    private val model: AssistantModel = AssistantModel.OPUS,
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : Assistant {
    constructor(client: AnthropicClient, tools: TaskTools, model: AssistantModel) :
        this({ client.messages().create(it) }, tools, model)

    private val history = mutableListOf<MessageParam>()

    /** Manda [text] y devuelve lo que contesta Claude cuando ha terminado de usar las herramientas. */
    override suspend fun ask(text: String): AssistantReply = withContext(io) {
        val start = history.size
        try {
            history += MessageParam.ofUser(AssistantSpec.userText(text, now()))
            var changed = false
            repeat(MAX_STEPS) {
                val response = createMessage(params())
                // Se guarda la respuesta entera (también el razonamiento) para la siguiente vuelta.
                history += response.toParam()

                if (response.stopReason().orElse(null) == StopReason.REFUSAL) {
                    return@withContext AssistantReply("Lo siento, con eso no te puedo ayudar.", changed)
                }
                val uses = response.content().mapNotNull { it.toolUse().orElse(null) }
                if (uses.isEmpty()) {
                    val answer = response.content().mapNotNull { it.text().orElse(null)?.text() }
                        .joinToString(" ").trim()
                    return@withContext AssistantReply(answer.ifEmpty { "Hecho." }, changed)
                }
                val results = uses.map { use ->
                    @Suppress("UNCHECKED_CAST")
                    val input = runCatching { use._input().convert(Map::class.java) as Map<String, Any?> }
                        .getOrDefault(emptyMap())
                    val outcome = tools.run(use.name(), input)
                    if (!outcome.isError && use.name() in TaskTools.WRITES) changed = true
                    ContentBlockParam.ofToolResult(
                        ToolResultBlockParam.builder()
                            .toolUseId(use.id())
                            .content(outcome.text)
                            .isError(outcome.isError)
                            .build()
                    )
                }
                history += MessageParam.ofUserOfContentBlockParams(results)
            }
            AssistantReply(AssistantSpec.TOO_MANY_STEPS, changed)
        } catch (e: Exception) {
            // Si algo falla a medias, se deja la conversación como estaba para poder seguir.
            while (history.size > start) history.removeAt(history.size - 1)
            throw explain(e)
        }
    }

    /** Empieza una conversación nueva. */
    override fun reset() {
        history.clear()
    }

    private fun params(): MessageCreateParams = MessageCreateParams.builder()
        .model(model.id)
        .maxTokens(8000L)
        .system(AssistantSpec.SYSTEM)
        // Respuestas rápidas: para tareas sencillas no hace falta pensar mucho.
        .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
        .apply {
            if (model.serverFallback) {
                // Si el modelo rechaza algo por error, la API reintenta sola con otro modelo.
                putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            }
        }
        .tools(TOOLS.map { com.anthropic.models.messages.ToolUnion.ofTool(it) })
        .messages(history.toList())
        .build()

    private fun explain(e: Exception): Exception {
        // El motivo que da la API, por ejemplo «Your credit balance is too low...».
        val detail = (e as? AnthropicServiceException)?.apiMessage()?.orElse(null).orEmpty()
        val message = when {
            e is AssistantException -> return e
            detail.contains("credit balance", ignoreCase = true) ||
                (e is AnthropicServiceException && e.statusCode() == 402) ->
                "Tu cuenta de Anthropic no tiene saldo. Añade crédito en console.anthropic.com → Billing y vuelve a probar."
            e is UnauthorizedException || e is PermissionDeniedException ->
                "La clave de Claude no es válida. Revísala en los ajustes del asistente (⚙)."
            e is RateLimitException || e is OverloadedException ->
                "Claude está saturado ahora mismo. Prueba otra vez en un momento."
            e is AnthropicIoException -> "No hay conexión a internet."
            e is AnthropicServiceException ->
                "Claude ha dado un error ${e.statusCode()}" + (if (detail.isNotEmpty()) ": $detail" else ". Prueba otra vez.")
            else -> "Algo ha fallado: ${e.message ?: e.javaClass.simpleName}"
        }
        return AssistantException(message, e)
    }

    companion object {
        private const val MAX_STEPS = 10

        /** Las herramientas en el formato de Claude, con `strict` para que los datos cumplan el esquema. */
        val TOOLS: List<Tool> = AssistantSpec.TOOLS.map { spec ->
            Tool.builder()
                .name(spec.name)
                .description(spec.description)
                .inputSchema(
                    Tool.InputSchema.builder()
                        .properties(
                            Tool.InputSchema.Properties.builder()
                                .apply { spec.properties.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) } }
                                .build()
                        )
                        .required(spec.required)
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                        .build()
                )
                .strict(true)
                .build()
        }
    }
}
