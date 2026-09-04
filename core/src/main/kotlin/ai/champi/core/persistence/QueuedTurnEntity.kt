package ai.champi.core.persistence

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A turn that could not be served because no provider was available; replayed when one becomes available. */
@Entity(tableName = "queued_turns")
data class QueuedTurnEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: String,
    val inputText: String,
    val inputAudioPath: String? = null,
    /** Epoch millis at which the turn was enqueued. */
    val enqueuedAt: Long,
    val retryCount: Int = 0,
    /**
     * Number of messages in the conversation at enqueue time. Used to detect a stale context
     * window: if the conversation has grown by more than [STALE_CONTEXT_THRESHOLD] messages since
     * the turn was queued, replay shows a system note instead of re-submitting the original input.
     */
    val messageCountAtEnqueue: Int = 0,
)
