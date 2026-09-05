package ai.champi.app

import ai.champi.providers.api.Locality
import ai.champi.providers.api.Provider

/** Pipeline stage that a settings provider row belongs to. */
enum class ProviderStage { STT, LLM, TTS }

/**
 * Pure view model for a single provider row in the Providers settings section.
 *
 * @property id The provider's [Provider.id].
 * @property stage Which pipeline stage this provider handles.
 * @property localityLabel Human-readable locality label: `"edge"` or `"remote"`.
 * @property toggleDataStoreKey The DataStore key name used by [ai.champi.core.routing.RoutingSettingsRepository]
 *   for this provider's enable toggle (e.g. `"remote_llm_enabled"`). Used only for display/test
 *   verification; the UI looks up the actual [kotlinx.coroutines.flow.Flow] and setter via
 *   [ai.champi.core.routing.RoutingSettingsRepository] based on [stage] and [locality].
 * @property locality The provider's [Locality], preserved for composable routing-settings lookup.
 */
data class ProviderRowModel(
    val id: String,
    val stage: ProviderStage,
    val localityLabel: String,
    val toggleDataStoreKey: String,
    val locality: Locality,
)

/**
 * Maps a [provider] and its [stage] to a [ProviderRowModel].
 *
 * Pure function — no Android context or coroutines needed, making it straightforwardly unit-testable.
 */
fun providerRowModel(stage: ProviderStage, provider: Provider): ProviderRowModel = ProviderRowModel(
    id = provider.id,
    stage = stage,
    localityLabel = provider.locality.name.lowercase(),
    toggleDataStoreKey = "${provider.locality.name.lowercase()}_${stage.name.lowercase()}_enabled",
    locality = provider.locality,
)

/**
 * Builds an ordered list of [ProviderRowModel]s from the actual injected provider lists,
 * preserving stage ordering (STT → LLM → TTS) and provider order within each stage.
 */
fun buildProviderRows(
    sttProviders: List<ai.champi.providers.api.SttProvider>,
    llmProviders: List<ai.champi.providers.api.LlmProvider>,
    ttsProviders: List<ai.champi.providers.api.TtsProvider>,
): List<ProviderRowModel> = buildList {
    sttProviders.forEach { add(providerRowModel(ProviderStage.STT, it)) }
    llmProviders.forEach { add(providerRowModel(ProviderStage.LLM, it)) }
    ttsProviders.forEach { add(providerRowModel(ProviderStage.TTS, it)) }
}
