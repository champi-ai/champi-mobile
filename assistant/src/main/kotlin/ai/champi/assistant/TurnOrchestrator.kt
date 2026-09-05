package ai.champi.assistant

import ai.champi.core.context.ContextSnapshotSource
import ai.champi.core.context.toSystemMessage
import ai.champi.core.conversation.AttachmentType
import ai.champi.core.conversation.Message
import ai.champi.core.persistence.MessageRole
import ai.champi.core.persistence.QueuedTurnDao
import ai.champi.core.persistence.QueuedTurnEntity
import ai.champi.core.routing.RoutingSettingsRepository
import ai.champi.core.state.AppStateHolder
import ai.champi.core.state.CharacterState
import ai.champi.core.state.ConfirmationRequest
import ai.champi.core.state.ConversationEntry
import ai.champi.providers.api.ActionProvider
import ai.champi.providers.api.Conversation
import ai.champi.providers.api.ConversationRole
import ai.champi.providers.api.ConversationTurn
import ai.champi.providers.api.LlmEvent
import ai.champi.providers.api.ToolCall
import ai.champi.providers.api.ToolResult
import ai.champi.providers.api.ToolSpec
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
 * [LlmEvent.ToolCallEvent]s are dispatched sequentially to the matching [ActionProvider] from
 * [actionProviders]. Each call may require user confirmation (if [ToolSpec.requiresConfirmation]
 * is true) via [AppStateHolder.requestConfirmation] before proceeding. Tool calls to a provider
 * whose action-settings toggle is disabled return a graceful error [ToolResult] instead of
 * throwing. Multiple tool calls within one LLM turn are handled sequentially by the natural
 * structure of the event [collect] loop.
 */
