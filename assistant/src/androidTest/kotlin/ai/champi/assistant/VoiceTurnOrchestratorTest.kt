package ai.champi.assistant

import ai.champi.audio.AudioCapture
import ai.champi.audio.PlaybackQueue
import ai.champi.core.context.ContextSnapshot
import ai.champi.core.context.ContextSnapshotSource
import ai.champi.core.persistence.AppDatabase
import ai.champi.core.routing.RoutingSettingsRepository
import ai.champi.core.state.AppStateHolder
import ai.champi.core.state.CharacterState
import ai.champi.providers.api.AudioChunk
import ai.champi.providers.api.BatteryClass
import ai.champi.providers.api.Cost
import ai.champi.providers.api.LatencyClass
import ai.champi.providers.api.Locality
import ai.champi.providers.api.PcmFrame
import ai.champi.providers.api.ProviderCapabilities
import ai.champi.providers.api.SttProvider
import ai.champi.providers.api.Transcript
import ai.champi.providers.api.TtsProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [VoiceTurnOrchestrator] push-to-talk pipeline state machine.
 *
 * Acceptance criteria verified here:
 * - STT `Transcript.Final` causes `TurnOrchestrator.submitText` to be called with the
 *   transcript text (observed via FakeLlmProvider receiving the user input).
 * - TTS `synthesize` is called with the assistant response text (observed via
 *   [RecordingTtsProvider]).
 * - Character-state transitions follow LISTENING → THINKING → SPEAKING → IDLE.
 * - Releasing push-to-talk (via [VoiceTurnOrchestrator.deactivate]) while still in
 *   LISTENING cancels cleanly with no dangling state (returns to IDLE).
 * - An empty or blank final transcript skips submitText and returns to IDLE.
 *
 * Acceptance criteria that cannot be verified here (require real device):
 * - Actual speech transcription accuracy (EdgeSttProvider requires on-device model).
 * - Real audio playback through the speaker (AudioTrack hardware).
 * - Actual mic hold gesture timing.
 */
@RunWith(AndroidJUnit4::class)
class VoiceTurnOrchestratorTest {

