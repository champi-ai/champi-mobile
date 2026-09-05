package ai.champi.assistant

import ai.champi.providers.api.BatteryClass
import ai.champi.providers.api.Conversation
import ai.champi.providers.api.Cost
import ai.champi.providers.api.LatencyClass
import ai.champi.providers.api.LlmEvent
import ai.champi.providers.api.LlmProvider
import ai.champi.providers.api.Locality
import ai.champi.providers.api.ProviderCapabilities
import ai.champi.providers.api.ToolCall
import ai.champi.providers.api.ToolSpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Scriptable [LlmProvider] test double: yields [tokens] one at a time (each after [tokenDelayMs])
 * then any [toolCalls] (as [LlmEvent.ToolCallEvent]) then a [LlmEvent.Done].
 * Set [availableOverride] to false to exercise the unavailable path.
 */
class FakeLlmProvider(
    private val tokens: List<String> = listOf("Hel", "lo", "!"),
    private val tokenDelayMs: Long = 20L,
    private val availableOverride: Boolean = true,
    private val toolCalls: List<ToolCall> = emptyList(),
) : LlmProvider {
    override val id = "fake-llm"
    override val locality = Locality.EDGE
    override val cost = Cost(LatencyClass.LOW, BatteryClass.LOW)
    override val capabilities = ProviderCapabilities(languages = listOf("en"), maxInputTokens = 4096, supportsStreaming = true)

    override suspend fun available(): Boolean = availableOverride

    override fun complete(ctx: Conversation, tools: List<ToolSpec>): Flow<LlmEvent> = flow {
        for (token in tokens) {
            delay(tokenDelayMs)
            emit(LlmEvent.Token(token))
        }
        for (call in toolCalls) {
            emit(LlmEvent.ToolCallEvent(call))
        }
        emit(LlmEvent.Done("stop"))
    }
}
