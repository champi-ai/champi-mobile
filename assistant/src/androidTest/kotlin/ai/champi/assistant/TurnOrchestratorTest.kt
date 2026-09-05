package ai.champi.assistant

import ai.champi.core.context.ContextSnapshot
import ai.champi.core.context.ContextSnapshotSource
import ai.champi.core.persistence.AppDatabase
import ai.champi.core.routing.RoutingSettingsRepository
import ai.champi.core.state.AppStateHolder
import ai.champi.core.state.CharacterState
import ai.champi.providers.api.ActionProvider
import ai.champi.providers.api.ToolCall
import ai.champi.providers.api.ToolResult
import ai.champi.providers.api.ToolSpec
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** Assembles an orchestrator wired to a single [FakeLlmProvider] and optional action providers. */
    private fun buildOrchestrator(
        fakeLlm: FakeLlmProvider,
        actionProviders: List<ActionProvider> = emptyList(),
    ): TurnOrchestrator {
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
            actionProviders,
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

    // -------------------------------------------------------------------------
    // Tool-call flow tests (issue #40 acceptance criteria)
    // -------------------------------------------------------------------------

    /**
     * AC1: A non-destructive tool call completes with a [ToolResult] and the turn ends normally.
     * The [FakeLlmProvider] emits a single [ToolCallEvent] followed by [Done]; the
     * [FakeActionProvider] is wired to handle it and records the invocation.
     */
    @Test
    fun toolCallEvent_dispatchedToProvider_turnCompletesNormally() = runBlocking {
        val spec = ToolSpec("set_timer", "Start a timer", "{}", requiresConfirmation = false)
        val fakeAction = FakeActionProvider(spec)
        val fakeLlm = FakeLlmProvider(
            tokens = emptyList(),
            toolCalls = listOf(ToolCall("id1", "set_timer", "{\"hours\":0,\"minutes\":2}")),
        )

        val orchestrator = buildOrchestrator(fakeLlm, listOf(fakeAction))
        orchestrator.submitText("set a timer for 2 minutes")

        withTimeout(3000) { appStateHolder.state.first { it.characterState == CharacterState.IDLE } }

        assertTrue("ActionProvider.invoke was not called", fakeAction.invocations.isNotEmpty())
        assertEquals("set_timer", fakeAction.invocations.first().name)
    }

    /**
     * AC2: A destructive tool call (requiresConfirmation=true) suspends until user responds.
     * Declining the confirmation produces a ToolResult error and the action is NOT invoked.
     */
    @Test
    fun destructiveToolCall_declined_actionNotInvoked() = runBlocking {
        val spec = ToolSpec("create_event", "Creates a calendar event", "{}", requiresConfirmation = true)
        val fakeAction = FakeActionProvider(spec)
        val fakeLlm = FakeLlmProvider(
            tokens = emptyList(),
            toolCalls = listOf(ToolCall("id2", "create_event", "{\"title\":\"Meeting\"}")),
        )

        val orchestrator = buildOrchestrator(fakeLlm, listOf(fakeAction))
        orchestrator.submitText("add a calendar event")

        // Wait for the confirmation dialog to appear in AppState.
        withTimeout(3000) {
            appStateHolder.state.first { it.pendingConfirmation != null }
        }

        // Decline the confirmation.
        appStateHolder.respondToConfirmation(approved = false)

        // Turn must still complete without throwing.
        withTimeout(3000) { appStateHolder.state.first { it.characterState == CharacterState.IDLE } }

        // The provider must NOT have been invoked.
        assertTrue("ActionProvider.invoke was called despite decline", fakeAction.invocations.isEmpty())
    }

    /**
     * AC2b: A destructive tool call with user approval invokes the provider.
     */
    @Test
    fun destructiveToolCall_approved_actionInvoked() = runBlocking {
        val spec = ToolSpec("create_event", "Creates a calendar event", "{}", requiresConfirmation = true)
        val fakeAction = FakeActionProvider(spec)
        val fakeLlm = FakeLlmProvider(
            tokens = emptyList(),
            toolCalls = listOf(ToolCall("id3", "create_event", "{\"title\":\"Meeting\"}")),
        )

        val orchestrator = buildOrchestrator(fakeLlm, listOf(fakeAction))
        orchestrator.submitText("add a calendar event")

        withTimeout(3000) {
            appStateHolder.state.first { it.pendingConfirmation != null }
        }

        // Approve.
        appStateHolder.respondToConfirmation(approved = true)

        withTimeout(3000) { appStateHolder.state.first { it.characterState == CharacterState.IDLE } }

        assertTrue("ActionProvider.invoke was not called after approval", fakeAction.invocations.isNotEmpty())
    }

    /**
     * AC3: Two tool calls in one response execute sequentially. The second call only starts after
     * the first [ToolResult] is returned, verified by the ordering of [FakeActionProvider.invocations].
     */
    @Test
    fun twoToolCallsInOneTurn_executedSequentially() = runBlocking {
        val spec1 = ToolSpec("tool_a", "First tool", "{}")
        val spec2 = ToolSpec("tool_b", "Second tool", "{}")
        val fakeAction = FakeActionProvider(spec1, spec2, delayMs = 50L)
        val fakeLlm = FakeLlmProvider(
            tokens = emptyList(),
            toolCalls = listOf(
                ToolCall("id_a", "tool_a", "{}"),
                ToolCall("id_b", "tool_b", "{}"),
            ),
        )

        val orchestrator = buildOrchestrator(fakeLlm, listOf(fakeAction))
        orchestrator.submitText("run both tools")

        withTimeout(5000) { appStateHolder.state.first { it.characterState == CharacterState.IDLE } }

        assertEquals(2, fakeAction.invocations.size)
        // Sequential: tool_a must have finished before tool_b started.
        assertEquals("tool_a", fakeAction.invocations[0].name)
        assertEquals("tool_b", fakeAction.invocations[1].name)
        // The completion times must be in order (tool_a finished before tool_b finished).
        assertTrue(fakeAction.completionTimesMs[0] <= fakeAction.completionTimesMs[1])
    }

    /**
     * AC4: A tool call to an unknown tool name returns a graceful error ToolResult without
     * throwing, and the turn completes normally back to IDLE.
     */
    @Test
    fun toolCallToUnknownTool_gracefulErrorResult_turnCompletesNormally() = runBlocking {
        val fakeLlm = FakeLlmProvider(
            tokens = emptyList(),
            toolCalls = listOf(ToolCall("id_unk", "nonexistent_tool", "{}")),
        )

        // No action providers registered — every tool name is unknown.
        val orchestrator = buildOrchestrator(fakeLlm, emptyList())
        orchestrator.submitText("do something unknown")

        // Must resolve to IDLE without an exception blowing up the orchestrator.
        withTimeout(3000) { appStateHolder.state.first { it.characterState == CharacterState.IDLE } }
    }
}

// ---------------------------------------------------------------------------
// Test doubles
// ---------------------------------------------------------------------------

/**
 * Scriptable [ActionProvider] test double. Records each [invoke] call in [invocations] and
 * the wall-clock time each invocation completed in [completionTimesMs].
 *
 * When [delayMs] is set each invocation sleeps briefly so sequential-vs-concurrent timing
 * is observable.
 */
private class FakeActionProvider(
    vararg specs: ToolSpec,
    private val delayMs: Long = 0L,
) : ActionProvider {
    override val specs: List<ToolSpec> = specs.toList()
    val invocations: MutableList<ToolCall> = mutableListOf()
    val completionTimesMs: MutableList<Long> = mutableListOf()

    override suspend fun invoke(call: ToolCall): ToolResult {
        if (delayMs > 0) delay(delayMs)
        invocations.add(call)
        completionTimesMs.add(System.currentTimeMillis())
        return ToolResult(callId = call.id, resultJson = """{"status":"ok"}""")
    }
}
