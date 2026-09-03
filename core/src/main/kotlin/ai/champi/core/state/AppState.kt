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
)
