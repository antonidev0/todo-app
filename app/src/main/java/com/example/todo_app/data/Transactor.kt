package com.example.todo_app.data

import androidx.room.RoomDatabase
import androidx.room.withTransaction

/** Ejecuta un bloque como una sola transacción: se guarda todo o nada. */
interface Transactor {
    suspend fun <R> inTransaction(block: suspend () -> R): R
}

class RoomTransactor(private val db: RoomDatabase) : Transactor {
    override suspend fun <R> inTransaction(block: suspend () -> R): R = db.withTransaction(block)
}