@Singleton
open class TurnOrchestrator @Inject constructor(
    private val conversationManager: ConversationManager,
    private val routingPolicy: RoutingPolicy,
    private val routingSettingsRepository: RoutingSettingsRepository,
    private val queuedTurnDao: QueuedTurnDao,
    private val appStateHolder: AppStateHolder,
    private val actionProviders: List<ActionProvider>,
    private val contextSnapshotSource: ContextSnapshotSource,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var activeTurn: Job? = null
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Set to `true` the first time a [NoProviderException] fires within an unavailability window;
     * reset to `false` when a turn successfully completes (i.e. the window ends). Prevents the
     * character from entering ERROR repeatedly while multiple turns queue up during the same outage.
     */
    private val errorShownInWindow = AtomicBoolean(false)

    /** Flat map of tool name → (provider, spec) built once from [actionProviders]. */
    private val toolIndex: Map<String, Pair<ActionProvider, ToolSpec>> by lazy {
        buildMap {
            for (provider in actionProviders) {
                for (spec in provider.specs) {
                    put(spec.name, provider to spec)
                }
            }
        }
    }

    /** Combined list of all [ToolSpec]s across all registered [ActionProvider]s. */
    private val allToolSpecs: List<ToolSpec> by lazy { actionProviders.flatMap { it.specs } }

    /**
     * Cancels any in-flight turn and starts this one. Suspends only long enough to cancel the
     * previous turn cleanly (so it can never leave the character stuck in THINKING) — the new
     * turn itself runs asynchronously, since callers observe progress via [AppStateHolder.state]
     * rather than waiting for the full response.
     *
     * [attachmentUri] and [attachmentType] carry an optional share-sheet attachment (image or file)
     * submitted alongside [input]. Both default to `null` so all existing no-attachment call sites
     * compile and behave identically to pre-Phase-6 behavior. When an image attachment is present
     * and the selected LLM provider does not support image input
     * ([ProviderCapabilities.supportsImageInput] is `false`), a graceful error assistant message is
     * returned instead of calling [LlmProvider.complete] — the attachment is still persisted to
     * [MessageEntity] so the conversation record is accurate.
     */
    open suspend fun submitText(
        input: String,
        attachmentUri: String? = null,
        attachmentType: AttachmentType? = null,
    ) {
        activeTurn?.cancelAndJoin()
        activeTurn = scope.launch { runTurn(input, attachmentUri, attachmentType) }
    }

    /** Resets the per-outage-window ERROR-flash guard. Called by [QueueReplayWorker] after a
     *  successful replay so the next genuine outage flashes ERROR again. */
    fun resetErrorWindow() {
        errorShownInWindow.set(false)
    }

    private suspend fun runTurn(
        input: String,
        attachmentUri: String? = null,
        attachmentType: AttachmentType? = null,
    ) {
        appStateHolder.appendConversationEntry(ConversationEntry(id = UUID.randomUUID().toString(), text = input, fromUser = true))
        conversationManager.appendUserMessage(input, attachmentUri, attachmentType)

        // Fetch the raw message history and this turn's ephemeral context-signal system message
        // (if any signal is enabled) once, so routing and windowing see the same input.
        val messages = conversationManager.messages.first()
        val contextMessage = contextSnapshotSource.readSnapshot().toSystemMessage()
        if (contextMessage != null) Log.d(TAG, "Context system message: ${contextMessage.content}")
        val messagesWithContext = if (contextMessage != null) messages + contextMessage else messages

        // Unwindowed context for the routing heuristic — RoutingPolicy.fits() needs total tokens.
        val routingCtx = Conversation(messagesWithContext.map { it.toConversationTurn() })
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

        // If this turn carries an image attachment and the selected provider cannot process images,
        // skip the LLM call entirely and return a graceful assistant message. The user message is
        // already persisted (with the attachment URI) so the conversation record is accurate.
        if (attachmentType == AttachmentType.IMAGE && !llmProvider.capabilities.supportsImageInput) {
            val errorText = "I can't process images on this device."
            appStateHolder.appendConversationEntry(ConversationEntry(id = UUID.randomUUID().toString(), text = errorText, fromUser = false))
            conversationManager.appendAssistantMessage(errorText)
            appStateHolder.setCharacterState(CharacterState.IDLE)
            return
        }

        appStateHolder.setCharacterState(CharacterState.THINKING)

        // Windowed context for the actual provider call, trimmed to the selected provider's
        // budget. The context-signal message (if any) is a SYSTEM turn, so ContextWindowBuilder's
        // always-include-system-messages rule keeps it in regardless of trimming.
        val windowedCtx = ContextWindowBuilder.build(messagesWithContext, llmProvider.capabilities.maxInputTokens)

        val entryId = UUID.randomUUID().toString()
        var accumulated = ""
        var entryAppended = false

        try {
            llmProvider.complete(windowedCtx, tools = allToolSpecs).collect { event ->
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
                    is LlmEvent.ToolCallEvent -> {
                        val result = dispatchToolCall(event.call)
                        Log.d(TAG, "ToolResult for ${event.call.name}: isError=${result.isError} json=${result.resultJson}")
                    }
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

    /**
     * Handles a single [ToolCall] from the LLM event stream:
     * 1. Looks up the provider and spec by tool name; returns an error [ToolResult] if not found.
     * 2. If the provider's action-settings toggle is off (detected by invoking the provider which
     *    checks internally), produces a graceful error without throwing.
     * 3. If [ToolSpec.requiresConfirmation] is true, presents a confirmation dialog via
     *    [AppStateHolder.requestConfirmation] and waits for the user's response; declines produce
     *    a graceful [ToolResult] rather than silently dropping the call.
     * 4. Calls [ActionProvider.invoke] and returns the result.
     *
     * This method is intentionally sequential and called from within the [collect] loop, so
     * multiple tool calls in one LLM turn are never concurrent.
     */
    private suspend fun dispatchToolCall(call: ToolCall): ToolResult {
        val (provider, spec) = toolIndex[call.name]
            ?: return ToolResult(
                callId = call.id,
                resultJson = json.encodeToString(mapOf("error" to "Unknown tool: ${call.name}")),
                isError = true,
            )

        if (spec.requiresConfirmation) {
            val request = ConfirmationRequest(
                toolName = call.name,
                prompt = spec.description,
            )
            val approved = appStateHolder.requestConfirmation(request)
            if (!approved) {
                return ToolResult(
                    callId = call.id,
                    resultJson = json.encodeToString(mapOf("error" to "User declined the action.")),
                    isError = true,
                )
            }
        }

        return provider.invoke(call)
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
    attachmentUri = attachmentUri,
    attachmentType = attachmentType?.name,
)
