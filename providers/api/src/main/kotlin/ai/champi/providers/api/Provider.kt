package ai.champi.providers.api

/** Where a [Provider] implementation actually runs. */
enum class Locality { EDGE, REMOTE }

enum class LatencyClass { LOW, MEDIUM, HIGH }

enum class BatteryClass { LOW, MEDIUM, HIGH }

/** Rough cost heuristics [ai.champi.providers.api routing] uses to prefer edge over remote. */
data class Cost(val latencyClass: LatencyClass, val batteryClass: BatteryClass)

/** What a provider can actually handle, so the router can choose without special-casing IDs. */
data class ProviderCapabilities(
    val languages: List<String>,
    val maxInputTokens: Int,
    val supportsStreaming: Boolean,
)

/**
 * Common shape for every routable pipeline stage (wake word, VAD, STT, LLM, TTS). [ActionProvider]
 * is deliberately not one of these — device actions aren't edge/remote-routable the same way.
 */
interface Provider {
    val id: String
    val locality: Locality
    val cost: Cost
    val capabilities: ProviderCapabilities
    suspend fun available(): Boolean
}