    private lateinit var db: AppDatabase
    private lateinit var conversationManager: ConversationManager
    private lateinit var appStateHolder: AppStateHolder
    private lateinit var settings: RoutingSettingsRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        conversationManager = ConversationManager(db.messageDao())
        appStateHolder = AppStateHolder()
        settings = RoutingSettingsRepository(context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** Assembles a [TurnOrchestrator] wired to [fakeLlm]. */
    private fun buildTurnOrchestrator(fakeLlm: FakeLlmProvider): TurnOrchestrator {
        val routingPolicy = RoutingPolicy(
            llmProviders = listOf(fakeLlm),
            sttProviders = emptyList(),
            ttsProviders = emptyList(),
            routingDecisionDao = db.routingDecisionDao(),
            routingSettingsRepository = settings,
        )
        return TurnOrchestrator(
            conversationManager = conversationManager,
            routingPolicy = routingPolicy,
            routingSettingsRepository = settings,
            queuedTurnDao = db.queuedTurnDao(),
            appStateHolder = appStateHolder,
            actionProviders = emptyList(),
            contextSnapshotSource = NoOpContextSource,
        )
    }

    /** Assembles the full [VoiceTurnOrchestrator] with the given fake providers. */
    private fun buildOrchestrator(
        stt: SttProvider,
        tts: TtsProvider = RecordingTtsProvider(),
        fakeLlm: FakeLlmProvider = FakeLlmProvider(tokens = listOf("Hello!")),
    ): VoiceTurnOrchestrator {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return VoiceTurnOrchestrator(
            audioCapture = AudioCapture(context),
            sttProvider = stt,
            ttsProvider = tts,
            playbackQueue = PlaybackQueue(),
            turnOrchestrator = buildTurnOrchestrator(fakeLlm),
            appStateHolder = appStateHolder,
        )
    }

    // -------------------------------------------------------------------------
    // AC1: transcript → submitText
    // -------------------------------------------------------------------------

    /**
     * A [Transcript.isFinal] result from [SttProvider] must cause [TurnOrchestrator.submitText]
     * to be called with the transcript text. Verified by asserting the user input appears in
     * [FakeLlmProvider.lastCtx] (the conversation the LLM received).
     */
    @Test
    fun finalTranscript_callsSubmitTextWithTranscriptText() = runBlocking {
        val fakeLlm = FakeLlmProvider(tokens = listOf("Hi!"), tokenDelayMs = 20L)
        val vto = buildOrchestrator(stt = ScriptedSttProvider("hello world"), fakeLlm = fakeLlm)

        vto.activate()

        withTimeout(5000) { appStateHolder.state.first { it.characterState == CharacterState.IDLE } }

        val ctx = fakeLlm.lastCtx
        assertNotNull("LlmProvider.complete was never called", ctx)
        val userTurn = ctx!!.turns.find {
            it.role == ai.champi.providers.api.ConversationRole.USER &&
                it.text == "hello world"
        }
        assertNotNull("User turn with text 'hello world' not found in LLM context", userTurn)
    }

    // -------------------------------------------------------------------------
    // AC2: TTS called with assistant response
    // -------------------------------------------------------------------------

    /**
     * After [TurnOrchestrator] finishes generating, [TtsProvider.synthesize] must be called
     * with the assistant's response text.
     */
    @Test
    fun afterLlmDone_ttsCalledWithAssistantResponseText() = runBlocking {
        val fakeLlm = FakeLlmProvider(tokens = listOf("Hello!"), tokenDelayMs = 20L)
        val tts = RecordingTtsProvider()
        val vto = buildOrchestrator(stt = ScriptedSttProvider("hey"), tts = tts, fakeLlm = fakeLlm)

        vto.activate()

        withTimeout(5000) { appStateHolder.state.first { it.characterState == CharacterState.IDLE } }

        assertTrue("TtsProvider.synthesize was never called", tts.synthesizedTexts.isNotEmpty())
        assertEquals("Hello!", tts.synthesizedTexts.first())
    }

    // -------------------------------------------------------------------------
    // AC3: character-state transitions
    // -------------------------------------------------------------------------

    /**
     * Character state must progress LISTENING → THINKING → SPEAKING → IDLE in that order.
     */
    @Test
    fun characterStateTransitions_followExpectedSequence() = runBlocking {
        val observedStates = mutableListOf<CharacterState>()
        val collectJob = launch {
            appStateHolder.state.collect { observedStates.add(it.characterState) }
        }

        val fakeLlm = FakeLlmProvider(tokens = listOf("Hi"), tokenDelayMs = 30L)
        val vto = buildOrchestrator(stt = ScriptedSttProvider("test"), fakeLlm = fakeLlm)

        vto.activate()
        withTimeout(5000) { appStateHolder.state.first { it.characterState == CharacterState.IDLE } }

        collectJob.cancelAndJoin()

        val distinct = observedStates.distinct()
        assertTrue("LISTENING expected", CharacterState.LISTENING in distinct)
        assertTrue("THINKING expected", CharacterState.THINKING in distinct)
        assertTrue("SPEAKING expected", CharacterState.SPEAKING in distinct)
        assertTrue("IDLE expected as final", distinct.last() == CharacterState.IDLE)

        // Order check: LISTENING before THINKING, THINKING before SPEAKING, SPEAKING before IDLE.
        val listeningIdx = observedStates.indexOfFirst { it == CharacterState.LISTENING }
        val thinkingIdx = observedStates.indexOfFirst { it == CharacterState.THINKING }
        val speakingIdx = observedStates.indexOfFirst { it == CharacterState.SPEAKING }
        val idleIdx = observedStates.indexOfLast { it == CharacterState.IDLE }
        assertTrue("LISTENING before THINKING", listeningIdx < thinkingIdx)
        assertTrue("THINKING before SPEAKING", thinkingIdx < speakingIdx)
        assertTrue("SPEAKING before final IDLE", speakingIdx < idleIdx)
    }

    // -------------------------------------------------------------------------
    // AC4: deactivate during LISTENING cancels cleanly
    // -------------------------------------------------------------------------

    /**
     * Calling [VoiceTurnOrchestrator.deactivate] while still in [CharacterState.LISTENING]
     * (STT has not yet produced a transcript) must cancel the session and return to IDLE with
     * no dangling state and no calls to [TurnOrchestrator.submitText].
     */
    @Test
    fun deactivateWhileListening_cancelsCleanlyAndReturnsToIdle() = runBlocking {
        val fakeLlm = FakeLlmProvider(tokens = listOf("irrelevant"))
        val blockingStt = BlockingSttProvider()
        val tts = RecordingTtsProvider()
        val vto = buildOrchestrator(stt = blockingStt, tts = tts, fakeLlm = fakeLlm)

        vto.activate()

        // Wait for LISTENING state to be set.
        withTimeout(2000) { appStateHolder.state.first { it.characterState == CharacterState.LISTENING } }

        // Release push-to-talk — session should cancel.
        vto.deactivate()

        withTimeout(2000) { appStateHolder.state.first { it.characterState == CharacterState.IDLE } }

        // TTS must NOT have been called.
        assertTrue("TTS should not have been called", tts.synthesizedTexts.isEmpty())
        // LLM must NOT have been called.
        assertEquals("LLM should not have been called", null, fakeLlm.lastCtx)
    }

    // -------------------------------------------------------------------------
    // AC5: empty transcript returns to IDLE without calling submitText
    // -------------------------------------------------------------------------

    /**
     * When [SttProvider] returns a blank or empty [Transcript.isFinal] result, the pipeline
     * must return to [CharacterState.IDLE] without calling [TurnOrchestrator.submitText].
     */
    @Test
    fun emptyTranscript_returnsToIdleWithoutSubmittingText() = runBlocking {
        val fakeLlm = FakeLlmProvider(tokens = listOf("irrelevant"))
        val tts = RecordingTtsProvider()
        val vto = buildOrchestrator(stt = ScriptedSttProvider(""), tts = tts, fakeLlm = fakeLlm)

        vto.activate()

        withTimeout(3000) { appStateHolder.state.first { it.characterState == CharacterState.IDLE } }

        assertTrue("TTS should not have been called", tts.synthesizedTexts.isEmpty())
        assertEquals("LLM should not have been called", null, fakeLlm.lastCtx)
    }

}

// ---------------------------------------------------------------------------
// Context source stub
// ---------------------------------------------------------------------------

private object NoOpContextSource : ContextSnapshotSource {
    override suspend fun readSnapshot() = ContextSnapshot()
}

// ---------------------------------------------------------------------------
// STT test doubles
// ---------------------------------------------------------------------------

/**
 * Emits one [Transcript] with [Transcript.isFinal] = `true` after a brief delay. Use
 * [finalText] = `""` to exercise the empty-transcript path.
 */
private class ScriptedSttProvider(
    private val finalText: String,
    private val delayMs: Long = 30L,
) : SttProvider {
    override val id = "scripted-stt"
    override val locality = Locality.EDGE
    override val cost = Cost(LatencyClass.LOW, BatteryClass.LOW)
    override val capabilities = ProviderCapabilities(listOf("en"), Int.MAX_VALUE, true)

    override suspend fun available() = true

    override fun transcribe(segment: Flow<PcmFrame>): Flow<Transcript> = flow {
        delay(delayMs)
        emit(Transcript(text = finalText, isFinal = true))
    }
}

/**
 * Blocks in [transcribe] until [unblock] is called, then emits the provided [Transcript].
 * Used to verify cancellation while the pipeline is still in the STT phase.
 */
private class BlockingSttProvider : SttProvider {
    override val id = "blocking-stt"
    override val locality = Locality.EDGE
    override val cost = Cost(LatencyClass.LOW, BatteryClass.LOW)
    override val capabilities = ProviderCapabilities(listOf("en"), Int.MAX_VALUE, true)

