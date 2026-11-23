package com.example.todo_app.assistant

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** La respuesta del asistente, y si ha cambiado alguna tarea. */
data class AssistantReply(val text: String, val changedTasks: Boolean = false)

/** Un fallo que se puede enseñar tal cual a la persona. */
class AssistantException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Una conversación con una IA que usa [TaskTools] para ver y cambiar las tareas. */
interface Assistant {
    /** Manda [text] y devuelve la respuesta cuando la IA ha terminado de usar las herramientas. */
    suspend fun ask(text: String): AssistantReply

    /** Empieza una conversación nueva. */
    fun reset()
}

/** Quién da la IA. Cada uno tiene su propia clave. */
enum class Provider(
    val label: String,
    /** Dónde se consigue la clave. */
    val keyUrl: String,
    /** Cómo empiezan sus claves, para avisar si se pega otra cosa. */
    val keyPrefix: String,
) {
    GEMINI("Google Gemini", "https://aistudio.google.com/apikey", "AIza"),
    CLAUDE("Anthropic Claude", "https://console.anthropic.com/settings/keys", "sk-ant-"),
}

/** Los modelos que se pueden elegir en los ajustes. Los de Gemini son gratis (con límite diario). */
enum class AssistantModel(
    val provider: Provider,
    val id: String,
    val label: String,
    val description: String,
    /** Solo Claude: reintento automático en el servidor si rechaza algo por error (Haiku no lo tiene). */
    val serverFallback: Boolean = false,
) {
    GEMINI_FLASH(
        Provider.GEMINI, "gemini-3.5-flash", "Gemini 3.5 Flash",
        "Gratis, con un límite de preguntas al día. Rápido y entiende bien.",
    ),
    GEMINI_LITE(
        Provider.GEMINI, "gemini-3.1-flash-lite", "Gemini 3.1 Flash-Lite",
        "Gratis. Más sencillo y suele tener un límite diario más alto.",
    ),
    HAIKU(
        Provider.CLAUDE, "claude-haiku-5-5", "Claude Haiku 5.5",
        "De pago: unos 0,1 céntimos por pregunta. Rápido y va sobrado para tareas sencillas.",
    ),
    SONNET(
        Provider.CLAUDE, "claude-sonnet-5-5", "Claude Sonnet 5.5",
        "De pago: alrededor de 1 céntimo por pregunta.", serverFallback = true,
    ),
    OPUS(
        Provider.CLAUDE, "claude-opus-5-5", "Claude Opus 5.5",
        "De pago: el más listo y el más caro (unos 2 céntimos por pregunta).", serverFallback = true,
    );

    companion object {
        val DEFAULT = GEMINI_FLASH

        fun fromId(id: String?): AssistantModel = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** Una herramienta descrita sin depender de ninguna IA: cada asistente la traduce a su formato. */
data class ToolSpec(
    val name: String,
    val description: String,
    /** Propiedades en JSON Schema (type, description, enum, items). */
    val properties: Map<String, Map<String, Any>>,
    val required: List<String> = emptyList(),
)

/** Lo que comparten todos los asistentes: instrucciones y herramientas. */
object AssistantSpec {
    private val NOW = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM 'de' yyyy, HH:mm", Locale.forLanguageTag("es-ES"))

    /** El mensaje de la persona con la fecha y hora delante, para que la IA sepa qué es «hoy». */
    fun userText(text: String, now: LocalDateTime): String = "(Ahora es ${now.format(NOW)}.)\n$text"

    val SYSTEM = """
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

    const val TOO_MANY_STEPS = "Me he liado con tantos pasos. ¿Me lo pides otra vez más sencillo?"

    private fun prop(type: String, description: String, extra: Map<String, Any> = emptyMap()) =
        mapOf("type" to type, "description" to description) + extra

    private val DATE = prop("string", "Día en formato AAAA-MM-DD.")
    private val TASK_ID = prop("string", "id de la tarea, tal como lo devuelve list_tasks o list_subtasks.")
    private val PRIORITY = prop("string", "Orden dentro del día.", mapOf("enum" to listOf("primero", "normal", "al_final")))
    private val REPEAT = prop(
        "array", "Días de la semana en que se repite. Lista vacía = no se repite.",
        mapOf("items" to mapOf("type" to "string", "enum" to TaskTools.DAYS.keys.toList())),
    )

    val TOOLS: List<ToolSpec> = listOf(
        ToolSpec(
            TaskTools.LIST_TASKS,
            "Lista las tareas principales de un día (o las que no tienen día), con su id, si están hechas, " +
                "su hora de aviso, prioridad y cuántas subtareas tienen.",
            mapOf(
                "date" to prop("string", "Día en formato AAAA-MM-DD. Si no se da, hoy."),
                "no_date" to prop("boolean", "true para ver las tareas que no tienen día."),
            ),
        ),
        ToolSpec(
            TaskTools.LIST_SUBTASKS,
            "Lista las subtareas de una tarea.",
            mapOf("task_id" to TASK_ID, "date" to DATE),
            listOf("task_id"),
        ),
        ToolSpec(
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
        ToolSpec(
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
        ToolSpec(
            TaskTools.SET_DONE,
            "Marca una tarea como hecha o pendiente. En las que se repiten cuenta solo para ese día.",
            mapOf(
                "task_id" to TASK_ID,
                "done" to prop("boolean", "true = hecha, false = pendiente."),
                "date" to prop("string", "Día al que se refiere, AAAA-MM-DD. Si no se da, hoy."),
            ),
            listOf("task_id", "done"),
        ),
        ToolSpec(
            TaskTools.DELETE_TASK,
            "Borra una tarea y todas sus subtareas. Úsala solo si la persona lo pide claramente.",
            mapOf("task_id" to TASK_ID),
            listOf("task_id"),
        ),
    )
}
