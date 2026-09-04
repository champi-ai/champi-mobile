package ai.champi.assistant

import ai.champi.providers.api.BatteryClass
import ai.champi.providers.api.Cost
import ai.champi.providers.api.LatencyClass
import ai.champi.providers.api.Locality
import ai.champi.providers.api.PcmFrame
import ai.champi.providers.api.ProviderCapabilities
import ai.champi.providers.api.SttProvider
import ai.champi.providers.api.Transcript
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Controllable [SttProvider] test double for [RoutingPolicyTest]. */
class FakeSttProvider(
    override val id: String = "fake-stt",
    override val locality: Locality = Locality.EDGE,
    private val availableOverride: Boolean = true,
) : SttProvider {
    override val cost = Cost(LatencyClass.LOW, BatteryClass.LOW)
    override val capabilities = ProviderCapabilities(languages = listOf("en"), maxInputTokens = 0, supportsStreaming = true)

    override suspend fun available(): Boolean = availableOverride

    override fun transcribe(segment: Flow<PcmFrame>): Flow<Transcript> = emptyFlow()
}
