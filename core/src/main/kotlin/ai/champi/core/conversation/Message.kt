package ai.champi.core.conversation

import ai.champi.core.persistence.MessageRole

/** Domain-level conversation turn, decoupled from the Room entity it's persisted as. */
data class Message(
    val id: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Long,
    /** Raw JSON — which provider answered this turn, routing metadata, etc. Kept untyped until a
     *  real provider needs to attach structured metadata; nothing populates it yet. */
    val providerMetadata: String? = null,
)
