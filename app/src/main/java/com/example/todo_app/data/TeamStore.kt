package com.example.todo_app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Guarda los nombres del equipo para no tener que escribirlos cada vez. */
interface TeamStore {
    suspend fun load(): List<String>
    fun save(names: List<String>)

    /** Último día ("2026-11-23") en que se preguntó quién está, o null. */
    suspend fun lastAskedDate(): String?
    fun setLastAskedDate(date: String)
}

/** [TeamStore] sobre SharedPreferences. */
class PrefsTeamStore(context: Context) : TeamStore {
    private val prefs by lazy { context.getSharedPreferences("equipo", Context.MODE_PRIVATE) }

    /** Lee del disco, así que se hace fuera del hilo principal. */
    override suspend fun load(): List<String> = withContext(Dispatchers.IO) {
        prefs.getString(KEY, null)
            ?.split("\n")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
    }

    override fun save(names: List<String>) {
        // apply() escribe en segundo plano.
        prefs.edit().putString(KEY, names.joinToString("\n")).apply()
    }

    override suspend fun lastAskedDate(): String? = withContext(Dispatchers.IO) {
        prefs.getString(KEY_ASKED, null)
    }

    override fun setLastAskedDate(date: String) {
        prefs.edit().putString(KEY_ASKED, date).apply()
    }

    private companion object {
        const val KEY = "nombres"
        const val KEY_ASKED = "preguntado_el"
    }
}
