package ai.champi.providers.api

import kotlinx.coroutines.flow.Flow

class AudioChunk(val samples: ShortArray, val sampleRateHz: Int, val isFinal: Boolean = false) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is AudioChunk &&
                    sampleRateHz == other.sampleRateHz &&
                    isFinal == other.isFinal &&
                    samples.contentEquals(other.samples)
                )

    override fun hashCode(): Int = (31 * sampleRateHz + isFinal.hashCode()) * 31 + samples.contentHashCode()
}

interface TtsProvider : Provider {
    fun synthesize(text: Flow<String>): Flow<AudioChunk>
}
