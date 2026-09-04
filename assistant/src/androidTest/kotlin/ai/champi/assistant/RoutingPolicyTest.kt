package ai.champi.assistant

import ai.champi.core.persistence.AppDatabase
import ai.champi.core.persistence.RoutingDecisionDao
import ai.champi.core.persistence.RoutingDecisionEntity
import ai.champi.core.persistence.RoutingReason
import ai.champi.core.persistence.RoutingStage
import ai.champi.core.routing.RoutingSettingsRepository
import ai.champi.providers.api.BatteryClass
import ai.champi.providers.api.Conversation
import ai.champi.providers.api.ConversationTurn
import ai.champi.providers.api.ConversationRole
import ai.champi.providers.api.Cost
import ai.champi.providers.api.LatencyClass
import ai.champi.providers.api.Locality
import ai.champi.providers.api.ProviderCapabilities
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises all five acceptance criteria for issue #34:
 *
 * 1. edgeOnly=true returns the edge provider regardless of input size.
 * 2. Short input + available edge → edge is selected.
 * 3. Input exceeding 80% of edge context window → remote is selected (mock edge maxInputTokens=100).
 * 4. Every selectLlm() call writes a RoutingDecision row to Room.
 * 5. NoProviderException is thrown when both edge and remote are unavailable.
 *
 * A real in-memory Room database is used for criteria 4 so the DAO is tested as-is (matching
 * the pattern in TurnOrchestratorTest). [RoutingSettingsRepository] is NOT mocked — it would
 * require a real DataStore Context which is available in instrumented tests.
 */
