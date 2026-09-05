package ai.champi.assistant

import ai.champi.audio.AudioCapture
import ai.champi.audio.PlaybackQueue
import ai.champi.core.state.AppStateHolder
import ai.champi.core.state.CharacterState
import ai.champi.providers.api.SttProvider
import ai.champi.providers.api.TtsProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives the push-to-talk voice pipeline: STT → [TurnOrchestrator] → TTS → [PlaybackQueue].
 *
 * **Wake-word activation is intentionally absent.** The wake-word model training pipeline is
 * blocked on issue #24; there is no [ai.champi.providers.api.WakeWordProvider] implementation.
 * [VadProvider] is not yet wired here — it will be required for onset/endpointing when the
 * always-on wake-word loop is added in #24.
 *
 * **Mic re-acquisition:** [SttProvider] (backed by [ai.champi.providers.edge.EdgeSttProvider])
 * acquires the microphone itself via `SpeechRecognizer.startListening()` rather than consuming the
 * `Flow<PcmFrame>` passed to [ai.champi.providers.api.SttProvider.transcribe]. The PCM flow
 * argument is therefore [emptyFlow] — satisfying the API contract while acknowledging the
 * documented architectural tradeoff. [AudioCapture.pcmFlow] runs concurrently solely to drive the
 * RMS amplitude animation during [CharacterState.LISTENING]; Android's audio policy typically lets
 * both sessions coexist, with the higher-priority recognition session winning the mic — if
 * [AudioCapture] receives silence, the `.catch { }` operator ensures animation simply freezes
 * rather than crashing.
 *
 * **Provider metadata:** per-turn `providerMetadata` population is deferred. Threading metadata
 * through the [TurnOrchestrator.submitText] → `appendAssistantMessage` call path cleanly would
 * require a dedicated hook; that is out of scope for this issue. The `MessageEntity.providerMetadata`
 * field already exists and will be populated in a follow-up.
 *
 * **Character-state ownership:** this orchestrator owns [CharacterState.LISTENING],
 * [CharacterState.SPEAKING], and the final [CharacterState.IDLE] transition for a voice turn.
 * [TurnOrchestrator] internally transitions [CharacterState.THINKING] → [CharacterState.IDLE]
 * after the LLM responds; this orchestrator observes [AppStateHolder.state] to detect that
 * completion before initiating TTS playback.
 */
@Singleton
class VoiceTurnOrchestrator @Inject constructor(
    private val audioCapture: AudioCapture,
    private val sttProvider: SttProvider,
    private val ttsProvider: TtsProvider,
    private val playbackQueue: PlaybackQueue,
    private val turnOrchestrator: TurnOrchestrator,
    private val appStateHolder: AppStateHolder,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var session: Job? = null

    /**
     * Cancels any in-flight session then starts a new push-to-talk pipeline.
     *
     * Suspends only long enough to clean up the previous session (via [Job.cancelAndJoin]) so it
     * can never leave the character stuck in a transitional state. The new session itself runs
     * asynchronously — callers observe progress via [AppStateHolder.state].
     *
     * State sequence: [CharacterState.LISTENING] → (STT) → [CharacterState.THINKING] (owned by
     * [TurnOrchestrator]) → [CharacterState.SPEAKING] → [CharacterState.IDLE].
     */
    suspend fun activate() {
        session?.cancelAndJoin()
        session = scope.launch { runSession() }
    }

    /**
     * Cancels the current session if the pipeline is still in the [CharacterState.LISTENING]
     * phase — i.e. STT has not yet produced a final transcript. If the pipeline has already
     * advanced to [CharacterState.THINKING] or [CharacterState.SPEAKING] this is a no-op; the
     * session runs to natural completion without interruption.
     *
     * Safe to call from any thread.
     */
    fun deactivate() {
        if (appStateHolder.state.value.characterState == CharacterState.LISTENING) {
            session?.cancel()
            session = null
            appStateHolder.setAudioLevel(0f)
            appStateHolder.setCharacterState(CharacterState.IDLE)
        }
    }

    private suspend fun runSession() {
        appStateHolder.setCharacterState(CharacterState.LISTENING)
        var finalText: String? = null
        try {
            // coroutineScope creates a child scope so that rmsJob is cancelled automatically if
            // the outer session Job is cancelled — structured concurrency prevents mic/pcm leaks.
            coroutineScope {
                val rmsJob = launch {
                    audioCapture.pcmFlow()
                        .catch { /* SpeechRecognizer may hold the mic; animation stays at 0 */ }
                        .collect { frame -> appStateHolder.setAudioLevel(rmsLevel(frame.samples)) }
                }
                try {
                    sttProvider.transcribe(emptyFlow()).collect { transcript ->
                        if (transcript.isFinal) finalText = transcript.text
                    }
                } finally {
                    rmsJob.cancel()
                    appStateHolder.setAudioLevel(0f)
                }
            }

            val text = finalText?.takeIf { it.isNotBlank() } ?: run {
                appStateHolder.setCharacterState(CharacterState.IDLE)
                return
            }

            // TurnOrchestrator manages the THINKING → IDLE transition internally.
            turnOrchestrator.submitText(text)

            // Wait for the LLM response to complete.
            appStateHolder.state.first { it.characterState == CharacterState.IDLE }

            val response = appStateHolder.state.value.conversation
                .lastOrNull { !it.fromUser }?.text
                ?: return

            appStateHolder.setCharacterState(CharacterState.SPEAKING)
            val playbackJob = playbackQueue.enqueue(ttsProvider.synthesize(flowOf(response)))
            playbackJob.join()
            appStateHolder.setCharacterState(CharacterState.IDLE)
        } catch (e: CancellationException) {
            throw e
        } finally {
            appStateHolder.setAudioLevel(0f)
            val cs = appStateHolder.state.value.characterState
            if (cs == CharacterState.LISTENING || cs == CharacterState.SPEAKING) {
                appStateHolder.setCharacterState(CharacterState.IDLE)
            }
        }
    }
}

/** RMS amplitude of a 16-bit PCM frame, normalised to [0f, 1f]. */
private fun rmsLevel(samples: ShortArray): Float {
    if (samples.isEmpty()) return 0f
    var sumSquares = 0.0
    for (sample in samples) sumSquares += sample.toDouble() * sample.toDouble()
    return (kotlin.math.sqrt(sumSquares / samples.size) / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
}
