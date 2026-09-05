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

/**
 * Emitted by [AppStateHolder.requestConfirmation] when a destructive tool action needs the
 * user's explicit approval before [ActionProvider.invoke] is called.
 *
 * @param toolName  The `ToolSpec.name` being requested, shown in the dialog title.
 * @param prompt    A human-readable summary of what the action will do.
 */
data class ConfirmationRequest(
    val toolName: String,
    val prompt: String,
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
    /**
     * Non-null while [AppStateHolder.requestConfirmation] is waiting for user input. The overlay
     * observes this and renders a confirmation dialog; the user's response is delivered back via
     * [AppStateHolder.respondToConfirmation].
     */
    val pendingConfirmation: ConfirmationRequest? = null,
)
