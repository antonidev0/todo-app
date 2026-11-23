package com.example.todo_app.data

/**
 * Reparte tareas entre varias personas para que todos terminen más o menos a la vez.
 *
 * Usa el método clásico "la más larga primero": ordena las tareas de más larga a más
 * corta y cada una se la da a quien lleve menos minutos acumulados en ese momento.
 * Es simple, rápido y casi siempre queda muy cerca del reparto perfecto.
 */
object Splitter {

    /** Minutos que se suponen si una tarea no tiene estimación. */
    const val DEFAULT_MINUTES = 10

    data class Item(
        val id: String,
        val title: String,
        val minutes: Int,
        val priority: Int = Priority.NORMAL,
    )

    data class Share(val person: String, val items: List<Item>) {
        val totalMinutes: Int get() = items.sumOf { it.minutes }
    }

    fun split(items: List<Item>, people: List<String>): List<Share> {
        if (people.isEmpty()) return emptyList()
        val loads = people.associateWith { mutableListOf<Item>() }
        val totals = people.associateWith { 0 }.toMutableMap()

        // Más largas primero; si empatan, se mantiene el orden original de la lista.
        items.sortedByDescending { it.minutes }.forEach { item ->
            // Quien lleve menos minutos; si empatan, el primero de la lista de personas.
            val person = people.minBy { totals.getValue(it) }
            loads.getValue(person).add(item)
            totals[person] = totals.getValue(person) + item.minutes
        }

        // Dentro de cada persona: primero las de prioridad "Primero", al final las de
        // "Al final", y entre medias el mismo orden que la lista original.
        val order = items.withIndex().associate { it.value.id to it.index }
        return people.map { p ->
            Share(p, loads.getValue(p).sortedWith(compareBy<Item>({ it.priority }, { order.getValue(it.id) })))
        }
    }

    /** Mensaje para mandar al grupo, por ejemplo por WhatsApp. */
    fun message(title: String, shares: List<Share>): String = buildString {
        append("📋 ").append(title).append("\n")
        val longest = shares.maxOfOrNull { it.totalMinutes } ?: 0
        if (longest > 0) append("Acabamos en unos ").append(longest).append(" min\n")
        shares.forEach { share ->
            append("\n👤 ").append(share.person)
            append(" (").append(share.totalMinutes).append(" min)\n")
            if (share.items.isEmpty()) append("   – nada, ¡ayuda a quien vaya más lento!\n")
            share.items.forEach { append("   • ").append(it.title).append("\n") }
        }
    }.trimEnd()
}
