package ai.champi.core.state

import ai.champi.core.conversation.AttachmentType

data class ConversationEntry(
    val id: String,
    val text: String,
    val fromUser: Boolean,
    /** Cache-local file path set when this entry was created from a share-sheet payload. */
    val attachmentUri: String? = null,
    /** Discriminates whether [attachmentUri] is an image or a generic file. */
    val attachmentType: AttachmentType? = null,
)

data class AppState(
    val characterState: CharacterState = CharacterState.IDLE,
    val conversation: List<ConversationEntry> = emptyList(),
    val audioLevel: Float = 0f,
    /** 0f..1f: how focused the character's gaze/pose should be, e.g. driven by finger position
     * relative to the bubble during a long-press quick-actions interaction. */
    val attention: Float = 0f,
    /** 0f..1f: character mood/expression input for the Rive state machine (neutral = 0.5f). */
    val mood: Float = 0.5f,
)
