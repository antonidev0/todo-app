package com.example.todo_app

import android.content.Context
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
}

/** Atajo para llegar al contenedor desde un receiver o una actividad. */
val Context.appContainer: AppContainer
    get() = (applicationContext as TodoApp).container
