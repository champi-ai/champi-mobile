package ai.champi.core.state

data class ConversationEntry(
    val id: String,
    val text: String,
    val fromUser: Boolean,
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
