package com.example.todo_app.data

import android.content.Context

/** Guarda los nombres del equipo para no tener que escribirlos cada vez. */
class TeamStore(context: Context) {
    private val prefs = context.getSharedPreferences("equipo", Context.MODE_PRIVATE)

    fun load(): List<String> =
        prefs.getString(KEY, null)
            ?.split("\n")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    fun save(names: List<String>) {
        prefs.edit().putString(KEY, names.joinToString("\n")).apply()
    }

    private companion object {
        const val KEY = "nombres"
    }
}
