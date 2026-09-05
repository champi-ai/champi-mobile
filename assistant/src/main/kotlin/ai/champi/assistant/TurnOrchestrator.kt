package ai.champi.assistant

import ai.champi.core.context.ContextSnapshotSource
import ai.champi.core.context.toSystemMessage
import ai.champi.core.conversation.Message
import ai.champi.core.persistence.MessageRole
import ai.champi.core.persistence.QueuedTurnDao
import ai.champi.core.persistence.QueuedTurnEntity
import ai.champi.core.routing.RoutingSettingsRepository
import ai.champi.core.state.AppStateHolder
import ai.champi.core.state.CharacterState
import ai.champi.core.state.ConversationEntry
import ai.champi.providers.api.Conversation
import ai.champi.providers.api.ConversationRole
import ai.champi.providers.api.ConversationTurn
import ai.champi.providers.api.LlmEvent
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives one text-turn's full lifecycle: persist the user message, select an LLM provider via
 * [RoutingPolicy], stream tokens into [AppStateHolder]'s conversation list, persist the finished
 * assistant message, and step [CharacterState] through THINKING/ERROR back to IDLE.
 *
 * When [RoutingPolicy.selectLlm] throws [NoProviderException] the turn is written to
 * [QueuedTurnDao] for later replay by [QueueReplayWorker]. The character flashes ERROR once per
 * unavailability window (tracked by [errorShownInWindow]) so multiple queued items during the
 * same outage don't repeatedly show the error state.
 *
 * Tool calls are surfaced as [LlmEvent]s but not yet dispatched to an `ActionProvider` — that's
 * issue #40's tool-call flow.
 */
@Singleton
open class TurnOrchestrator @Inject constructor(
    private val conversationManager: ConversationManager,
    private val routingPolicy: RoutingPolicy,
    private val routingSettingsRepository: RoutingSettingsRepository,
    private val queuedTurnDao: QueuedTurnDao,
    private val appStateHolder: AppStateHolder,
    private val contextSnapshotSource: ContextSnapshotSource,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var activeTurn: Job? = null

    /**
     * Set to `true` the first time a [NoProviderException] fires within an unavailability window;
     * reset to `false` when a turn successfully completes (i.e. the window ends). Prevents the
     * character from entering ERROR repeatedly while multiple turns queue up during the same outage.
     */
    private val errorShownInWindow = AtomicBoolean(false)

    /**
     * Cancels any in-flight turn and starts this one. Suspends only long enough to cancel the
     * previous turn cleanly (so it can never leave the character stuck in THINKING) — the new
     * turn itself runs asynchronously, since callers observe progress via [AppStateHolder.state]
     * rather than waiting for the full response.
     */
    open suspend fun submitText(input: String) {
        activeTurn?.cancelAndJoin()
        activeTurn = scope.launch { runTurn(input) }
    }

    /** Resets the per-outage-window ERROR-flash guard. Called by [QueueReplayWorker] after a
     *  successful replay so the next genuine outage flashes ERROR again. */
    fun resetErrorWindow() {
        errorShownInWindow.set(false)
    }

    private suspend fun runTurn(input: String) {
        appStateHolder.appendConversationEntry(ConversationEntry(id = UUID.randomUUID().toString(), text = input, fromUser = true))
        conversationManager.appendUserMessage(input)

        // Unwindowed context for the routing heuristic — RoutingPolicy.fits() needs total tokens.
        val routingCtx = buildConversationContext()
        val edgeOnly = routingSettingsRepository.edgeOnlyMode.first()

        val llmProvider = try {
            routingPolicy.selectLlm(routingCtx, input, edgeOnly)
        } catch (e: NoProviderException) {
            val conversationId = conversationManager.getActiveConversationId()
            val messageCount = conversationManager.getMessageCount()
            queuedTurnDao.insert(
                QueuedTurnEntity(
                    conversationId = conversationId,
                    inputText = input,
                    enqueuedAt = System.currentTimeMillis(),
                    messageCountAtEnqueue = messageCount,
                ),
            )
            if (errorShownInWindow.compareAndSet(false, true)) {
                appStateHolder.setCharacterState(CharacterState.ERROR)
                delay(ERROR_FLASH_MS)
                appStateHolder.setCharacterState(CharacterState.IDLE)
            }
            return
        }

        appStateHolder.setCharacterState(CharacterState.THINKING)

        // Windowed context for the actual provider call, trimmed to the selected provider's budget.
        val windowedCtx = buildWindowedContext(llmProvider.capabilities.maxInputTokens)

        val entryId = UUID.randomUUID().toString()
        var accumulated = ""
        var entryAppended = false

        try {
            llmProvider.complete(windowedCtx, tools = emptyList()).collect { event ->
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
                        errorShownInWindow.set(false)
                        appStateHolder.setCharacterState(CharacterState.IDLE)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e // a newer submitText() cancelling this turn — not a provider failure
        } catch (e: Exception) {
            // A network/parse failure mid-stream (e.g. the remote endpoint dropped the
            // connection) must still resolve back to IDLE, same as the unavailable-provider path.
            val message = if (entryAppended) {
                "Champi lost connection while responding."
            } else {
                "Champi couldn't reach its language model."
            }
            appStateHolder.setCharacterState(CharacterState.ERROR)
            appStateHolder.appendConversationEntry(ConversationEntry(id = UUID.randomUUID().toString(), text = message, fromUser = false))
            delay(ERROR_FLASH_MS)
            appStateHolder.setCharacterState(CharacterState.IDLE)
        }
    }

    /** Builds an unwindowed [Conversation] for the routing heuristic. */
    private suspend fun buildConversationContext(): Conversation {
        val turns = conversationManager.messages.first().map { it.toConversationTurn() }.toMutableList()

        // Read a fresh context snapshot for this turn. If any signal is enabled and the
        // corresponding permission is granted, a system message is prepended to the conversation.
        // This is an ephemeral prepend — it is NOT persisted to ConversationEntity.
        val contextMessage = contextSnapshotSource.readSnapshot().toSystemMessage()
        if (contextMessage != null) {
            Log.d(TAG, "Context system message: ${contextMessage.content}")
            turns.add(0, contextMessage.toConversationTurn())
        }

        return Conversation(turns)
    }

    /**
     * Builds a context-windowed [Conversation] for the actual provider call, trimmed to fit
     * within [maxInputTokens] via [ContextWindowBuilder].
     */
    private suspend fun buildWindowedContext(maxInputTokens: Int): Conversation {
        val messages = conversationManager.messages.first()
        return ContextWindowBuilder.build(messages, maxInputTokens)
    }

    private companion object {
        const val ERROR_FLASH_MS = 2000L
        const val TAG = "TurnOrchestrator"
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
