package ai.champi.core.persistence

import androidx.room.Dao
import androidx.room.Insert

@Dao
interface RoutingDecisionDao {
    @Insert
    suspend fun insert(decision: RoutingDecisionEntity): Long
}