@RunWith(AndroidJUnit4::class)
class RoutingPolicyTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: RoutingDecisionDao
    private lateinit var settings: RoutingSettingsRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.routingDecisionDao()
        settings = RoutingSettingsRepository(context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // -- Helpers --

    private fun emptyCtx() = Conversation(emptyList())

    private fun ctxWithTokens(estimatedTokens: Int): Conversation {
        // Each char is ~1/4 token; create turns whose total char length = estimatedTokens * 4
        val text = "a".repeat(estimatedTokens * 4)
        return Conversation(listOf(ConversationTurn(ConversationRole.USER, text)))
    }

    private fun edgeLlm(maxInputTokens: Int = 4096, available: Boolean = true) =
        FakeLlmProvider(availableOverride = available).let { base ->
            object : ai.champi.providers.api.LlmProvider by base {
                override val id = "edge-llm"
                override val locality = Locality.EDGE
                override val capabilities = ProviderCapabilities(
                    languages = listOf("en"),
                    maxInputTokens = maxInputTokens,
                    supportsStreaming = true,
                )
                override suspend fun available() = available
            }
        }

    private fun remoteLlm(available: Boolean = true) =
        FakeLlmProvider(availableOverride = available).let { base ->
            object : ai.champi.providers.api.LlmProvider by base {
                override val id = "remote-llm"
                override val locality = Locality.REMOTE
                override val capabilities = ProviderCapabilities(
                    languages = listOf("en"),
                    maxInputTokens = 128_000,
                    supportsStreaming = true,
                )
                override suspend fun available() = available
            }
        }

    private fun policy(
        llmProviders: List<ai.champi.providers.api.LlmProvider> = emptyList(),
        sttProviders: List<ai.champi.providers.api.SttProvider> = emptyList(),
        ttsProviders: List<ai.champi.providers.api.TtsProvider> = emptyList(),
    ) = RoutingPolicy(
        llmProviders = llmProviders,
        sttProviders = sttProviders,
        ttsProviders = ttsProviders,
        routingDecisionDao = dao,
        routingSettingsRepository = settings,
    )

    // -- Acceptance criterion 1: edgeOnly=true always returns the edge provider --

    @Test
    fun edgeOnly_returnsEdgeRegardlessOfInputSize() = runBlocking {
        // Input that would clearly exceed the 80% budget of a small edge model.
        val edge = edgeLlm(maxInputTokens = 100)
        val p = policy(llmProviders = listOf(edge, remoteLlm()))
        val hugInput = "word ".repeat(100) // ~500 chars → ~125 tokens, well above 80 (80% of 100)

        val selected = p.selectLlm(emptyCtx(), hugInput, edgeOnly = true)

        assertEquals("edge-llm", selected.id)
    }

    // -- Acceptance criterion 2: short input + available edge → edge --

    @Test
    fun shortInput_edgeAvailable_selectsEdge() = runBlocking {
        val edge = edgeLlm(maxInputTokens = 4096)
        val p = policy(llmProviders = listOf(edge, remoteLlm()))
        val shortInput = "hello world" // ~2 tokens

        val selected = p.selectLlm(emptyCtx(), shortInput, edgeOnly = false)

        assertEquals("edge-llm", selected.id)
    }

    // -- Acceptance criterion 3: large input (>80% of edge window) → remote --

    @Test
    fun inputExceedingEdgeBudget_selectsRemote() = runBlocking {
        // Edge model reports maxInputTokens=100; 80% threshold = 80 tokens.
        // Build input that is 85 tokens (85*4=340 chars).
        val edge = edgeLlm(maxInputTokens = 100)
        val remote = remoteLlm()
        val p = policy(llmProviders = listOf(edge, remote))
        val largeInput = "a".repeat(85 * 4) // 340 chars → ~85 tokens

        val selected = p.selectLlm(emptyCtx(), largeInput, edgeOnly = false)

        assertEquals("remote-llm", selected.id)
    }

    // -- Acceptance criterion 4: every selectLlm() writes a RoutingDecision row --

    @Test
    fun selectLlm_writesRoutingDecisionRow() = runBlocking {
        val edge = edgeLlm()
        val p = policy(llmProviders = listOf(edge))

        p.selectLlm(emptyCtx(), "test input", edgeOnly = false)

        // Query the in-memory database directly.
        val rows = db.openHelper.writableDatabase.let { db ->
            val cursor = db.query("SELECT * FROM routing_decisions")
            val count = cursor.count
            cursor.close()
            count
        }
        assertEquals(1, rows)
    }

    @Test
    fun multipleSelectLlmCalls_writeMultipleRows() = runBlocking {
        val edge = edgeLlm()
        val p = policy(llmProviders = listOf(edge))

        p.selectLlm(emptyCtx(), "first", edgeOnly = false)
        p.selectLlm(emptyCtx(), "second", edgeOnly = false)

        val rows = db.openHelper.writableDatabase.let { db ->
            val cursor = db.query("SELECT * FROM routing_decisions")
            val count = cursor.count
            cursor.close()
            count
        }
        assertEquals(2, rows)
    }

    // -- Acceptance criterion 5: NoProviderException when both edge and remote are unavailable --

    @Test
    fun noProvidersAvailable_throwsNoProviderException() = runBlocking {
        val edge = edgeLlm(available = false)
        val remote = remoteLlm(available = false)
        val p = policy(llmProviders = listOf(edge, remote))

        var caughtException: NoProviderException? = null
        try {
            p.selectLlm(emptyCtx(), "hello", edgeOnly = false)
        } catch (e: NoProviderException) {
            caughtException = e
        }

        assertNotNull("Expected NoProviderException but no exception was thrown", caughtException)
    }

    @Test
    fun noProviderException_isNotSwallowed() = runBlocking {
        val p = policy(llmProviders = emptyList())

        var threw = false
        try {
            p.selectLlm(emptyCtx(), "hello", edgeOnly = false)
        } catch (e: NoProviderException) {
            threw = true
        }

        assertTrue("NoProviderException must propagate, not be swallowed", threw)
    }

    // -- Degrade case also writes a row --

    @Test
    fun degradeCase_writesRoutingDecisionRowWithDegradeReason() = runBlocking {
        val p = policy(llmProviders = emptyList())

        try {
            p.selectLlm(emptyCtx(), "hello", edgeOnly = false)
        } catch (_: NoProviderException) {
            // expected
        }

        val rows = db.openHelper.writableDatabase.let { sdb ->
            val cursor = sdb.query(
                "SELECT reason FROM routing_decisions WHERE reason = 'DEGRADE'",
            )
            val count = cursor.count
            cursor.close()
            count
        }
        assertEquals(1, rows)
    }

    // -- fits() heuristic unit test --

    @Test
    fun fits_returnsFalseWhenTotalExceedsEightyPercent() {
        val edge = edgeLlm(maxInputTokens = 100)
        val p = policy(llmProviders = listOf(edge))

        // 85 tokens context + 0 input = 85 > 80 (80% of 100)
        val ctx = ctxWithTokens(85)
        val result = p.fits(edge, ctx, "")

        assertTrue("fits() should return false when total tokens exceed 80% budget", !result)
    }

    @Test
    fun fits_returnsTrueWhenTotalIsBelowEightyPercent() {
        val edge = edgeLlm(maxInputTokens = 100)
        val p = policy(llmProviders = listOf(edge))

        // 10 tokens context + ~2 token input = 12 < 80
        val ctx = ctxWithTokens(10)
        val result = p.fits(edge, ctx, "hello world")

        assertTrue("fits() should return true when total tokens are within 80% budget", result)
    }
}
