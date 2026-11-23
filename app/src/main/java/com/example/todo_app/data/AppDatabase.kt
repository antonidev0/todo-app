package com.example.todo_app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Task::class, Completion::class], version = 4, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao

    companion object {
        /** Versión 2: añade los días de repetición sin perder las tareas guardadas. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN repeatDays INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Versión 3: minutos estimados y persona asignada. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN minutes INTEGER")
                db.execSQL("ALTER TABLE tasks ADD COLUMN assignee TEXT")
            }
        }

        /**
         * Versión 4: prioridad, día de las tareas sin repetición y "hecha" por día.
         * Las tareas que se repiten y estaban marcadas como hechas pasan a estar
         * hechas solo hoy.
         */
        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN priority INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE tasks ADD COLUMN date TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS completions (" +
                        "taskId TEXT NOT NULL, date TEXT NOT NULL, completedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(taskId, date))"
                )
                db.execSQL(
                    "UPDATE tasks SET date = date(reminderAt / 1000, 'unixepoch', 'localtime') " +
                        "WHERE reminderAt IS NOT NULL AND repeatDays = 0"
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO completions (taskId, date, completedAt) " +
                        "SELECT id, date('now', 'localtime'), COALESCE(completedAt, 0) " +
                        "FROM tasks WHERE repeatDays != 0 AND done = 1"
                )
                db.execSQL("UPDATE tasks SET done = 0, completedAt = NULL WHERE repeatDays != 0")
            }
        }

        /** Crea la base de datos. Solo debe llamarse desde [com.example.todo_app.AppContainer]. */
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "recordatorios.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
    }
}
