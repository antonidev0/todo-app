package com.example.todo_app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Guarda los nombres del equipo para no tener que escribirlos cada vez. */
class TeamStore(context: Context) {
    private val prefs by lazy { context.getSharedPreferences("equipo", Context.MODE_PRIVATE) }

    /** Lee del disco, así que se hace fuera del hilo principal. */
    suspend fun load(): List<String> = withContext(Dispatchers.IO) {
        prefs.getString(KEY, null)
            ?.split("\n")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
    }

    fun save(names: List<String>) {
        // apply() escribe en segundo plano.
        prefs.edit().putString(KEY, names.joinToString("\n")).apply()
    }

    private companion object {
        const val KEY = "nombres"
    }
}
