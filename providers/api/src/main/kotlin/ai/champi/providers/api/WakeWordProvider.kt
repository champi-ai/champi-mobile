package ai.champi.providers.api

import kotlinx.coroutines.flow.Flow

data class WakeEvent(val wakeWordId: String, val timestampMs: Long, val confidence: Float)

interface WakeWordProvider : Provider {
    fun listen(audio: Flow<PcmFrame>): Flow<WakeEvent>
}
