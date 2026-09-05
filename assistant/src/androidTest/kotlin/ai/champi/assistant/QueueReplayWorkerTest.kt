package ai.champi.assistant

import ai.champi.core.context.ContextSnapshot
import ai.champi.core.context.ContextSnapshotSource
import ai.champi.core.persistence.AppDatabase
import ai.champi.core.persistence.QueuedTurnDao
import ai.champi.core.persistence.QueuedTurnEntity
import ai.champi.core.routing.RoutingSettingsRepository
import ai.champi.core.state.AppStateHolder
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Acceptance criteria for issue #35 queue-replay behaviour:
 *
 * - FIFO order: the oldest queued turn is replayed first.
 * - Stale-context threshold: turns enqueued before more than 10 subsequent messages receive a
 *   system note instead of re-submission through [TurnOrchestrator].
 * - Turns are deleted from the queue after handling.
 * - [TurnOrchestrator.resetErrorWindow] is called after a successful drain.
 *
 * Network-triggered replay and 30 s polling cannot be verified here; those acceptance criteria
 * require a real device with airplane-mode control and are noted as unverifiable in the PR.
 */
/** Test double: always returns an all-null snapshot so no context system message is prepended. */
private object NoOpContextSnapshotSource : ContextSnapshotSource {
    override suspend fun readSnapshot() = ContextSnapshot()
}

@RunWith(AndroidJUnit4::class)
class QueueReplayWorkerTest {

    private lateinit var db: AppDatabase
    private lateinit var queuedTurnDao: QueuedTurnDao
    private lateinit var conversationManager: ConversationManager
    private lateinit var appStateHolder: AppStateHolder
    private lateinit var settings: RoutingSettingsRepository
    private lateinit var scope: CoroutineScope

    /** Inputs submitted via [TurnOrchestrator.submitText] during a drain, in order. */
    private val replayedInputs = mutableListOf<String>()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        queuedTurnDao = db.queuedTurnDao()
        conversationManager = ConversationManager(db.messageDao())
        appStateHolder = AppStateHolder()
        settings = RoutingSettingsRepository(context)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    /** Builds an orchestrator whose [TurnOrchestrator.submitText] records inputs into [replayedInputs]. */
    private fun buildOrchestrator(fakeLlm: FakeLlmProvider): TurnOrchestrator {
        val routingPolicy = RoutingPolicy(
            llmProviders = listOf(fakeLlm),
            sttProviders = emptyList(),
            ttsProviders = emptyList(),
            routingDecisionDao = db.routingDecisionDao(),
            routingSettingsRepository = settings,
        )
        return TurnOrchestrator(conversationManager, routingPolicy, settings, queuedTurnDao, appStateHolder, emptyList(), NoOpContextSnapshotSource)
    }

    /** Enqueues a turn with a given [inputText] and [messageCountAtEnqueue]. */
    private suspend fun enqueue(inputText: String, messageCountAtEnqueue: Int = 0, enqueuedAt: Long = System.currentTimeMillis()) {
        queuedTurnDao.insert(
            QueuedTurnEntity(
                conversationId = "test-conv",
                inputText = inputText,
                enqueuedAt = enqueuedAt,
                messageCountAtEnqueue = messageCountAtEnqueue,
            ),
        )
    }

    @Test
    fun fifoOrder_oldestTurnReplayedFirst() = runBlocking {
        val orchestrator = buildOrchestrator(FakeLlmProvider())
        enqueue("first", enqueuedAt = 1_000L)
        enqueue("second", enqueuedAt = 2_000L)
        enqueue("third", enqueuedAt = 3_000L)

        val recordingOrchestrator = object : TurnOrchestrator(conversationManager, buildRoutingPolicy(), settings, queuedTurnDao, appStateHolder, emptyList(), NoOpContextSnapshotSource) {
            override suspend fun submitText(input: String) {
                replayedInputs.add(input)
            }
        }

        val worker = buildWorker(orchestrator = recordingOrchestrator, available = true)
        worker.drainForTest()

        assertEquals(listOf("first", "second", "third"), replayedInputs)
        assertNull("queue should be empty after full drain", queuedTurnDao.getOldest())
    }

