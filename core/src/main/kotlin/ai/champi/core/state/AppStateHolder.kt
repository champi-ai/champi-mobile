package ai.champi.core.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** Single source of truth for [AppState], shared by `:overlay`, `:character` and `:assistant`. */
@Singleton
class AppStateHolder @Inject constructor() {
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state

    fun setCharacterState(characterState: CharacterState) {
        _state.update { it.copy(characterState = characterState) }
    }

    fun setAudioLevel(level: Float) {
        _state.update { it.copy(audioLevel = level) }
    }

    fun appendConversationEntry(entry: ConversationEntry) {
        _state.update { it.copy(conversation = it.conversation + entry) }
    }
}
