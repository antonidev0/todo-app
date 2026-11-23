package com.example.todo_app

import android.app.Application
import com.example.todo_app.reminders.Notifications

class TodoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannel(this)
    }
}
