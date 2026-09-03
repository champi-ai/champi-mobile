package ai.champi.audio

import ai.champi.providers.api.AudioChunk
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays a stream of [AudioChunk]s (TTS output) via [AudioTrack] in streaming mode. Each
 * [enqueue] call owns its own [AudioTrack], created lazily from the first chunk's sample rate;
 * cancelling the returned [Job] tears it down mid-stream via the `finally` block, so playback
 * stops within the write in flight rather than draining the rest of the queued audio.
 */
@Singleton
class PlaybackQueue @Inject constructor() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun enqueue(chunks: Flow<AudioChunk>): Job = scope.launch {
        var track: AudioTrack? = null
        try {
            chunks.collect { chunk ->
                val current = track ?: createTrack(chunk.sampleRateHz).also {
                    track = it
                    it.play()
                }
                current.write(chunk.samples, 0, chunk.samples.size)
            }
        } finally {
            track?.let {
                runCatching { it.stop() }
                it.release()
            }
        }
    }

    private fun createTrack(sampleRateHz: Int): AudioTrack {
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val format = AudioFormat.Builder()
            .setSampleRate(sampleRateHz)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        return AudioTrack(
            attributes,
            format,
            minBufferSize.coerceAtLeast(1),
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
    }
}
