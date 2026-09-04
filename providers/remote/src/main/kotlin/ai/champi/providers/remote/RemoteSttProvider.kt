package ai.champi.providers.remote

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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stub for a remote speech-to-text provider (e.g. Whisper API or similar HTTP endpoint).
 *
 * Voice-over-remote transport is out of scope for the current milestone. This stub satisfies the
 * [SttProvider] interface contract so the Hilt graph and routing policy can treat remote STT as a
 * legitimate (but unavailable) provider alongside the edge STT implementation, without any
 * `is RemoteSttProvider` type checks at the call site.
 *
 * [available] returns `false` always — see the TODO below.
 * [transcribe] returns an empty flow so callers that proceed despite `available() == false`
 * receive no transcripts and do not throw, consistent with how other unimplemented provider
 * paths in this codebase signal "nothing to produce" (empty flow rather than an exception).
 */
@Singleton
class RemoteSttProvider @Inject constructor() : SttProvider {

    override val id = "remote-stt"
    override val locality = Locality.REMOTE
    override val cost = Cost(LatencyClass.HIGH, BatteryClass.LOW)

    // A realistic remote STT service (e.g. Whisper) typically supports multiple languages and
    // streams interim results over a websocket. The values below reflect a plausible capability
    // profile so the routing policy can filter by language without special-casing provider IDs.
    override val capabilities = ProviderCapabilities(
        languages = listOf("en", "es"),
        maxInputTokens = Int.MAX_VALUE, // audio duration is not token-bounded
        supportsStreaming = true,
    )

    // TODO(transport): implement HTTP/WebSocket transport to a remote Whisper endpoint;
    // until then this provider is always unavailable so the router falls through to edge STT.
    override suspend fun available(): Boolean = false

    override fun transcribe(segment: Flow<PcmFrame>): Flow<Transcript> = emptyFlow()
}
