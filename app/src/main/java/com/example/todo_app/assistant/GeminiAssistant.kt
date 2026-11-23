package com.example.todo_app.assistant

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime

/** Respuesta HTTP mínima: código y cuerpo. */
data class HttpResult(val code: Int, val body: String)

/**
 * Conversación con Google Gemini sobre las tareas, por la API REST (`generateContent`).
 * Con una clave gratuita de Google AI Studio no cuesta dinero, aunque hay un límite de
 * preguntas por minuto y por día.
 *
 * Las respuestas del modelo se guardan tal cual en el historial: en las llamadas a
 * herramientas traen una «firma de razonamiento» que hay que devolver sin tocar.
 */
class GeminiAssistant(
    private val apiKey: String,
    private val tools: TaskTools,
    private val model: AssistantModel = AssistantModel.GEMINI_FLASH,
    /** Hace la petición POST. En los tests es una falsa. */
    private val post: (url: String, apiKey: String, body: String) -> HttpResult = ::httpPost,
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : Assistant {

    private val json = ObjectMapper()
    private val contents = mutableListOf<JsonNode>()

    override suspend fun ask(text: String): AssistantReply = withContext(io) {
        val start = contents.size
        try {
            contents.add(userText(AssistantSpec.userText(text, now())))
            var changed = false
            repeat(MAX_STEPS) {
                val response = call()
                val candidate = response.path("candidates").path(0)
                val content = candidate.path("content")
                if (content.isMissingNode || !content.has("parts")) {
                    // Sin respuesta: normalmente porque Google la ha bloqueado por seguridad.
                    while (contents.size > start) contents.removeAt(contents.size - 1)
                    return@withContext AssistantReply("Lo siento, con eso no te puedo ayudar.", changed)
                }
                // Se guarda la respuesta entera, con sus firmas, para la siguiente vuelta.
                contents.add(content)
                val parts = content.path("parts").toList()
                val calls = parts.filter { it.has("functionCall") }.map { it.path("functionCall") }
                if (calls.isEmpty()) {
                    val answer = parts.filter { it.has("text") && !it.path("thought").asBoolean(false) }
                        .joinToString(" ") { it.path("text").asText() }.trim()
                    return@withContext AssistantReply(answer.ifEmpty { "Hecho." }, changed)
                }
                val results = json.createArrayNode()
                calls.forEach { call ->
                    val name = call.path("name").asText()
                    @Suppress("UNCHECKED_CAST")
                    val args = runCatching { json.convertValue(call.path("args"), Map::class.java) as Map<String, Any?> }
                        .getOrNull().orEmpty()
                    val outcome = tools.run(name, args)
                    if (!outcome.isError && name in TaskTools.WRITES) changed = true
                    results.addObject().putObject("functionResponse").apply {
                        put("name", name)
                        if (call.has("id")) set<JsonNode>("id", call.path("id"))
                        putObject("response").put(if (outcome.isError) "error" else "result", outcome.text)
                    }
                }
                contents.add(json.createObjectNode().apply {
                    put("role", "user")
                    set<JsonNode>("parts", results)
                })
            }
            AssistantReply(AssistantSpec.TOO_MANY_STEPS, changed)
        } catch (e: Exception) {
            // Si algo falla a medias, se deja la conversación como estaba para poder seguir.
            while (contents.size > start) contents.removeAt(contents.size - 1)
            throw explain(e)
        }
    }

    override fun reset() {
        contents.clear()
    }

    private fun userText(text: String): ObjectNode = json.createObjectNode().apply {
        put("role", "user")
        putArray("parts").addObject().put("text", text)
    }

    /** Hace la petición y devuelve el JSON, o lanza [GeminiError] con lo que diga Google. */
    private fun call(): JsonNode {
        val body = json.createObjectNode().apply {
            putObject("systemInstruction").putArray("parts").addObject().put("text", AssistantSpec.SYSTEM)
            set<JsonNode>("contents", json.valueToTree(contents))
            putArray("tools").addObject().set<JsonNode>("functionDeclarations", json.valueToTree(DECLARATIONS))
        }
        val result = post("$BASE/models/${model.id}:generateContent", apiKey, json.writeValueAsString(body))
        val parsed = runCatching { json.readTree(result.body) }.getOrNull()
        if (result.code !in 200..299) {
            val error = parsed?.path("error")
            throw GeminiError(
                result.code,
                error?.path("status")?.asText().orEmpty(),
                error?.path("message")?.asText().orEmpty(),
            )
        }
        return parsed ?: throw GeminiError(result.code, "", "Respuesta vacía")
    }

    private class GeminiError(val code: Int, val status: String, override val message: String) : Exception(message)

    private fun explain(e: Exception): Exception {
        val message = when {
            e is AssistantException -> return e
            e is IOException -> "No hay conexión a internet."
            e is GeminiError && e.code == 429 ->
                "Has llegado al límite gratis de Gemini por ahora. Espera un minuto, o si es el límite del día, " +
                    "prueba mañana o cambia a ${AssistantModel.GEMINI_LITE.label} en los ajustes (⚙)."
            e is GeminiError && (e.message.contains("API key", ignoreCase = true) || e.status == "UNAUTHENTICATED" ||
                e.code == 401 || e.code == 403) ->
                "La clave de Google no es válida. Revísala en los ajustes del asistente (⚙)."
            e is GeminiError && e.message.contains("location", ignoreCase = true) ->
                "Google no ofrece Gemini gratis desde tu país. Prueba con Claude en los ajustes (⚙)."
            e is GeminiError && e.code >= 500 -> "Gemini está saturado ahora mismo. Prueba otra vez en un momento."
            e is GeminiError ->
                "Gemini ha dado un error ${e.code}" + (if (e.message.isNotEmpty()) ": ${e.message}" else ". Prueba otra vez.")
            else -> "Algo ha fallado: ${e.message ?: e.javaClass.simpleName}"
        }
        return AssistantException(message, e)
    }

    companion object {
        private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
        private const val MAX_STEPS = 10

        /** Las herramientas en el formato de Gemini. */
        val DECLARATIONS: List<Map<String, Any>> = AssistantSpec.TOOLS.map { spec ->
            mapOf(
                "name" to spec.name,
                "description" to spec.description,
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to spec.properties,
                    "required" to spec.required,
                ),
            )
        }

        /** POST con la clave en la cabecera (no en la URL, para que no salga en registros). */
        fun httpPost(url: String, apiKey: String, body: String): HttpResult {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 15_000
                conn.readTimeout = 60_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("x-goog-api-key", apiKey)
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                return HttpResult(code, text)
            } finally {
                conn.disconnect()
            }
        }
    }
}
