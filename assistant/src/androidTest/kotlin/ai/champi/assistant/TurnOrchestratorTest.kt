package ai.champi.assistant

import ai.champi.core.context.ContextSnapshot
import ai.champi.core.context.ContextSnapshotSource
import ai.champi.core.persistence.AppDatabase
import ai.champi.core.routing.RoutingSettingsRepository
import ai.champi.core.state.AppStateHolder
import ai.champi.core.state.CharacterState
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Test double: always returns an all-null snapshot so no context system message is prepended. */
private object NoOpContextSnapshotSource : ContextSnapshotSource {
    override suspend fun readSnapshot() = ContextSnapshot()
}

@RunWith(AndroidJUnit4::class)
class TurnOrchestratorTest {

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

    /** Assembles an orchestrator wired to a single [FakeLlmProvider]. */
    private fun buildOrchestrator(fakeLlm: FakeLlmProvider): TurnOrchestrator {
        val routingDecisionDao = db.routingDecisionDao()
        val queuedTurnDao = db.queuedTurnDao()
        val routingPolicy = RoutingPolicy(
            llmProviders = listOf(fakeLlm),
            sttProviders = emptyList(),
            ttsProviders = emptyList(),
            routingDecisionDao = routingDecisionDao,
            routingSettingsRepository = settings,
        )
        return TurnOrchestrator(
            conversationManager,
            routingPolicy,
            settings,
            queuedTurnDao,
            appStateHolder,
            NoOpContextSnapshotSource,
        )
    }

    @Test
    fun submittingTextStreamsTokensThenReturnsToIdle() = runBlocking {
        val orchestrator = buildOrchestrator(FakeLlmProvider(tokens = listOf("Hel", "lo", "!"), tokenDelayMs = 50L))

        orchestrator.submitText("hi")

        // Predicate-based wait, not an instant sample: launching the turn is async, so checking
        // characterState immediately after submitText() returns races the launched coroutine.
        withTimeout(1000) { appStateHolder.state.first { it.characterState == CharacterState.THINKING } }

        // Tokens must appear incrementally, not all at once when generation completes: catch the
        // assistant entry mid-stream (non-empty but not yet the full "Hello!").
        withTimeout(2000) {
            appStateHolder.state.first { state ->
                state.conversation.any { !it.fromUser && it.text.isNotEmpty() && it.text != "Hello!" }
            }
        }

        withTimeout(2000) { appStateHolder.state.first { it.characterState == CharacterState.IDLE } }

        val finalState = appStateHolder.state.first()
        assertEquals("hi", finalState.conversation.first().text)
        assertEquals("Hello!", finalState.conversation.last().text)

        val persisted = withTimeout(2000) { conversationManager.messages.first { it.size == 2 } }
        assertEquals("hi", persisted[0].content)
        assertEquals("Hello!", persisted[1].content)
    }

    @Test
    fun submittingASecondTurnCancelsTheFirstWithoutLeavingThinkingDangling() = runBlocking {
        // Same provider/script serves both calls (TurnOrchestrator binds one RoutingPolicy for its
        // lifetime), so this must be short enough that an uncancelled second run also finishes
        // well inside the waits below — 500ms total — while still slow enough (100ms/token) to
        // reliably catch the first turn mid-stream before cancelling it.
        val slowProvider = FakeLlmProvider(tokens = List(5) { "SLOW" }, tokenDelayMs = 100L)
        val orchestrator = buildOrchestrator(slowProvider)

        orchestrator.submitText("first turn")
        // Wait for at least one token to actually land, proving the first turn is genuinely
        // mid-stream (not just mid-setup) before cancelling it.
        withTimeout(2000) {
            appStateHolder.state.first { state -> state.conversation.any { !it.fromUser && it.text.isNotEmpty() } }
        }

        orchestrator.submitText("second turn")
        withTimeout(2000) { appStateHolder.state.first { it.characterState == CharacterState.IDLE } }

        // Exactly 3 messages, not 4: if cancellation hadn't worked, the first turn would
        // eventually also reach LlmEvent.Done and persist its own assistant message.
        val persisted = conversationManager.messages.first()
        assertEquals(3, persisted.size)
        assertEquals("first turn", persisted[0].content)
        assertEquals("second turn", persisted[1].content)
        assertEquals("SLOW".repeat(5), persisted[2].content)
    }

    @Test
    fun submittingWhenNoProviderAvailable_queuesTheTurnAndFlashesErrorThenIdle() = runBlocking {
        val orchestrator = buildOrchestrator(FakeLlmProvider(availableOverride = false))
        val queuedTurnDao = db.queuedTurnDao()
        val start = System.currentTimeMillis()

        orchestrator.submitText("hi")

        // ERROR must appear and then resolve back to IDLE within ~3 s (2 s flash + margin).
        withTimeout(3000) {
            appStateHolder.state.first { it.characterState == CharacterState.ERROR }
        }
        withTimeout(3000) { appStateHolder.state.first { it.characterState == CharacterState.IDLE } }

        // The turn must be written to the queue, not silently dropped.
        val queued = withTimeout(500) {
            var found: ai.champi.core.persistence.QueuedTurnEntity? = null
            while (found == null) {
                found = queuedTurnDao.getOldest()
                if (found == null) kotlinx.coroutines.delay(50)
            }
            found
        }
        assertNotNull(queued)
        assertEquals("hi", queued.inputText)

        // ERROR was shown in time (well under the 3 s outer window).
        assertTrue(System.currentTimeMillis() - start < 3000)
    }

    @Test
    fun multipleUnavailableTurns_onlyFlashErrorOnce() = runBlocking {
        val orchestrator = buildOrchestrator(FakeLlmProvider(availableOverride = false))
        val errorFlashes = mutableListOf<Long>()

        orchestrator.submitText("first")
        withTimeout(3000) { appStateHolder.state.first { it.characterState == CharacterState.ERROR } }
        errorFlashes.add(System.currentTimeMillis())
        withTimeout(3000) { appStateHolder.state.first { it.characterState == CharacterState.IDLE } }

        // Submit a second turn in the same outage window — ERROR must NOT flash again.
        orchestrator.submitText("second")
        // Give enough time for any async error flash to appear (more than the 2000ms delay).
        kotlinx.coroutines.delay(500)

        // Only one ERROR flash recorded.
        assertEquals(1, errorFlashes.size)
        // Both turns are in the queue.
        val q = db.queuedTurnDao()
        var count = 0
        var oldest = q.getOldest()
        while (oldest != null) {
            count++
            q.delete(oldest)
            oldest = q.getOldest()
        }
        assertEquals(2, count)
    }
}
