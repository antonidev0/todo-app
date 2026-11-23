package com.example.todo_app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {

    /** Hijos directos de [parentId] (null = tareas principales), las pendientes primero. */
    @Query(
        """
        SELECT t.*,
            (SELECT COUNT(*) FROM tasks c WHERE c.parentId = t.id AND c.deletedAt IS NULL) AS childCount,
            (SELECT COUNT(*) FROM tasks c WHERE c.parentId = t.id AND c.deletedAt IS NULL AND c.done = 1) AS doneChildCount
        FROM tasks t
        WHERE t.parentId IS :parentId AND t.deletedAt IS NULL
        ORDER BY t.done ASC, t.reminderAt IS NULL, t.reminderAt ASC, t.position ASC, t.createdAt ASC
        """
    )
    fun observeChildren(parentId: String?): Flow<List<TaskWithCounts>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    fun observeTask(id: String): Flow<Task?>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getTask(id: String): Task?

    /** Ids de la tarea y de todas sus subtareas, a cualquier profundidad. */
    @Query(
        """
        WITH RECURSIVE sub(id) AS (
            SELECT :id
            UNION ALL
            SELECT t.id FROM tasks t JOIN sub ON t.parentId = sub.id
        )
        SELECT id FROM sub
        """
    )
    suspend fun getSubtreeIds(id: String): List<String>

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM tasks WHERE parentId IS :parentId")
    suspend fun nextPosition(parentId: String?): Int

    /** Tareas con recordatorio pendiente o que se repiten (para reprogramarlas al reiniciar). */
    @Query(
        """
        SELECT * FROM tasks
        WHERE deletedAt IS NULL AND reminderAt IS NOT NULL
            AND (repeatDays != 0 OR (done = 0 AND reminderAt > :now))
        """
    )
    suspend fun getActiveReminders(now: Long): List<Task>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: Task)

    @Update
    suspend fun update(task: Task)

    @Query("UPDATE tasks SET deletedAt = :now, updatedAt = :now WHERE id IN (:ids)")
    suspend fun markDeleted(ids: List<String>, now: Long)
}
