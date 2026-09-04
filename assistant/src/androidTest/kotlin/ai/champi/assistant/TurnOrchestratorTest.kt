package ai.champi.assistant

import ai.champi.core.persistence.AppDatabase
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TurnOrchestratorTest {

    private lateinit var db: AppDatabase
    private lateinit var conversationManager: ConversationManager
    private lateinit var appStateHolder: AppStateHolder

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        conversationManager = ConversationManager(db.messageDao())
        appStateHolder = AppStateHolder()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun submittingTextStreamsTokensThenReturnsToIdle() = runBlocking {
        val orchestrator = TurnOrchestrator(conversationManager, FakeLlmProvider(tokens = listOf("Hel", "lo", "!"), tokenDelayMs = 50L), appStateHolder)

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
        // Same provider/script serves both calls (TurnOrchestrator binds one LlmProvider for its
        // lifetime), so this must be short enough that an uncancelled second run also finishes
        // well inside the waits below — 500ms total — while still slow enough (100ms/token) to
        // reliably catch the first turn mid-stream before cancelling it.
        val slowProvider = FakeLlmProvider(tokens = List(5) { "SLOW" }, tokenDelayMs = 100L)
        val orchestrator = TurnOrchestrator(conversationManager, slowProvider, appStateHolder)

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
    fun submittingWhenProviderUnavailableShowsErrorAndReturnsToIdleWithinTwoSeconds() = runBlocking {
        val orchestrator = TurnOrchestrator(conversationManager, FakeLlmProvider(availableOverride = false), appStateHolder)
        val start = System.currentTimeMillis()

        orchestrator.submitText("hi")

        withTimeout(2000) { appStateHolder.state.first { it.characterState == CharacterState.IDLE && it.conversation.size == 2 } }

        assertTrue(System.currentTimeMillis() - start < 2000)
        val errorEntry = appStateHolder.state.first().conversation.last()
        assertTrue(!errorEntry.fromUser)
        assertTrue(errorEntry.text.isNotBlank())
    }
}
