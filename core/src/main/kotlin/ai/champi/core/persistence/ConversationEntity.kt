package ai.champi.core.persistence

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** Added in schema v2 — exists to exercise a real migration (see MIGRATION_1_2). */
    val title: String? = null,
)
