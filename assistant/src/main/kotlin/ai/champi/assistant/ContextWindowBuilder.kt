package ai.champi.assistant

import ai.champi.core.conversation.Message
import ai.champi.core.persistence.MessageRole
import ai.champi.providers.api.Conversation
import ai.champi.providers.api.ConversationRole
import ai.champi.providers.api.ConversationTurn
import ai.champi.providers.api.estimateTokens

/**
 * Builds a [Conversation] from a raw message list that fits within a provider's declared context
 * window, so the orchestrator — not the model — controls what each provider sees.
 *
 * Algorithm (per §3.3):
 * 1. System messages and the most recent conversational turn are always included.
 * 2. Starting from the second-most-recent turn, messages are appended in reverse-chronological
 *    order until the [WINDOW_BUDGET_FRACTION] × `maxInputTokens` token budget is exhausted.
 * 3. When any messages are dropped a "Earlier conversation omitted for length" system note is
 *    prepended so the provider knows the context is incomplete.
 *
 * Token counting uses [estimateTokens] (4 chars ≈ 1 token), the same approximation that
 * [RoutingPolicy.fits] and [Conversation.totalTokens] use, keeping all token estimates
 * consistent across the pipeline.
 *
 * This object is stateless and has no Android or Hilt dependency; it may be called from any
 * coroutine context.
 */
object ContextWindowBuilder {

    /** Fraction of `maxInputTokens` to use as the effective token budget. */
    private const val WINDOW_BUDGET_FRACTION = 0.8

    /**
     * System note injected when older turns are dropped from the context window.
     * Kept intentionally concise so it occupies few tokens itself.
     */
    internal const val OMISSION_NOTE = "Earlier conversation omitted for length"

    /**
     * Builds the windowed [Conversation] for a provider with the given [maxInputTokens].
     *
     * @param messages Full message history from [ConversationManager], chronologically ordered
     *   (oldest first). This list is never mutated — only an in-memory transformation is returned.
     * @param maxInputTokens The provider's declared maximum context size in tokens.
     * @return A [Conversation] whose total token count (per [estimateTokens]) does not exceed
     *   [WINDOW_BUDGET_FRACTION] × [maxInputTokens], subject to the always-include guarantees.
     */
    fun build(messages: List<Message>, maxInputTokens: Int): Conversation {
        val budget = (maxInputTokens * WINDOW_BUDGET_FRACTION).toInt()

        val systemMessages = messages.filter { it.role == MessageRole.SYSTEM }
        val nonSystemMessages = messages.filter { it.role != MessageRole.SYSTEM }

        // Tokens consumed by always-included content.
        var tokensUsed = systemMessages.sumOf { estimateTokens(it.content) }
        val mostRecentTurn = nonSystemMessages.lastOrNull()
        if (mostRecentTurn != null) tokensUsed += estimateTokens(mostRecentTurn.content)

        // Fill backward through the remaining conversational turns (newest-first iteration so we
        // preserve the most recent context when the budget runs out).
        val candidates = nonSystemMessages.dropLast(if (mostRecentTurn != null) 1 else 0).reversed()
        val additionalIncluded = mutableListOf<Message>()
        for (msg in candidates) {
            val cost = estimateTokens(msg.content)
            if (tokensUsed + cost > budget) break
            additionalIncluded.add(0, msg) // re-insert at front to restore chronological order
            tokensUsed += cost
        }

        val truncated = additionalIncluded.size < candidates.size

        val turns = buildList {
            if (truncated) add(ConversationTurn(ConversationRole.SYSTEM, OMISSION_NOTE))
            systemMessages.forEach { add(it.toConversationTurn()) }
            additionalIncluded.forEach { add(it.toConversationTurn()) }
            mostRecentTurn?.let { add(it.toConversationTurn()) }
        }
        return Conversation(turns)
    }

    private fun Message.toConversationTurn() = ConversationTurn(
        role = when (role) {
            MessageRole.USER -> ConversationRole.USER
            MessageRole.ASSISTANT -> ConversationRole.ASSISTANT
            MessageRole.SYSTEM -> ConversationRole.SYSTEM
        },
        text = content,
    )
}
