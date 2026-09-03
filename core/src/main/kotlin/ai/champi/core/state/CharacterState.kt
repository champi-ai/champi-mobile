package ai.champi.core.state

/** The seven visual/behavioral states the character (Rive artboard) can be driven into. */
enum class CharacterState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING,
    NOTIFYING,
    ERROR,
    SLEEPING,
}
