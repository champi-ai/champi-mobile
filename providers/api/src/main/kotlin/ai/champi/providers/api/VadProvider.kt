package ai.champi.providers.api

import kotlinx.coroutines.flow.Flow

/** One detected span of speech, isolated from the continuous [audio] stream. */
data class SpeechSegment(val audio: Flow<PcmFrame>, val startMs: Long, val endMs: Long? = null)

interface VadProvider : Provider {
    fun segment(audio: Flow<PcmFrame>): Flow<SpeechSegment>
}
