package ai.champi.providers.edge

import ai.champi.providers.api.AudioChunk
import ai.champi.providers.api.BatteryClass
import ai.champi.providers.api.Cost
import ai.champi.providers.api.LatencyClass
import ai.champi.providers.api.Locality
import ai.champi.providers.api.ProviderCapabilities
import ai.champi.providers.api.TtsProvider
import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Edge TTS provider backed by the Android platform [TextToSpeech] engine. Splits incoming text
 * tokens into sentence-sized chunks on `.`, `!`, or `?` boundaries, synthesizes each sentence
 * via [TextToSpeech.synthesizeToFile], and emits the resulting PCM samples as [AudioChunk].
 *
 * The first chunk is emitted as soon as the first complete sentence has been synthesized, which
 * keeps pipeline latency within the 500 ms per-sentence target relative to when the LLM finishes
 * emitting that sentence's tokens.
 *
 * Cancellation: if the returned [Flow] is cancelled (e.g. barge-in), [TextToSpeech.stop] is
 * called from the `finally` block and all in-flight temp WAV files are deleted.
 *
 * Piper neural TTS is out of scope for M3 (no model asset available). A future
 * `PiperTtsProvider` would implement [TtsProvider] in the same package and replace this binding.
 */
@Singleton
class AndroidTtsProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : TtsProvider {

    override val id = "android-tts"
    override val locality = Locality.EDGE
    override val cost = Cost(LatencyClass.LOW, BatteryClass.LOW)
    override val capabilities = ProviderCapabilities(
        languages = listOf("en-US", "es-MX"),
        maxInputTokens = 4096,
        supportsStreaming = true,
    )

    /**
     * Completes with `true` when the TTS engine finishes initialization, `false` on failure.
     * Awaited before any [synthesize] call to ensure the engine is ready.
     */
    private val engineReady = CompletableDeferred<Boolean>()

    /**
     * Maps each in-flight utterance ID to a [CompletableDeferred] that resolves to the WAV output
     * file once [UtteranceProgressListener.onDone] fires. The `synthesize` flow awaits this deferred
     * then reads and deletes the file.
     */
    private val pending = ConcurrentHashMap<String, CompletableDeferred<File>>()

    private val engine: TextToSpeech = TextToSpeech(context) { status ->
        engineReady.complete(status == TextToSpeech.SUCCESS)
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                utteranceId ?: return
                pending[utteranceId]?.complete(wavFileFor(utteranceId))
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(utteranceId: String?) {
                utteranceId ?: return
                pending[utteranceId]?.completeExceptionally(
                    IOException("TextToSpeech synthesis error for utterance $utteranceId"),
                )
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                utteranceId ?: return
                pending[utteranceId]?.completeExceptionally(
                    IOException("TextToSpeech synthesis error $errorCode for utterance $utteranceId"),
                )
            }
        })
    }

    /**
     * Returns `true` when the platform TTS engine is initialized and at least one voice for
     * en-US or es-MX is installed on the device. A device with the default Google TTS engine
     * will almost always satisfy this.
     */
    override suspend fun available(): Boolean {
        if (!engineReady.await()) return false
        val enUs = engine.isLanguageAvailable(Locale.US)
        val esMx = engine.isLanguageAvailable(Locale.forLanguageTag("es-MX"))
        return enUs >= TextToSpeech.LANG_AVAILABLE || esMx >= TextToSpeech.LANG_AVAILABLE
    }

    /**
     * Consumes [text] token-by-token, buffers tokens until a sentence boundary (`.`, `!`, `?`),
     * synthesizes each sentence via [TextToSpeech.synthesizeToFile], and emits the resulting
     * [AudioChunk]. Any tokens remaining after the upstream flow completes are flushed as a final
     * synthesis call. The flow runs on [Dispatchers.IO].
     *
     * If the flow is cancelled, [TextToSpeech.stop] is called from the `finally` block so the
     * in-flight synthesis is aborted within one sentence boundary.
     */
    override fun synthesize(text: Flow<String>): Flow<AudioChunk> = flow {
        if (!engineReady.await()) return@flow

        val buffer = StringBuilder()
        val sentenceTerminators = charArrayOf('.', '!', '?')

        suspend fun emitSentence(sentence: String) {
            val utteranceId = UUID.randomUUID().toString()
            val wavFile = wavFileFor(utteranceId)
            val deferred = CompletableDeferred<File>()
            pending[utteranceId] = deferred
            try {
                engine.synthesizeToFile(sentence, Bundle(), wavFile, utteranceId)
                val resultFile = deferred.await()
                emit(wavToAudioChunk(resultFile))
            } finally {
                pending.remove(utteranceId)
                wavFile.delete()
            }
        }

        try {
            text.collect { token ->
                buffer.append(token)
                var offset = 0
                while (offset < buffer.length) {
                    val termIdx = buffer.indexOfAny(sentenceTerminators, offset)
                    if (termIdx == -1) break
                    val sentence = buffer.substring(offset, termIdx + 1).trim()
                    offset = termIdx + 1
                    if (sentence.isNotEmpty()) emitSentence(sentence)
                }
                if (offset > 0) buffer.delete(0, offset)
            }
            val remaining = buffer.toString().trim()
            if (remaining.isNotEmpty()) emitSentence(remaining)
        } finally {
            engine.stop()
        }
    }.flowOn(Dispatchers.IO)

    private fun wavFileFor(utteranceId: String): File =
        File(context.cacheDir, "tts_$utteranceId.wav")

    /**
     * Reads a standard 44-byte PCM WAV file produced by [TextToSpeech.synthesizeToFile] and
     * returns an [AudioChunk] with the raw 16-bit little-endian samples and the sample rate
     * extracted from the WAV header (bytes 24–27).
     */
    private fun wavToAudioChunk(wavFile: File): AudioChunk {
        wavFile.inputStream().use { stream ->
            // Standard PCM WAV header is 44 bytes; sample rate is at offset 24 (4 bytes, LE).
            val header = ByteArray(44)
            var read = 0
            while (read < 44) {
                val n = stream.read(header, read, 44 - read)
                if (n < 0) throw IOException("WAV file too short: ${wavFile.length()} bytes")
                read += n
            }
            val sampleRate = ByteBuffer.wrap(header, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int

            val pcmBytes = stream.readBytes()
            val samples = ShortArray(pcmBytes.size / 2)
            ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)

            return AudioChunk(samples = samples, sampleRateHz = sampleRate)
        }
    }
}