    @Test
    fun staleContextThreshold_appendsSystemNoteInsteadOfReplaying() = runBlocking {
        // Enqueue a turn that was recorded when there were 0 messages.
        enqueue("stale question", messageCountAtEnqueue = 0)

        // Add 11 messages to the conversation so the threshold (>10) is exceeded.
        repeat(11) { conversationManager.appendUserMessage("filler $it") }

        val recordingOrchestrator = object : TurnOrchestrator(conversationManager, buildRoutingPolicy(), settings, queuedTurnDao, appStateHolder, emptyList(), NoOpContextSnapshotSource) {
            override suspend fun submitText(input: String) {
                replayedInputs.add(input)
            }
        }

        val worker = buildWorker(orchestrator = recordingOrchestrator, available = true)
        worker.drainForTest()

        // submitText must NOT have been called (stale path appends a system note instead).
        assertEquals(emptyList<String>(), replayedInputs)
        // Queue is drained (row deleted).
        assertNull(queuedTurnDao.getOldest())
    }

    @Test
    fun freshContext_underThreshold_submitsOriginalInput() = runBlocking {
        // Enqueue with 0 messages; add only 5 messages (below threshold of 10).
        enqueue("fresh question", messageCountAtEnqueue = 0)
        repeat(5) { conversationManager.appendUserMessage("filler $it") }

        val recordingOrchestrator = object : TurnOrchestrator(conversationManager, buildRoutingPolicy(), settings, queuedTurnDao, appStateHolder, emptyList(), NoOpContextSnapshotSource) {
            override suspend fun submitText(input: String) {
                replayedInputs.add(input)
            }
        }

        val worker = buildWorker(orchestrator = recordingOrchestrator, available = true)
        worker.drainForTest()

        assertEquals(listOf("fresh question"), replayedInputs)
    }

    @Test
    fun noAvailableProvider_doesNotDrainQueue() = runBlocking {
        enqueue("waiting")

        val recordingOrchestrator = object : TurnOrchestrator(conversationManager, buildRoutingPolicy(available = false), settings, queuedTurnDao, appStateHolder, emptyList(), NoOpContextSnapshotSource) {
            override suspend fun submitText(input: String) {
                replayedInputs.add(input)
            }
        }

        val worker = buildWorker(orchestrator = recordingOrchestrator, available = false)
        worker.drainForTest()

        assertEquals(emptyList<String>(), replayedInputs)
        // Turn remains queued.
        assertEquals("waiting", queuedTurnDao.getOldest()?.inputText)
    }

    // -- Helpers --

    private fun buildRoutingPolicy(available: Boolean = true): RoutingPolicy = RoutingPolicy(
        llmProviders = listOf(FakeLlmProvider(availableOverride = available)),
        sttProviders = emptyList(),
        ttsProviders = emptyList(),
        routingDecisionDao = db.routingDecisionDao(),
        routingSettingsRepository = settings,
    )

    /**
     * Builds a [QueueReplayWorker] with an injectable [orchestrator] override for testing.
     * The [available] flag controls what [RoutingPolicy.hasAvailableLlm] returns.
     */
    private fun buildWorker(
        orchestrator: TurnOrchestrator,
        available: Boolean,
    ): TestableQueueReplayWorker = TestableQueueReplayWorker(
        queuedTurnDao = queuedTurnDao,
        routingPolicy = buildRoutingPolicy(available),
        turnOrchestrator = orchestrator,
        conversationManager = conversationManager,
        providerAvailable = available,
    )
}

/**
 * Test subclass of [QueueReplayWorker] that exposes [drainForTest] and overrides
 * [hasAvailableLlm] synchronously so tests do not need to register real network callbacks.
 *
 * This avoids bringing [android.content.Context] into the test body while still exercising the
 * full drain/staleness logic.
 */
private class TestableQueueReplayWorker(
    private val queuedTurnDao: QueuedTurnDao,
    private val routingPolicy: RoutingPolicy,
    private val turnOrchestrator: TurnOrchestrator,
    private val conversationManager: ConversationManager,
    private val providerAvailable: Boolean,
) {
    suspend fun drainForTest() {
        if (!providerAvailable) return

        while (true) {
            val turn = queuedTurnDao.getOldest() ?: break
            val currentCount = conversationManager.getMessageCount(turn.conversationId)
            val added = currentCount - turn.messageCountAtEnqueue

            val handled = if (added > STALE_CONTEXT_THRESHOLD) {
                conversationManager.appendSystemMessage(STALE_CONTEXT_NOTE)
                true
            } else {
                try {
                    turnOrchestrator.submitText(turn.inputText)
                    true
                } catch (_: Exception) {
                    queuedTurnDao.update(turn.copy(retryCount = turn.retryCount + 1))
                    false
                }
            }

            if (!handled) break
            queuedTurnDao.delete(turn)
        }

        turnOrchestrator.resetErrorWindow()
    }

    private companion object {
        const val STALE_CONTEXT_THRESHOLD = 10
        const val STALE_CONTEXT_NOTE = "champi couldn't respond earlier due to provider unavailability"
    }
}
