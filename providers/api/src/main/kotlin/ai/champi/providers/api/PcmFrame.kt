package ai.champi.providers.api

/** A chunk of raw 16-bit PCM audio samples, mono, at [sampleRateHz]. */
class PcmFrame(val samples: ShortArray, val sampleRateHz: Int) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is PcmFrame && sampleRateHz == other.sampleRateHz && samples.contentEquals(other.samples))

    override fun hashCode(): Int = 31 * sampleRateHz + samples.contentHashCode()
}
