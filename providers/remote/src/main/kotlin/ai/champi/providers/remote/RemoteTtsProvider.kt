package ai.champi.providers.remote

import ai.champi.providers.api.AudioChunk
import ai.champi.providers.api.BatteryClass
import ai.champi.providers.api.Cost
import ai.champi.providers.api.LatencyClass
import ai.champi.providers.api.Locality
import ai.champi.providers.api.ProviderCapabilities
import ai.champi.providers.api.TtsProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stub for a remote text-to-speech provider (e.g. an HTTP TTS API that streams synthesized audio).
 *
 * Voice-over-remote transport is out of scope for the current milestone. This stub satisfies the
 * [TtsProvider] interface contract so the Hilt graph and routing policy can treat remote TTS as a
 * legitimate (but unavailable) provider alongside the edge TTS implementation, without any
 * `is RemoteTtsProvider` type checks at the call site.
 *
 * [available] returns `false` always — see the TODO below.
 * [synthesize] returns an empty flow so callers that proceed despite `available() == false`
 * receive no audio chunks and do not throw, consistent with how other unimplemented provider
 * paths in this codebase signal "nothing to produce" (empty flow rather than an exception).
 */
@Singleton
class RemoteTtsProvider @Inject constructor() : TtsProvider {

    override val id = "remote-tts"
    override val locality = Locality.REMOTE
    override val cost = Cost(LatencyClass.HIGH, BatteryClass.LOW)

    // A realistic remote TTS service typically synthesizes any language and can stream audio
    // chunks over HTTP chunked transfer or a WebSocket. The values below reflect a plausible
    // capability profile so the routing policy can filter by language without special-casing IDs.
    override val capabilities = ProviderCapabilities(
        languages = listOf("en", "es"),
        maxInputTokens = 4096,
        supportsStreaming = true,
    )

    // TODO(transport): implement HTTP/WebSocket transport to a remote TTS endpoint;
    // until then this provider is always unavailable so the router falls through to edge TTS.
    override suspend fun available(): Boolean = false

    override fun synthesize(text: Flow<String>): Flow<AudioChunk> = emptyFlow()
}
