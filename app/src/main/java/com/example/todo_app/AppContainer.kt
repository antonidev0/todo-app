package com.example.todo_app

import android.content.Context
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.example.todo_app.assistant.ApiKeyStore
import com.example.todo_app.assistant.ClaudeAssistant
import com.example.todo_app.assistant.PrefsApiKeyStore
import com.example.todo_app.assistant.TaskTools
import com.example.todo_app.data.AppDatabase
import com.example.todo_app.data.RoomTransactor
import com.example.todo_app.data.TaskRepository
import com.example.todo_app.data.PrefsTeamStore
import com.example.todo_app.data.TeamStore
import com.example.todo_app.reminders.ReminderScheduler

/**
 * Único sitio donde se crean las piezas de la app (inyección de dependencias manual).
 * Vive en [TodoApp], así que hay una sola base de datos y un solo repositorio
 * para la pantalla y para los avisos.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    private val database: AppDatabase by lazy { AppDatabase.create(appContext) }

    val reminderScheduler: ReminderScheduler by lazy { ReminderScheduler(appContext) }

    val taskRepository: TaskRepository by lazy {
        TaskRepository(database.taskDao(), RoomTransactor(database), reminderScheduler)
    }

    val teamStore: TeamStore by lazy { PrefsTeamStore(appContext) }

    val apiKeyStore: ApiKeyStore by lazy { PrefsApiKeyStore(appContext) }

    /** Un asistente nuevo que habla con Claude usando [apiKey]. */
    fun newAssistant(apiKey: String): ClaudeAssistant = ClaudeAssistant(
        AnthropicOkHttpClient.builder().apiKey(apiKey).build(),
        TaskTools(taskRepository),
    )
}

/** Atajo para llegar al contenedor desde un receiver o una actividad. */
val Context.appContainer: AppContainer
    get() = (applicationContext as TodoApp).container
