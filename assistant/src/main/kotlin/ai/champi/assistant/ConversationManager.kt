package ai.champi.assistant

import ai.champi.core.conversation.AttachmentType
import ai.champi.core.conversation.Message
import ai.champi.core.persistence.ConversationEntity
import ai.champi.core.persistence.MessageDao
import ai.champi.core.persistence.MessageEntity
import ai.champi.core.persistence.MessageRole
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Authoritative source for the active conversation's history. Loads the most recent conversation
 * from Room lazily — on first use of [messages] or either append function — rather than in the
 * constructor, since Hilt singleton construction must stay synchronous.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ConversationManager @Inject constructor(
    private val dao: MessageDao,
) {
    private val activeConversationId = MutableStateFlow<String?>(null)
    private val initMutex = Mutex()

    val messages: Flow<List<Message>> = activeConversationId
        .onStart { ensureInitialized() }
        .filterNotNull()
        .flatMapLatest { id -> dao.observeMessages(id).map { entities -> entities.map { it.toMessage() } } }

    suspend fun appendUserMessage(
        text: String,
        attachmentUri: String? = null,
        attachmentType: AttachmentType? = null,
    ) = appendMessage(MessageRole.USER, text, attachmentUri = attachmentUri, attachmentType = attachmentType)

    suspend fun appendAssistantMessage(text: String, providerMetadata: String? = null) =
        appendMessage(MessageRole.ASSISTANT, text, providerMetadata)

    /** Deletes the active conversation's messages and starts a fresh one. */
    suspend fun clearConversation() {
        ensureInitialized()
        val id = activeConversationId.value ?: return
        dao.deleteConversation(id)
        activeConversationId.value = createNewConversation()
    }

    private suspend fun appendMessage(
        role: MessageRole,
        content: String,
        providerMetadata: String? = null,
        attachmentUri: String? = null,
        attachmentType: AttachmentType? = null,
    ) {
        ensureInitialized()
        val conversationId = activeConversationId.value ?: error("conversation not initialized")
        dao.insertMessage(
            MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                role = role,
                content = content,
                timestamp = System.currentTimeMillis(),
                providerMetadata = providerMetadata,
                attachmentUri = attachmentUri,
                attachmentType = attachmentType?.name,
            ),
        )
    }

    private suspend fun ensureInitialized() {
        if (activeConversationId.value != null) return
        initMutex.withLock {
            if (activeConversationId.value != null) return
            activeConversationId.value = dao.getMostRecentConversation()?.id ?: createNewConversation()
        }
    }

    private suspend fun createNewConversation(): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        dao.insertConversation(ConversationEntity(id = id, createdAt = now, updatedAt = now))
        return id
    }

    private fun MessageEntity.toMessage() = Message(
        id = id,
        role = role,
        content = content,
        timestamp = timestamp,
        providerMetadata = providerMetadata,
        attachmentUri = attachmentUri,
        attachmentType = attachmentType?.let { runCatching { AttachmentType.valueOf(it) }.getOrNull() },
    )
}
