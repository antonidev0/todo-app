package com.example.todo_app

import com.example.todo_app.data.Splitter
import com.example.todo_app.data.Splitter.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitterTest {

    private val closing = listOf(
        Item("1", "Barrer", 20),
        Item("2", "Recargar bebidas", 15),
        Item("3", "Limpiar vasos", 25),
        Item("4", "Limpiar máquina de zumos", 10),
        Item("5", "Sacar la basura", 5),
        Item("6", "Fregar el suelo", 20),
    )

    @Test
    fun todasLasTareasSeRepartenUnaVez() {
        val shares = Splitter.split(closing, listOf("Antoni", "Ana", "Luis"))
        val ids = shares.flatMap { s -> s.items.map { it.id } }
        assertEquals(closing.map { it.id }.sorted(), ids.sorted())
    }

    @Test
    fun repartoEquilibradoConTresPersonas() {
        // Total 95 min entre 3: lo ideal sería ~32 min cada uno.
        val shares = Splitter.split(closing, listOf("Antoni", "Ana", "Luis"))
        val totals = shares.map { it.totalMinutes }
        assertTrue("reparto: $totals", totals.max() - totals.min() <= 10)
        assertEquals(35, totals.max())
    }

    @Test
    fun unaSolaPersonaLoHaceTodo() {
        val shares = Splitter.split(closing, listOf("Antoni"))
        assertEquals(95, shares.single().totalMinutes)
        // Mantiene el orden original de la lista
        assertEquals(closing.map { it.id }, shares.single().items.map { it.id })
    }

    @Test
    fun masPersonasQueTareas() {
        val shares = Splitter.split(closing.take(2), listOf("A", "B", "C"))
        assertEquals(3, shares.size)
        assertEquals(1, shares.count { it.items.isEmpty() })
    }

    @Test
    fun sinPersonasNoHayReparto() {
        assertTrue(Splitter.split(closing, emptyList()).isEmpty())
    }

    @Test
    fun mensajeParaElGrupo() {
        val text = Splitter.message("Cierre", Splitter.split(closing.take(2), listOf("Ana", "Luis")))
        assertTrue(text.contains("👤 Ana (20 min)"))
        assertTrue(text.contains("• Recargar bebidas"))
    }
}
