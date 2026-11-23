package com.example.todo_app.assistant

import android.content.Context

/**
 * Guarda las claves de las IAs (Gemini y Claude) y el modelo elegido. Se queda solo en el móvil: no está en el código,
 * no se sube a GitHub y no entra en las copias de seguridad (ver backup_rules.xml).
 */
interface ApiKeyStore {
    /** Clave guardada para [provider], o null. */
    fun get(provider: Provider): String?
    fun set(provider: Provider, key: String?)

    /** Modelo elegido en los ajustes. */
    fun model(): AssistantModel
    fun setModel(model: AssistantModel)
}

/** [ApiKeyStore] sobre SharedPreferences privadas de la app. */
class PrefsApiKeyStore(context: Context) : ApiKeyStore {
    private val prefs by lazy { context.getSharedPreferences(FILE, Context.MODE_PRIVATE) }

    override fun get(provider: Provider): String? = prefs.getString(keyFor(provider), null)?.takeIf { it.isNotBlank() }

    override fun set(provider: Provider, key: String?) {
        prefs.edit().apply {
            if (key.isNullOrBlank()) remove(keyFor(provider)) else putString(keyFor(provider), key.trim())
        }.apply()
    }

    /** La de Claude conserva el nombre de antes, para no perder una clave ya guardada. */
    private fun keyFor(provider: Provider) = when (provider) {
        Provider.CLAUDE -> KEY
        Provider.GEMINI -> "clave_gemini"
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
