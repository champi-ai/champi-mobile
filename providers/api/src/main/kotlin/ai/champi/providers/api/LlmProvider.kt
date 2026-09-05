package ai.champi.providers.api

import kotlinx.coroutines.flow.Flow

enum class ConversationRole { SYSTEM, USER, ASSISTANT }

/**
 * A single turn in the conversation context handed to [LlmProvider.complete].
 *
 * [attachmentUri] and [attachmentType] carry share-sheet attachment metadata for turns that
 * include an image or file. Both default to `null` so text-only turns are unaffected. Providers
 * that support image input (see [ProviderCapabilities.supportsImageInput]) are responsible for
 * reading and encoding the file at [attachmentUri]; providers that do not support image input must
 * never receive a turn with an image attachment — [TurnOrchestrator] enforces this by returning a
 * graceful error instead of calling [LlmProvider.complete] in that case.
 *
 * [attachmentType] is stored as a raw string matching the
 * [ai.champi.core.conversation.AttachmentType] enum name (e.g. `"IMAGE"`, `"FILE"`) to avoid a
 * hard dependency from `:providers:api` onto `:core`.
 */
data class ConversationTurn(
    val role: ConversationRole,
    val text: String,
    val attachmentUri: String? = null,
    val attachmentType: String? = null,
)

/**
 * Prompt context handed to [LlmProvider.complete]. Distinct from `:core`'s `ConversationEntry` —
 * that's UI-facing display state (what the panel renders); this is the provider-facing prompt
 * shape the assistant layer builds from it.
 */
data class Conversation(val turns: List<ConversationTurn>) {
    /**
     * Rough token count of all turns in this context, estimated via [estimateTokens].
     * Used by the routing heuristic to decide whether a request fits within the edge model's
     * declared [ProviderCapabilities.maxInputTokens] budget.
     */
    val totalTokens: Int get() = turns.sumOf { estimateTokens(it.text) }
}

sealed class LlmEvent {
    data class Token(val text: String) : LlmEvent()
    data class ToolCallEvent(val call: ToolCall) : LlmEvent()
    data class Done(val finishReason: String) : LlmEvent()
}

interface LlmProvider : Provider {
    fun complete(ctx: Conversation, tools: List<ToolSpec>): Flow<LlmEvent>
}
