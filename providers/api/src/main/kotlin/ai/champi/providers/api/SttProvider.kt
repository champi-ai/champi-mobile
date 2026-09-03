package ai.champi.providers.api

import kotlinx.coroutines.flow.Flow

/** [isFinal] false for interim/partial results as the utterance is still being spoken. */
data class Transcript(val text: String, val isFinal: Boolean, val confidence: Float = 1f)

interface SttProvider : Provider {
    fun transcribe(segment: Flow<PcmFrame>): Flow<Transcript>
}
