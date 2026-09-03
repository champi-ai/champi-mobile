package ai.champi.providers.api

import kotlinx.coroutines.flow.Flow

enum class ConversationRole { SYSTEM, USER, ASSISTANT }

data class ConversationTurn(val role: ConversationRole, val text: String)

/**
 * Prompt context handed to [LlmProvider.complete]. Distinct from `:core`'s `ConversationEntry` —
 * that's UI-facing display state (what the panel renders); this is the provider-facing prompt
 * shape the assistant layer builds from it.
 */
data class Conversation(val turns: List<ConversationTurn>)

sealed class LlmEvent {
    data class Token(val text: String) : LlmEvent()
    data class ToolCallEvent(val call: ToolCall) : LlmEvent()
    data class Done(val finishReason: String) : LlmEvent()
}

interface LlmProvider : Provider {
    fun complete(ctx: Conversation, tools: List<ToolSpec>): Flow<LlmEvent>
}