    private val channel = Channel<String>(1)

    override suspend fun available() = true

    override fun transcribe(segment: Flow<PcmFrame>): Flow<Transcript> = flow {
        val text = channel.receive()
        emit(Transcript(text = text, isFinal = true))
    }

    /** Sends [transcript] to unblock a waiting [transcribe] call. */
    fun unblock(transcript: String = "unblocked") {
        channel.trySend(transcript)
    }
}

// ---------------------------------------------------------------------------
// TTS test double
// ---------------------------------------------------------------------------

/**
 * Records every [synthesize] call's collected text and returns [emptyFlow] so no AudioTrack is
 * created and no hardware is required.
 */
private class RecordingTtsProvider : TtsProvider {
    override val id = "recording-tts"
    override val locality = Locality.EDGE
    override val cost = Cost(LatencyClass.LOW, BatteryClass.LOW)
    override val capabilities = ProviderCapabilities(listOf("en"), Int.MAX_VALUE, true)

    val synthesizedTexts: MutableList<String> = mutableListOf()

    override suspend fun available() = true

    override fun synthesize(text: Flow<String>): Flow<AudioChunk> = flow {
        val sb = StringBuilder()
        text.collect { sb.append(it) }
        synthesizedTexts.add(sb.toString())
        // No AudioChunk emitted → PlaybackQueue collects nothing, no AudioTrack is created,
        // and the enqueue Job completes immediately — no audio hardware required.
    }
}
