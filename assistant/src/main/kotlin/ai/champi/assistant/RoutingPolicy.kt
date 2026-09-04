package ai.champi.assistant

import ai.champi.core.persistence.RoutingDecisionDao
import ai.champi.core.persistence.RoutingDecisionEntity
import ai.champi.core.persistence.RoutingReason
import ai.champi.core.persistence.RoutingStage
import ai.champi.core.routing.RoutingSettingsRepository
import ai.champi.providers.api.Conversation
import ai.champi.providers.api.Locality
import ai.champi.providers.api.LlmProvider
import ai.champi.providers.api.SttProvider
import ai.champi.providers.api.TtsProvider
import kotlinx.coroutines.flow.first

/** Characters-per-token approximation used for all token estimates in routing heuristics. */
private const val CHARS_PER_TOKEN = 4

/** A request must consume less than this fraction of a provider's maxInputTokens to be considered fitting. */
private const val FITS_BUDGET_FRACTION = 0.8

private fun estimateTokens(text: String): Int = text.length / CHARS_PER_TOKEN

/**
 * Edge-first provider selection for the three routable pipeline stages (STT, LLM, TTS) per §3.3.
 *
 * For the LLM stage, [selectLlm] runs the full four-step algorithm:
 * 1. `edgeOnly` flag → edge unconditionally (skips the fits heuristic).
 * 2. Edge provider `available()` and `fits(ctx, input)` → edge.
 * 3. Remote provider `available()` → remote fallback.
 * 4. No provider available → log DEGRADE and throw [NoProviderException].
 *
 * For STT and TTS, selection is simpler: first available provider in locality order (edge
 * preferred, then remote), or [NoProviderException] if none is available.
 *
 * Every call writes one [RoutingDecisionEntity] row before returning (or throwing), enabling
 * offline heuristic tuning from real usage logs.
 *
 * Per-locality enable toggles from [RoutingSettingsRepository] are respected: a provider that is
 * `available()` but whose DataStore toggle is disabled is treated as unavailable for selection.
 */
