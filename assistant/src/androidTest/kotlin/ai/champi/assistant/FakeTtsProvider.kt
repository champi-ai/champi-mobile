package ai.champi.assistant

import ai.champi.providers.api.AudioChunk
import ai.champi.providers.api.BatteryClass
import ai.champi.providers.api.Cost
import ai.champi.providers.api.LatencyClass
import ai.champi.providers.api.Locality
import ai.champi.providers.api.ProviderCapabilities
import ai.champi.providers.api.TtsProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Controllable [TtsProvider] test double for [RoutingPolicyTest]. */
class FakeTtsProvider(
    override val id: String = "fake-tts",
    override val locality: Locality = Locality.EDGE,
    private val availableOverride: Boolean = true,
) : TtsProvider {
    override val cost = Cost(LatencyClass.LOW, BatteryClass.LOW)
    override val capabilities = ProviderCapabilities(languages = listOf("en"), maxInputTokens = 0, supportsStreaming = true)

    override suspend fun available(): Boolean = availableOverride

    override fun synthesize(text: Flow<String>): Flow<AudioChunk> = emptyFlow()
}
