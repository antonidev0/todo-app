package com.example.todo_app

import android.app.Application
import com.example.todo_app.reminders.Notifications

class TodoApp : Application() {

    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannel(this)
    }
}
