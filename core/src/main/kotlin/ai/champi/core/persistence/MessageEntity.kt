package ai.champi.core.persistence

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class MessageRole { USER, ASSISTANT, SYSTEM }

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Long,
    /** Raw JSON blob — routing/provider info for this turn (e.g. which provider answered it). */
    val providerMetadata: String? = null,
    /** Cache-local file path for share-sheet attachments. Null when the message has no attachment. */
    val attachmentUri: String? = null,
    /** Text encoding of an [ai.champi.core.conversation.AttachmentType] value, or null. */
    val attachmentType: String? = null,
)
