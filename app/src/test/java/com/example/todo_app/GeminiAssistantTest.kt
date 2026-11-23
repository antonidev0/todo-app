package com.example.todo_app

import com.example.todo_app.assistant.AssistantException
import com.example.todo_app.assistant.AssistantModel
import com.example.todo_app.assistant.GeminiAssistant
import com.example.todo_app.assistant.HttpResult
import com.example.todo_app.assistant.TaskTools
import com.example.todo_app.data.TaskRepository
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class GeminiAssistantTest {
    private val dao = FakeTaskDao()
    private val repo = TaskRepository(dao, DirectTransactor, FakeAlarms())
    private val now = LocalDateTime.of(2026, 11, 23, 10, 0)
    private val tools = TaskTools(repo, { now }, ZoneOffset.UTC)
    private val json = ObjectMapper()

    private data class Sent(val url: String, val key: String, val body: String)

    private val sent = mutableListOf<Sent>()

    private fun reply(vararg parts: String) = HttpResult(
        200, """{"candidates":[{"content":{"role":"model","parts":[${parts.joinToString(",")}]}}]}""",
    )

    private fun assistant(vararg replies: HttpResult, model: AssistantModel = AssistantModel.GEMINI_FLASH): GeminiAssistant {
        val queue = ArrayDeque(replies.toList())
        return GeminiAssistant("AIza-prueba", tools, model, { url, key, body ->
            sent += Sent(url, key, body)
            queue.removeFirst()
        }, { now }, Dispatchers.Unconfined)
    }

    @Test
    fun usaLaHerramientaYDevuelveLaFirma() = runBlocking {
        val a = assistant(
            reply("""{"functionCall":{"id":"c1","name":"add_task","args":{"title":"Comprar pan","date":"2026-11-24","time":"09:00"}},"thoughtSignature":"FIRMA123"}"""),
            reply("""{"text":"pensando...","thought":true}""", """{"text":"Hecho, te aviso mañana a las nueve."}"""),
        )

        val r = a.ask("recuérdame comprar pan mañana a las 9")

        assertEquals("Hecho, te aviso mañana a las nueve.", r.text)
        assertTrue(r.changedTasks)
        assertEquals("Comprar pan", dao.all.values.single().title)
        assertTrue(sent[0].url.endsWith("/models/gemini-3.5-flash:generateContent"))
        assertEquals("AIza-prueba", sent[0].key)

        val first = json.readTree(sent[0].body)
        assertTrue(first.path("contents").path(0).toString().contains("lunes 23 de noviembre de 2026"))
        assertEquals(6, first.path("tools").path(0).path("functionDeclarations").size())

        // La segunda petición lleva la pregunta, la llamada con su firma intacta y el resultado.
        val contents = json.readTree(sent[1].body).path("contents")
        assertEquals(3, contents.size())
        assertEquals("FIRMA123", contents.path(1).path("parts").path(0).path("thoughtSignature").asText())
        val response = contents.path(2).path("parts").path(0).path("functionResponse")
        assertEquals("add_task", response.path("name").asText())
        assertEquals("c1", response.path("id").asText())
        assertTrue(response.path("response").path("result").asText().startsWith("Creada"))
    }

    @Test
    fun soloLeerNoCambiaNada() = runBlocking {
        val a = assistant(
            reply("""{"functionCall":{"name":"list_tasks","args":{}}}"""),
            reply("""{"text":"Hoy no tienes nada."}"""),
        )
        val r = a.ask("¿qué tengo hoy?")
        assertEquals("Hoy no tienes nada.", r.text)
        assertFalse(r.changedTasks)
    }

    @Test
    fun alLlegarAlLimiteGratisLoExplica() = runBlocking {
        val a = assistant(
            HttpResult(429, """{"error":{"code":429,"message":"Quota exceeded","status":"RESOURCE_EXHAUSTED"}}"""),
            reply("""{"text":"Hola."}"""),
        )
        try {
            a.ask("hola")
            fail("debería fallar")
        } catch (e: AssistantException) {
            assertTrue(e.message!!.contains("límite gratis"))
        }
        // La conversación queda limpia y se puede seguir.
        assertEquals("Hola.", a.ask("hola").text)
        assertEquals(1, json.readTree(sent[1].body).path("contents").size())
    }

    @Test
    fun claveMalaSeExplica() = runBlocking {
        val a = assistant(
            HttpResult(400, """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}"""),
        )
        try {
            a.ask("hola")
            fail("debería fallar")
        } catch (e: AssistantException) {
            assertTrue(e.message!!.contains("clave de Google no es válida"))
        }
    }

    @Test
    fun respuestaBloqueadaNoRompeLaConversacion() = runBlocking {
        val a = assistant(
            HttpResult(200, """{"candidates":[{"finishReason":"SAFETY"}]}"""),
            reply("""{"text":"Vale."}"""),
        )
        assertEquals("Lo siento, con eso no te puedo ayudar.", a.ask("algo").text)
        assertEquals("Vale.", a.ask("otra cosa").text)
        assertEquals(1, json.readTree(sent[1].body).path("contents").size())
    }
}
