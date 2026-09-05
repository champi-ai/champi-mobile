package ai.champi.providers.api

import kotlinx.coroutines.flow.Flow

enum class ConversationRole { SYSTEM, USER, ASSISTANT }

data class ConversationTurn(val role: ConversationRole, val text: String)

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
