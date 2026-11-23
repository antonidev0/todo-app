package com.example.todo_app.assistant

import android.content.Context

/**
 * Guarda la clave de la API de Claude. Se queda solo en el móvil: no está en el código,
 * no se sube a GitHub y no entra en las copias de seguridad (ver backup_rules.xml).
 */
interface ApiKeyStore {
    fun get(): String?
    fun set(key: String?)
}

/** [ApiKeyStore] sobre SharedPreferences privadas de la app. */
class PrefsApiKeyStore(context: Context) : ApiKeyStore {
    private val prefs by lazy { context.getSharedPreferences(FILE, Context.MODE_PRIVATE) }

    override fun get(): String? = prefs.getString(KEY, null)?.takeIf { it.isNotBlank() }

    override fun set(key: String?) {
        prefs.edit().apply {
            if (key.isNullOrBlank()) remove(KEY) else putString(KEY, key.trim())
        }.apply()
    }

    companion object {
        /** Nombre del archivo, para excluirlo de las copias de seguridad. */
        const val FILE = "asistente"
        private const val KEY = "clave_api"
    }
}
