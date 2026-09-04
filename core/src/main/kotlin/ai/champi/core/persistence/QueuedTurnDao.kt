package ai.champi.core.persistence

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface QueuedTurnDao {
    @Insert
    suspend fun insert(turn: QueuedTurnEntity): Long

    /** Returns the single oldest enqueued turn (by `enqueuedAt`), or `null` if the queue is empty. */
    @Query("SELECT * FROM queued_turns ORDER BY enqueuedAt ASC LIMIT 1")
    suspend fun getOldest(): QueuedTurnEntity?

    @Update
    suspend fun update(turn: QueuedTurnEntity)

    @Delete
    suspend fun delete(turn: QueuedTurnEntity)
}
