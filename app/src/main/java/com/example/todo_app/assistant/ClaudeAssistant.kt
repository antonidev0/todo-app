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
import java.time.format.DateTimeFormatter
import java.util.Locale

/** La respuesta del asistente, y si ha cambiado alguna tarea. */
data class AssistantReply(val text: String, val changedTasks: Boolean = false)

/**
 * Los modelos de Claude que se pueden elegir en los ajustes, del más barato al más listo.
 * Precios por millón de tokens (entrada / salida); una pregunta normal usa unos pocos miles.
 */
enum class AssistantModel(
    val id: String,
    val label: String,
    val description: String,
    /** Haiku no tiene reintento automático en el servidor cuando rechaza algo. */
    val serverFallback: Boolean,
) {
    HAIKU("claude-haiku-5-5", "Haiku 5.5", "El más rápido y barato (unos 0,1 céntimos por pregunta). Va sobrado para tareas sencillas.", false),
    SONNET("claude-sonnet-5-5", "Sonnet 5.5", "Término medio (alrededor de 1 céntimo por pregunta).", true),
    OPUS("claude-opus-5-5", "Opus 5.5", "El más listo y el más caro (unos 2 céntimos por pregunta).", true);

    companion object {
        val DEFAULT = OPUS

        fun fromId(id: String?): AssistantModel = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** Un fallo que se puede enseñar tal cual a la persona. */
class AssistantException(message: String, cause: Throwable? = null) : Exception(message, cause)

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
    private val model: AssistantModel = AssistantModel.DEFAULT,
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(client: AnthropicClient, tools: TaskTools, model: AssistantModel) :
        this({ client.messages().create(it) }, tools, model)

    private val history = mutableListOf<MessageParam>()

    /** Manda [text] y devuelve lo que contesta Claude cuando ha terminado de usar las herramientas. */
    suspend fun ask(text: String): AssistantReply = withContext(io) {
        val start = history.size
        try {
            history += MessageParam.ofUser("(Ahora es ${now().format(NOW)}.)\n$text")
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
            AssistantReply("Me he liado con tantos pasos. ¿Me lo pides otra vez más sencillo?", changed)
        } catch (e: Exception) {
            // Si algo falla a medias, se deja la conversación como estaba para poder seguir.
            while (history.size > start) history.removeAt(history.size - 1)
            throw explain(e)
        }
    }

    /** Empieza una conversación nueva. */
    fun reset() {
        history.clear()
    }

    private fun params(): MessageCreateParams = MessageCreateParams.builder()
        .model(model.id)
        .maxTokens(8000L)
        .system(SYSTEM)
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

    private fun explain(e: Exception): Exception = when (e) {
        is AssistantException -> e
        is UnauthorizedException, is PermissionDeniedException ->
            AssistantException("La clave de Claude no es válida. Revísala en los ajustes del asistente.", e)
        is RateLimitException, is OverloadedException ->
            AssistantException("Claude está saturado ahora mismo. Prueba otra vez en un momento.", e)
        is AnthropicIoException -> AssistantException("No hay conexión a internet.", e)
        is AnthropicServiceException if e.statusCode() == 402 ->
            AssistantException("Tu cuenta de Anthropic no tiene saldo. Añade crédito en console.anthropic.com.", e)
        is AnthropicServiceException -> AssistantException("Claude ha dado un error (${e.statusCode()}). Prueba otra vez.", e)
        else -> AssistantException("Algo ha fallado: ${e.message ?: e.javaClass.simpleName}", e)
    }

    companion object {
        private const val MAX_STEPS = 10

        private val NOW = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM 'de' yyyy, HH:mm", Locale.forLanguageTag("es-ES"))

        private val SYSTEM = """
            Eres el asistente de una app de tareas y recordatorios en un móvil Android. Hablas español.
            La persona te habla por voz o por escrito, y tu respuesta se lee en voz alta, así que:
            - Contesta en una o dos frases cortas y naturales. Nada de listas, asteriscos, emojis ni Markdown.
            - Las horas dilas como «a las nueve y media», no «09:30». Los días como «mañana» o «el viernes 27».

            Usa las herramientas para ver y cambiar las tareas; nunca te inventes tareas ni ids.
            - Para cambiar, completar o borrar una tarea, primero búscala con list_tasks (o list_subtasks) para saber su id.
            - Si no se dice día, «hoy» es la fecha que va al principio de cada mensaje.
            - «Recuérdame...» o una hora significan poner aviso a esa hora.
            - Si la persona dice una hora sin mañana o tarde, elige la que tenga más sentido (normalmente la próxima que no haya pasado).
            - Borra solo si la persona lo pide claramente. Si hay dudas de qué tarea es, pregunta.
            - Después de hacer algo, dilo en pocas palabras («Hecho, te aviso mañana a las nueve»).
            Si te piden algo que no tiene que ver con las tareas, responde brevemente y con amabilidad.
        """.trimIndent()

        private fun prop(type: String, description: String, extra: Map<String, Any> = emptyMap()) =
            JsonValue.from(mapOf("type" to type, "description" to description) + extra)

        private val DATE = prop("string", "Día en formato AAAA-MM-DD.")
        private val TASK_ID = prop("string", "id de la tarea, tal como lo devuelve list_tasks o list_subtasks.")
        private val PRIORITY = prop(
            "string", "Orden dentro del día.", mapOf("enum" to listOf("primero", "normal", "al_final"))
        )
        private val REPEAT = prop(
            "array", "Días de la semana en que se repite. Lista vacía = no se repite.",
            mapOf("items" to mapOf("type" to "string", "enum" to TaskTools.DAYS.keys.toList())),
        )

        private fun tool(name: String, description: String, props: Map<String, JsonValue>, required: List<String>): Tool =
            Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(
                    Tool.InputSchema.builder()
                        .properties(
                            Tool.InputSchema.Properties.builder()
                                .apply { props.forEach { (k, v) -> putAdditionalProperty(k, v) } }
                                .build()
                        )
                        .required(required)
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                        .build()
                )
                .strict(true)
                .build()

        val TOOLS: List<Tool> = listOf(
            tool(
                TaskTools.LIST_TASKS,
                "Lista las tareas principales de un día (o las que no tienen día), con su id, si están hechas, " +
                    "su hora de aviso, prioridad y cuántas subtareas tienen.",
                mapOf(
                    "date" to prop("string", "Día en formato AAAA-MM-DD. Si no se da, hoy."),
                    "no_date" to prop("boolean", "true para ver las tareas que no tienen día."),
                ),
                emptyList(),
            ),
            tool(
                TaskTools.LIST_SUBTASKS,
                "Lista las subtareas de una tarea.",
                mapOf("task_id" to TASK_ID, "date" to DATE),
                listOf("task_id"),
            ),
            tool(
                TaskTools.ADD_TASK,
                "Crea una tarea nueva, opcionalmente con subtareas. Con parent_id se crea como subtarea de otra " +
                    "(las subtareas no tienen día ni repetición propios).",
                mapOf(
                    "title" to prop("string", "Qué hay que hacer, corto, empezando en mayúscula (p. ej. «Comprar pan»)."),
                    "date" to prop("string", "Día en formato AAAA-MM-DD. Omitir si no tiene día o si se repite."),
                    "time" to prop("string", "Hora del aviso en formato HH:MM (24 h). Omitir si no quiere aviso."),
                    "repeat_days" to REPEAT,
                    "priority" to PRIORITY,
                    "minutes" to prop("integer", "Minutos que se calcula que lleva."),
                    "subtasks" to prop("array", "Títulos de las subtareas, en orden.", mapOf("items" to mapOf("type" to "string"))),
                    "parent_id" to prop("string", "id de la tarea principal, para crear una subtarea."),
                ),
                listOf("title"),
            ),
            tool(
                TaskTools.UPDATE_TASK,
                "Cambia una tarea. Solo se cambian los campos que se mandan.",
                mapOf(
                    "task_id" to TASK_ID,
                    "title" to prop("string", "Título nuevo."),
                    "date" to DATE,
                    "no_date" to prop("boolean", "true para quitarle el día."),
                    "time" to prop("string", "Hora nueva del aviso, HH:MM (24 h)."),
                    "clear_time" to prop("boolean", "true para quitar el aviso."),
                    "repeat_days" to REPEAT,
                    "priority" to PRIORITY,
                    "minutes" to prop("integer", "Minutos que lleva."),
                ),
                listOf("task_id"),
            ),
            tool(
                TaskTools.SET_DONE,
                "Marca una tarea como hecha o pendiente. En las que se repiten cuenta solo para ese día.",
                mapOf(
                    "task_id" to TASK_ID,
                    "done" to prop("boolean", "true = hecha, false = pendiente."),
                    "date" to prop("string", "Día al que se refiere, AAAA-MM-DD. Si no se da, hoy."),
                ),
                listOf("task_id", "done"),
            ),
            tool(
                TaskTools.DELETE_TASK,
                "Borra una tarea y todas sus subtareas. Úsala solo si la persona lo pide claramente.",
                mapOf("task_id" to TASK_ID),
                listOf("task_id"),
            ),
        )
    }
}
