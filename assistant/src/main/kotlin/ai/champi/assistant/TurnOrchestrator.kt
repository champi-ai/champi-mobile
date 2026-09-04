package ai.champi.assistant

import ai.champi.core.conversation.Message
import ai.champi.core.persistence.MessageRole
import ai.champi.core.state.AppStateHolder
import ai.champi.core.state.CharacterState
import ai.champi.core.state.ConversationEntry
import ai.champi.providers.api.Conversation
import ai.champi.providers.api.ConversationRole
import ai.champi.providers.api.ConversationTurn
import ai.champi.providers.api.LlmEvent
import ai.champi.providers.api.LlmProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives one text-turn's full lifecycle: persist the user message, call the LLM, stream tokens
 * into [AppStateHolder]'s conversation list, persist the finished assistant message, and step
 * [CharacterState] through THINKING/ERROR back to IDLE. Tool calls are surfaced as [LlmEvent]s but
 * not yet dispatched to an `ActionProvider` — that's issue #40's tool-call flow.
 */
@Singleton
class TurnOrchestrator @Inject constructor(
    private val conversationManager: ConversationManager,
    private val llmProvider: LlmProvider,
    private val appStateHolder: AppStateHolder,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var activeTurn: Job? = null

    /**
     * Cancels any in-flight turn and starts this one. Suspends only long enough to cancel the
     * previous turn cleanly (so it can never leave the character stuck in THINKING) — the new
     * turn itself runs asynchronously, since callers observe progress via [AppStateHolder.state]
     * rather than waiting for the full response.
     */
    suspend fun submitText(input: String) {
        activeTurn?.cancelAndJoin()
        activeTurn = scope.launch { runTurn(input) }
    }

    private suspend fun runTurn(input: String) {
        appStateHolder.appendConversationEntry(ConversationEntry(id = UUID.randomUUID().toString(), text = input, fromUser = true))
        conversationManager.appendUserMessage(input)

        if (!llmProvider.available()) {
            appStateHolder.setCharacterState(CharacterState.ERROR)
            appStateHolder.appendConversationEntry(
                ConversationEntry(id = UUID.randomUUID().toString(), text = "Champi's language model isn't available right now.", fromUser = false),
            )
            delay(ERROR_FLASH_MS)
            appStateHolder.setCharacterState(CharacterState.IDLE)
            return
        }

        appStateHolder.setCharacterState(CharacterState.THINKING)

        val entryId = UUID.randomUUID().toString()
        var accumulated = ""
        var entryAppended = false

        llmProvider.complete(buildConversationContext(), tools = emptyList()).collect { event ->
            when (event) {
                is LlmEvent.Token -> {
                    accumulated += event.text
                    if (!entryAppended) {
                        appStateHolder.appendConversationEntry(ConversationEntry(id = entryId, text = accumulated, fromUser = false))
                        entryAppended = true
                    } else {
                        appStateHolder.updateConversationEntry(entryId, accumulated)
                    }
                }
                is LlmEvent.ToolCallEvent -> Unit // dispatched to ActionProvider in #40
                is LlmEvent.Done -> {
                    if (accumulated.isNotEmpty()) conversationManager.appendAssistantMessage(accumulated)
                    appStateHolder.setCharacterState(CharacterState.IDLE)
                }
            }
        }
    }

    private suspend fun buildConversationContext(): Conversation {
        val turns = conversationManager.messages.first().map { it.toConversationTurn() }
        return Conversation(turns)
    }

    private companion object {
        const val ERROR_FLASH_MS = 500L
    }
}

private fun Message.toConversationTurn() = ConversationTurn(
    role = when (role) {
        MessageRole.USER -> ConversationRole.USER
        MessageRole.ASSISTANT -> ConversationRole.ASSISTANT
        MessageRole.SYSTEM -> ConversationRole.SYSTEM
    },
    text = content,
)
