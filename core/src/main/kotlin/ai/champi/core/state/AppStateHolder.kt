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

    // Nonce rather than a plain event: StateFlow always replays its current value to a fresh
    // collector, so a request issued a moment before the overlay composes (e.g. a Quick Settings
    // tile tap racing service startup) still lands instead of being lost the way a bare
    // SharedFlow's non-replayed emission would be.
    private val _openPanelRequestId = MutableStateFlow(0L)
    val openPanelRequestId: StateFlow<Long> = _openPanelRequestId

    fun requestOpenPanel() {
        _openPanelRequestId.update { it + 1 }
    }

    fun setCharacterState(characterState: CharacterState) {
        _state.update { it.copy(characterState = characterState) }
    }

    fun setAudioLevel(level: Float) {
        _state.update { it.copy(audioLevel = level) }
    }

    fun setAttention(attention: Float) {
        _state.update { it.copy(attention = attention.coerceIn(0f, 1f)) }
    }

    fun setMood(mood: Float) {
        _state.update { it.copy(mood = mood.coerceIn(0f, 1f)) }
    }

    fun appendConversationEntry(entry: ConversationEntry) {
        _state.update { it.copy(conversation = it.conversation + entry) }
    }

    /** Replaces an existing entry's text in place — how streamed LLM tokens update the last
     *  assistant entry incrementally instead of appending a new one per token. */
    fun updateConversationEntry(id: String, text: String) {
        _state.update { state ->
            state.copy(conversation = state.conversation.map { if (it.id == id) it.copy(text = text) else it })
        }
    }
}
