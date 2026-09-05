package ai.champi.app

import ai.champi.providers.api.AudioChunk
import ai.champi.providers.api.BatteryClass
import ai.champi.providers.api.Conversation
import ai.champi.providers.api.Cost
import ai.champi.providers.api.LatencyClass
import ai.champi.providers.api.Locality
import ai.champi.providers.api.LlmEvent
import ai.champi.providers.api.LlmProvider
import ai.champi.providers.api.PcmFrame
import ai.champi.providers.api.ProviderCapabilities
import ai.champi.providers.api.SttProvider
import ai.champi.providers.api.ToolSpec
import ai.champi.providers.api.Transcript
import ai.champi.providers.api.TtsProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Test

private val defaultCost = Cost(LatencyClass.LOW, BatteryClass.LOW)
private val defaultCapabilities = ProviderCapabilities(
    languages = listOf("en"),
    maxInputTokens = 4096,
    supportsStreaming = false,
)

private class FakeSttProvider(
    override val id: String,
    override val locality: Locality,
) : SttProvider {
    override val cost = defaultCost
    override val capabilities = defaultCapabilities
    override suspend fun available() = true
    override fun transcribe(segment: Flow<PcmFrame>): Flow<Transcript> = emptyFlow()
}

private class FakeLlmProvider(
    override val id: String,
    override val locality: Locality,
) : LlmProvider {
    override val cost = defaultCost
    override val capabilities = defaultCapabilities
    override suspend fun available() = true
    override fun complete(ctx: Conversation, tools: List<ToolSpec>): Flow<LlmEvent> = emptyFlow()
}

private class FakeTtsProvider(
    override val id: String,
    override val locality: Locality,
) : TtsProvider {
    override val cost = defaultCost
    override val capabilities = defaultCapabilities
    override suspend fun available() = true
    override fun synthesize(text: Flow<String>): Flow<AudioChunk> = emptyFlow()
}

/**
 * JVM unit tests for the [providerRowModel] and [buildProviderRows] pure functions.
 * No Android context required.
 */
class ProviderRowModelTest {

    @Test
    fun remoteLlmMapsToRemoteLocalityLabelAndToggleKey() {
        val model = providerRowModel(ProviderStage.LLM, FakeLlmProvider("remote-llm", Locality.REMOTE))
        assertEquals("remote", model.localityLabel)
        assertEquals("remote_llm_enabled", model.toggleDataStoreKey)
        assertEquals(Locality.REMOTE, model.locality)
    }

    @Test
    fun edgeSttMapsToEdgeLocalityLabelAndToggleKey() {
        val model = providerRowModel(ProviderStage.STT, FakeSttProvider("edge-stt", Locality.EDGE))
        assertEquals("edge", model.localityLabel)
        assertEquals("edge_stt_enabled", model.toggleDataStoreKey)
        assertEquals(Locality.EDGE, model.locality)
    }

    @Test
    fun remoteTtsMapsToRemoteLocalityLabelAndToggleKey() {
        val model = providerRowModel(ProviderStage.TTS, FakeTtsProvider("remote-tts", Locality.REMOTE))
        assertEquals("remote", model.localityLabel)
        assertEquals("remote_tts_enabled", model.toggleDataStoreKey)
    }

    @Test
    fun edgeLlmMapsToEdgeLocalityLabelAndToggleKey() {
        val model = providerRowModel(ProviderStage.LLM, FakeLlmProvider("edge-llm", Locality.EDGE))
        assertEquals("edge", model.localityLabel)
        assertEquals("edge_llm_enabled", model.toggleDataStoreKey)
    }

    @Test
    fun edgeTtsMapsToEdgeLocalityLabelAndToggleKey() {
        val model = providerRowModel(ProviderStage.TTS, FakeTtsProvider("edge-tts", Locality.EDGE))
        assertEquals("edge", model.localityLabel)
        assertEquals("edge_tts_enabled", model.toggleDataStoreKey)
    }

    @Test
    fun remoteSttMapsToRemoteLocalityLabelAndToggleKey() {
        val model = providerRowModel(ProviderStage.STT, FakeSttProvider("remote-stt", Locality.REMOTE))
        assertEquals("remote", model.localityLabel)
        assertEquals("remote_stt_enabled", model.toggleDataStoreKey)
    }

    @Test
    fun providerIdIsPreservedInRowModel() {
        val model = providerRowModel(ProviderStage.STT, FakeSttProvider("my-custom-stt", Locality.REMOTE))
        assertEquals("my-custom-stt", model.id)
        assertEquals(ProviderStage.STT, model.stage)
    }

    @Test
    fun buildProviderRowsPreservesStageOrderAndProviderOrder() {
        val stt1 = FakeSttProvider("stt-a", Locality.EDGE)
        val stt2 = FakeSttProvider("stt-b", Locality.REMOTE)
        val llm1 = FakeLlmProvider("llm-a", Locality.REMOTE)
        val tts1 = FakeTtsProvider("tts-a", Locality.EDGE)

        val rows = buildProviderRows(
            sttProviders = listOf(stt1, stt2),
            llmProviders = listOf(llm1),
            ttsProviders = listOf(tts1),
        )

        assertEquals(4, rows.size)
        assertEquals(ProviderStage.STT, rows[0].stage)
        assertEquals("stt-a", rows[0].id)
        assertEquals(ProviderStage.STT, rows[1].stage)
        assertEquals("stt-b", rows[1].id)
        assertEquals(ProviderStage.LLM, rows[2].stage)
        assertEquals("llm-a", rows[2].id)
        assertEquals(ProviderStage.TTS, rows[3].stage)
        assertEquals("tts-a", rows[3].id)
    }
}