class RoutingPolicy(
    private val llmProviders: List<LlmProvider>,
    private val sttProviders: List<SttProvider>,
    private val ttsProviders: List<TtsProvider>,
    private val routingDecisionDao: RoutingDecisionDao,
    private val routingSettingsRepository: RoutingSettingsRepository,
) {
    /**
     * Selects an [LlmProvider] for the given context and input using the §3.3 four-step algorithm.
     *
     * @param ctx current conversation context, used for the fits token-budget heuristic.
     * @param input raw user input string for this turn.
     * @param edgeOnly when `true`, skips the fits check and always returns an edge provider if one
     *   is available; if no edge provider is available in this mode, [NoProviderException] is thrown.
     * @throws NoProviderException if no provider can serve the request (step 4 / DEGRADE).
     */
    suspend fun selectLlm(ctx: Conversation, input: String, edgeOnly: Boolean): LlmProvider {
        val inputTokenEstimate = estimateTokens(input)
        val edgeLlmEnabled = routingSettingsRepository.edgeLlmEnabled.first()
        val remoteLlmEnabled = routingSettingsRepository.remoteLlmEnabled.first()

        val edgeCandidates = llmProviders.filter { it.locality == Locality.EDGE && edgeLlmEnabled }
        val remoteCandidates = llmProviders.filter { it.locality == Locality.REMOTE && remoteLlmEnabled }

        // Step 1: edgeOnly → edge unconditionally, regardless of input size.
        if (edgeOnly) {
            val edge = edgeCandidates.firstOrNull { it.available() }
            if (edge != null) {
                log(RoutingStage.LLM, edge.id, edge.locality.name, RoutingReason.EDGE_ONLY, inputTokenEstimate)
                return edge
            }
            log(RoutingStage.LLM, "", "", RoutingReason.DEGRADE, inputTokenEstimate)
            throw NoProviderException("edgeOnly=true but no edge LLM provider is available")
        }

        // Step 2: edge available and request fits within declared capability budget.
        val fittingEdge = edgeCandidates.firstOrNull { it.available() && fits(it, ctx, input) }
        if (fittingEdge != null) {
            log(RoutingStage.LLM, fittingEdge.id, fittingEdge.locality.name, RoutingReason.EDGE_FIT, inputTokenEstimate)
            return fittingEdge
        }

        // Step 3: remote available.
        val remote = remoteCandidates.firstOrNull { it.available() }
        if (remote != null) {
            log(RoutingStage.LLM, remote.id, remote.locality.name, RoutingReason.REMOTE_FALLBACK, inputTokenEstimate)
            return remote
        }

        // Step 4: degrade — no provider available.
        log(RoutingStage.LLM, "", "", RoutingReason.DEGRADE, inputTokenEstimate)
        throw NoProviderException("No LLM provider is available")
    }

    /**
     * Selects an [SttProvider] using availability only (no fits heuristic for STT): edge is
     * preferred; remote is the fallback; [NoProviderException] if neither is available.
     *
     * @throws NoProviderException if no STT provider is available.
     */
    suspend fun selectStt(): SttProvider {
        val edgeSttEnabled = routingSettingsRepository.edgeSttEnabled.first()
        val remoteSttEnabled = routingSettingsRepository.remoteSttEnabled.first()

        val ordered = buildList {
            if (edgeSttEnabled) addAll(sttProviders.filter { it.locality == Locality.EDGE })
            if (remoteSttEnabled) addAll(sttProviders.filter { it.locality == Locality.REMOTE })
        }

        val selected = ordered.firstOrNull { it.available() }
        if (selected != null) {
            val reason = if (selected.locality == Locality.EDGE) RoutingReason.EDGE_FIT else RoutingReason.REMOTE_FALLBACK
            log(RoutingStage.STT, selected.id, selected.locality.name, reason, 0)
            return selected
        }

        log(RoutingStage.STT, "", "", RoutingReason.DEGRADE, 0)
        throw NoProviderException("No STT provider is available")
    }

    /**
     * Selects a [TtsProvider] using availability only (no fits heuristic for TTS): edge is
     * preferred; remote is the fallback; [NoProviderException] if neither is available.
     *
     * @throws NoProviderException if no TTS provider is available.
     */
    suspend fun selectTts(): TtsProvider {
        val edgeTtsEnabled = routingSettingsRepository.edgeTtsEnabled.first()
        val remoteTtsEnabled = routingSettingsRepository.remoteTtsEnabled.first()

        val ordered = buildList {
            if (edgeTtsEnabled) addAll(ttsProviders.filter { it.locality == Locality.EDGE })
            if (remoteTtsEnabled) addAll(ttsProviders.filter { it.locality == Locality.REMOTE })
        }

        val selected = ordered.firstOrNull { it.available() }
        if (selected != null) {
            val reason = if (selected.locality == Locality.EDGE) RoutingReason.EDGE_FIT else RoutingReason.REMOTE_FALLBACK
            log(RoutingStage.TTS, selected.id, selected.locality.name, reason, 0)
            return selected
        }

        log(RoutingStage.TTS, "", "", RoutingReason.DEGRADE, 0)
        throw NoProviderException("No TTS provider is available")
    }

    /**
     * Returns `true` when the edge candidate's declared [ai.champi.providers.api.ProviderCapabilities]
     * can accommodate both the current context and the new input. The heuristic: total estimated
     * tokens (context + input) must be below [FITS_BUDGET_FRACTION] of the provider's
     * [ai.champi.providers.api.ProviderCapabilities.maxInputTokens].
     * Deliberately conservative — thresholds should be widened from real [RoutingDecisionEntity] logs.
     */
    internal fun fits(candidate: LlmProvider, ctx: Conversation, input: String): Boolean {
        val total = ctx.totalTokens + estimateTokens(input)
        return total < candidate.capabilities.maxInputTokens * FITS_BUDGET_FRACTION
    }

    private suspend fun log(
        stage: RoutingStage,
        providerId: String,
        locality: String,
        reason: RoutingReason,
        inputTokenEstimate: Int,
    ) {
        routingDecisionDao.insert(
            RoutingDecisionEntity(
                timestamp = System.currentTimeMillis(),
                stage = stage,
                selectedProviderId = providerId,
                locality = locality,
                reason = reason,
                inputTokenEstimate = inputTokenEstimate,
            ),
        )
    }
}
