package com.example.todo_app.assistant

import android.content.Context

/**
 * Guarda la clave de la API de Claude. Se queda solo en el móvil: no está en el código,
 * no se sube a GitHub y no entra en las copias de seguridad (ver backup_rules.xml).
 */
interface ApiKeyStore {
    fun get(): String?
    fun set(key: String?)

    /** Modelo elegido en los ajustes. */
    fun model(): AssistantModel
    fun setModel(model: AssistantModel)
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

    override fun model(): AssistantModel = AssistantModel.fromId(prefs.getString(MODEL, null))

    override fun setModel(model: AssistantModel) {
        prefs.edit().putString(MODEL, model.id).apply()
    }

    companion object {
        private const val MODEL = "modelo"
        /** Nombre del archivo, para excluirlo de las copias de seguridad. */
        const val FILE = "asistente"
        private const val KEY = "clave_api"
    }
}
