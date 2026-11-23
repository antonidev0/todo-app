package com.example.todo_app

import com.anthropic.core.jsonMapper
import com.anthropic.errors.AnthropicIoException
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.example.todo_app.assistant.AssistantException
import com.example.todo_app.assistant.AssistantModel
import com.example.todo_app.assistant.ClaudeAssistant
import com.example.todo_app.assistant.TaskTools
import com.example.todo_app.data.TaskRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class ClaudeAssistantTest {
    private val dao = FakeTaskDao()
    private val repo = TaskRepository(dao, DirectTransactor, FakeAlarms())
    private val now = LocalDateTime.of(2026, 11, 23, 10, 0)
    private val tools = TaskTools(repo, { now }, ZoneOffset.UTC)
    private val sent = mutableListOf<MessageCreateParams>()

    private fun message(stop: String, vararg content: String): Message = jsonMapper().readValue(
        """{"id":"msg_${sent.size}","type":"message","role":"assistant","model":"claude-haiku-5-5",
            "content":[${content.joinToString(",")}],"stop_reason":"$stop","stop_sequence":null,
            "usage":{"input_tokens":10,"output_tokens":5}}""",
        Message::class.java,
    )

    private fun assistant(vararg replies: Message, model: AssistantModel = AssistantModel.OPUS): ClaudeAssistant {
        val queue = ArrayDeque(replies.toList())
        return ClaudeAssistant({ sent += it; queue.removeFirst() }, tools, model, { now }, Dispatchers.Unconfined)
    }

    @Test
    fun usaLaHerramientaYContesta() = runBlocking {
        val a = assistant(
            message("tool_use", """{"type":"tool_use","id":"t1","name":"add_task","input":{"title":"Comprar pan","date":"2026-11-24","time":"09:00"}}"""),
            message("end_turn", """{"type":"text","text":"Hecho, te aviso mañana a las nueve."}"""),
        )

        val reply = a.ask("recuérdame comprar pan mañana a las 9")

        assertEquals("Hecho, te aviso mañana a las nueve.", reply.text)
        assertTrue(reply.changedTasks)
        assertEquals("Comprar pan", dao.all.values.single().title)
        // La segunda llamada lleva la pregunta, la respuesta con la herramienta y su resultado.
        assertEquals(3, sent[1].messages().size)
        assertTrue(sent[0].messages()[0].toString().contains("lunes 23 de noviembre de 2026"))
        assertEquals(6, sent[0].tools().get().size)
    }

    @Test
    fun soloLeerNoCambiaNada() = runBlocking {
        val a = assistant(
            message("tool_use", """{"type":"tool_use","id":"t1","name":"list_tasks","input":{}}"""),
            message("end_turn", """{"type":"text","text":"Hoy no tienes nada."}"""),
        )
        assertFalse(a.ask("¿qué tengo hoy?").changedTasks)
    }

    @Test
    fun siFallaLaConexionSeAvisaYSePuedeSeguir() = runBlocking {
        var calls = 0
        val a = ClaudeAssistant({
            calls++
            if (calls == 1) throw AnthropicIoException("sin red")
            message("end_turn", """{"type":"text","text":"Hola."}""")
        }, tools, AssistantModel.HAIKU, { now }, Dispatchers.Unconfined)

        try {
            a.ask("hola")
            fail("debería fallar")
        } catch (e: AssistantException) {
            assertEquals("No hay conexión a internet.", e.message)
        }
        assertEquals("Hola.", a.ask("hola").text)
    }

    @Test
    fun usaElModeloElegido() = runBlocking {
        val reply = message("end_turn", """{"type":"text","text":"Vale."}""")
        assistant(reply, model = AssistantModel.HAIKU).ask("hola")
        assertEquals("claude-haiku-5-5", sent.last().model().asString())
        // Haiku no admite el reintento en el servidor, así que no se manda.
        assertFalse(sent.last()._additionalBodyProperties().containsKey("fallbacks"))

        assistant(reply, model = AssistantModel.OPUS).ask("hola")
        assertEquals("claude-opus-5-5", sent.last().model().asString())
        assertTrue(sent.last()._additionalBodyProperties().containsKey("fallbacks"))
    }
}
