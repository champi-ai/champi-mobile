package ai.champi.core.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/** Single source of truth for [AppState], shared by `:overlay`, `:character` and `:assistant`. */
@Singleton
class AppStateHolder @Inject constructor() {
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state

    /**
     * Holds the in-flight [CompletableDeferred] for the current confirmation request, if any.
     * Only one confirmation can be pending at a time (tool calls are processed sequentially).
     */
    private val pendingConfirmationDeferred = AtomicReference<CompletableDeferred<Boolean>?>(null)

    // Nonce rather than a plain event: StateFlow always replays its current value to a fresh
    // collector, so a request issued a moment before the overlay composes (e.g. a Quick Settings
    // tile tap racing service startup) still lands instead of being lost the way a bare
    // SharedFlow's non-replayed emission would be.
    private val _openPanelRequestId = MutableStateFlow(0L)
    val openPanelRequestId: StateFlow<Long> = _openPanelRequestId

    fun requestOpenPanel() {
        _openPanelRequestId.update { it + 1 }
    }

    /**
     * Nonce incremented when [AlarmTimerActionProvider] needs the user to grant
     * `SCHEDULE_EXACT_ALARM` on Android 12+. [ChampiService] observes this and fires
     * [android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM] with
     * [android.content.Intent.FLAG_ACTIVITY_NEW_TASK].
     */
    private val _exactAlarmSettingsRedirectId = MutableStateFlow(0L)
    val exactAlarmSettingsRedirectId: StateFlow<Long> = _exactAlarmSettingsRedirectId

    fun requestExactAlarmSettingsRedirect() {
        _exactAlarmSettingsRedirectId.update { it + 1 }
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

    /**
     * Publishes [request] to the UI (via [AppState.pendingConfirmation]) and suspends until the
     * user responds via [respondToConfirmation]. Returns `true` if approved, `false` if declined.
     *
     * Tool calls are processed sequentially so at most one confirmation is pending at a time.
     */
    suspend fun requestConfirmation(request: ConfirmationRequest): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pendingConfirmationDeferred.set(deferred)
        _state.update { it.copy(pendingConfirmation = request) }
        return try {
            deferred.await()
        } finally {
            pendingConfirmationDeferred.set(null)
            _state.update { it.copy(pendingConfirmation = null) }
        }
    }

    /**
     * Delivers the user's response ([approved]) to the in-flight [requestConfirmation] call.
     * Called by the overlay confirmation dialog's approve/decline buttons. A no-op if no
     * confirmation is pending.
     */
    fun respondToConfirmation(approved: Boolean) {
        pendingConfirmationDeferred.get()?.complete(approved)
    }

    fun appendConversationEntry(entry: ConversationEntry) {
        _state.update { it.copy(conversation = it.conversation + entry) }
    }

    /** Replaces the whole conversation list — used once to seed it from persisted history on
     *  cold start, since this in-memory list starts empty on every process restart. */
    fun setConversation(entries: List<ConversationEntry>) {
        _state.update { it.copy(conversation = entries) }
    }

    /** Replaces an existing entry's text in place — how streamed LLM tokens update the last
     *  assistant entry incrementally instead of appending a new one per token. */
    fun updateConversationEntry(id: String, text: String) {
        _state.update { state ->
            state.copy(conversation = state.conversation.map { if (it.id == id) it.copy(text = text) else it })
        }
    }
}
