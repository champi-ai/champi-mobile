package ai.champi.assistant

import ai.champi.core.conversation.Message
import ai.champi.core.persistence.AppDatabase
import ai.champi.core.persistence.MessageRole
import ai.champi.providers.api.ConversationRole
import ai.champi.providers.api.estimateTokens
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Verifies the acceptance criteria for issue #60:
 *
 * 1. A 200-message conversation submitted to an edge LLM with maxInputTokens=2048 produces a
 *    [Conversation] that includes the omission note (context was trimmed).
 * 2. ContextWindowBuilder with a 10-token effective budget and 5 messages of 3 tokens each
 *    includes only the system message and the 2 most recent turns.
 * 3. Stored [MessageEntity] rows in Room are not modified by windowing (count is unchanged).
 * 4. Two providers with different maxInputTokens receive different-length [Conversation]s for
 *    the same raw message list.
 */
@RunWith(AndroidJUnit4::class)
class ContextWindowBuilderTest {

    private lateinit var db: AppDatabase
    private lateinit var conversationManager: ConversationManager

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        conversationManager = ConversationManager(db.messageDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    // -- Helpers --

    /**
     * Creates a [Message] whose content is exactly [tokenCount] tokens long (via [estimateTokens]),
     * i.e. [tokenCount] × [ai.champi.providers.api.CHARS_PER_TOKEN] characters.
     */
    private fun messageOf(
        role: MessageRole,
        tokenCount: Int,
        id: String = UUID.randomUUID().toString(),
    ) = Message(
        id = id,
        role = role,
        content = "a".repeat(tokenCount * 4), // 4 chars per token
        timestamp = System.currentTimeMillis(),
    )

    // -- Acceptance criterion 1: 200 messages + maxInputTokens=2048 → omission note present --

    @Test
    fun largeConversation_edgeLlmBudget_includesOmissionNote() {
        // Build 200 messages: 1 system + 199 alternating user/assistant, each 20 tokens.
        // Total would be 200 × 20 = 4000 tokens; 80% of 2048 = 1638 → context must be trimmed.
        val messages = buildList {
            add(messageOf(MessageRole.SYSTEM, tokenCount = 20))
            repeat(199) { i ->
                add(messageOf(if (i % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT, tokenCount = 20))
            }
        }

        val conversation = ContextWindowBuilder.build(messages, maxInputTokens = 2048)

        // The omission note must appear as the first turn.
        assertEquals(ConversationRole.SYSTEM, conversation.turns.first().role)
        assertEquals(ContextWindowBuilder.OMISSION_NOTE, conversation.turns.first().text)

        // The last turn must be the most recent user turn (turn at index 199, USER role).
        assertEquals(ConversationRole.USER, conversation.turns.last().role)

        // Total token count must not exceed the 80% budget.
        val budget = (2048 * 0.8).toInt()
        assertTrue(
            "Windowed conversation (${conversation.totalTokens} tokens) must fit within budget ($budget)",
            conversation.totalTokens <= budget,
        )
    }

    // -- Acceptance criterion 2: 10-token budget, 5 messages of 3 tokens → system + 2 most recent --

    @Test
    fun tenTokenBudget_fiveMessages_includesSystemAndTwoMostRecent() {
        // maxInputTokens = 13 → budget = floor(13 × 0.8) = floor(10.4) = 10 tokens.
        // Messages: system(3), user(3), assistant(3), user(3), assistant(3).
        // Must-include: system(3) + last message/assistant(3) = 6 tokens used.
        // Fill backward: 4th message/user(3): 6+3=9 ≤ 10, include.
        //                3rd message/assistant(3): 9+3=12 > 10, stop.
        // Result (excl. omission note): system + 4th + 5th = 3 turns.
        // With omission note prepended: 4 turns total.
        val maxInputTokens = 13

        val sys = messageOf(MessageRole.SYSTEM, tokenCount = 3)
        val usr1 = messageOf(MessageRole.USER, tokenCount = 3)
        val ast1 = messageOf(MessageRole.ASSISTANT, tokenCount = 3)
        val usr2 = messageOf(MessageRole.USER, tokenCount = 3)
        val ast2 = messageOf(MessageRole.ASSISTANT, tokenCount = 3)
        val messages = listOf(sys, usr1, ast1, usr2, ast2)

        val conversation = ContextWindowBuilder.build(messages, maxInputTokens)

        // Omission note is present because usr1 and ast1 were dropped.
        val turns = conversation.turns
        assertEquals(ConversationRole.SYSTEM, turns[0].role)
        assertEquals(ContextWindowBuilder.OMISSION_NOTE, turns[0].text)

        // Then the real system message.
        assertEquals(ConversationRole.SYSTEM, turns[1].role)
        assertEquals(sys.content, turns[1].text)

        // Then the 2 most recent conversational turns (usr2, ast2) in chronological order.
        assertEquals(ConversationRole.USER, turns[2].role)
        assertEquals(usr2.content, turns[2].text)
        assertEquals(ConversationRole.ASSISTANT, turns[3].role)
        assertEquals(ast2.content, turns[3].text)

        assertEquals("Expected 4 turns total (note + system + 2 recent)", 4, turns.size)
    }

    // -- Acceptance criterion 3: stored MessageEntity rows in Room are unchanged after windowing --

    @Test
    fun windowing_doesNotModifyStoredMessages() = runBlocking {
        // Insert 20 messages (1 system + 19 user/assistant) into Room.
        conversationManager.appendSystemMessage("System prompt")
        repeat(9) { i ->
            conversationManager.appendUserMessage("user message $i")
            conversationManager.appendAssistantMessage("assistant reply $i")
        }
        conversationManager.appendUserMessage("final user message")

        val countBefore = conversationManager.getMessageCount()

        // Run windowing with a very tight budget to force aggressive trimming.
        val messages = conversationManager.messages.first()
        ContextWindowBuilder.build(messages, maxInputTokens = 100)

        val countAfter = conversationManager.getMessageCount()
        assertEquals(
            "Windowing must not modify stored Room rows",
            countBefore,
            countAfter,
        )
    }

    // -- Acceptance criterion 4: different maxInputTokens → different-length Conversations --

    @Test
    fun differentProviderBudgets_produceDifferentLengthConversations() {
        // 50 messages of 10 tokens each → 500 tokens total.
        // Edge provider: maxInputTokens = 200 (budget = 160 tokens → fits ~16 messages).
        // Remote provider: maxInputTokens = 2000 (budget = 1600 tokens → fits all 50).
        val messages = buildList {
            add(messageOf(MessageRole.SYSTEM, tokenCount = 10))
            repeat(49) { i ->
                add(messageOf(if (i % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT, tokenCount = 10))
            }
        }

        val edgeConversation = ContextWindowBuilder.build(messages, maxInputTokens = 200)
        val remoteConversation = ContextWindowBuilder.build(messages, maxInputTokens = 2000)

        assertTrue(
            "Edge conversation (${edgeConversation.turns.size} turns) must be shorter than " +
                "remote conversation (${remoteConversation.turns.size} turns)",
            edgeConversation.turns.size < remoteConversation.turns.size,
        )

        // Edge context must respect its budget.
        val edgeBudget = (200 * 0.8).toInt()
        assertTrue(
            "Edge conversation tokens (${edgeConversation.totalTokens}) must fit in budget ($edgeBudget)",
            edgeConversation.totalTokens <= edgeBudget,
        )
    }

    // -- Additional: no truncation when all messages fit within the budget --

    @Test
    fun allMessagesFit_noOmissionNote() {
        // 3 messages of 5 tokens each; maxInputTokens = 100 (budget = 80) → all fit.
        val messages = listOf(
            messageOf(MessageRole.SYSTEM, tokenCount = 5),
            messageOf(MessageRole.USER, tokenCount = 5),
            messageOf(MessageRole.ASSISTANT, tokenCount = 5),
        )

        val conversation = ContextWindowBuilder.build(messages, maxInputTokens = 100)

        assertFalse(
            "Omission note must not appear when all messages fit in the budget",
            conversation.turns.any { it.text == ContextWindowBuilder.OMISSION_NOTE },
        )
        assertEquals(3, conversation.turns.size)
    }

    // -- Additional: empty message list produces empty Conversation --

    @Test
    fun emptyMessages_producesEmptyConversation() {
        val conversation = ContextWindowBuilder.build(emptyList(), maxInputTokens = 2048)
        assertTrue(conversation.turns.isEmpty())
    }
}
