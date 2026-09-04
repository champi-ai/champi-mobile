package ai.champi.providers.edge

import ai.champi.providers.api.BatteryClass
import ai.champi.providers.api.Cost
import ai.champi.providers.api.LatencyClass
import ai.champi.providers.api.Locality
import ai.champi.providers.api.PcmFrame
import ai.champi.providers.api.ProviderCapabilities
import ai.champi.providers.api.SpeechSegment
import ai.champi.providers.api.VadProvider
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import java.nio.FloatBuffer
import java.nio.LongBuffer
import javax.inject.Inject
import javax.inject.Singleton

private const val MODEL_ASSET = "silero_vad.onnx"

/**
 * Number of PCM samples per Silero VAD inference window at 16 kHz (32 ms per chunk).
 * The model hard-codes this chunk size; do not change without replacing the ONNX asset.
 */
private const val CHUNK_SAMPLES = 512

/**
 * Probability above which a chunk is considered speech (onset).
 * Silero's recommended default is 0.5.
 */
private const val ONSET_THRESHOLD = 0.5f

/**
 * Probability below which a chunk is considered silence (for endpointing).
 * Using a slightly lower value than the onset threshold gives hysteresis.
 */
private const val SILENCE_THRESHOLD = 0.35f

/**
 * Milliseconds per 512-sample chunk at 16 kHz.
 */
private const val MS_PER_CHUNK = 32L

/**
 * Silero VAD LSTM state size (2 directions × 1 batch × 64 units).
 */
private const val STATE_SIZE = 2 * 1 * 64

/**
 * On-device VAD backed by the Silero VAD ONNX model bundled in [MODEL_ASSET].
 *
 * The Silero VAD ONNX model is © 2020–2024 snakers4/silero-vad contributors,
 * licensed under the MIT License. Source: https://github.com/snakers4/silero-vad
 *
 * [segment] accumulates incoming [PcmFrame]s into 512-sample windows, runs inference
 * per window, and emits a [SpeechSegment] for each detected utterance — onset when
 * speech probability crosses [ONSET_THRESHOLD], endpoint when silence persists past
 * [endpointingThresholdMs]. The ONNX session is initialised lazily on first use and
 * reused for the lifetime of the singleton; it is never torn down (no shutdown hook
 * needed — the ORT global environment cleans up on process exit).
 *
 * All inference runs on [Dispatchers.Default]; callers need not dispatch manually.
 * No network access is performed at any point — the model is read from app assets.
 *
 * @param endpointingThresholdMs Milliseconds of continuous silence after which the
 *     current speech utterance is considered ended and a [SpeechSegment] is emitted.
 *     Default 700 ms matches the V1 spec (§2.4) latency target.
 */
@Singleton
class EdgeVadProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    val endpointingThresholdMs: Long = 700L,
) : VadProvider {

    override val id = "silero-vad-edge"
    override val locality = Locality.EDGE
    override val cost = Cost(LatencyClass.LOW, BatteryClass.LOW)
    override val capabilities = ProviderCapabilities(
        languages = listOf("*"),
        maxInputTokens = Int.MAX_VALUE,
        supportsStreaming = true,
    )

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    /**
     * ONNX session loaded once from assets. Inference is stateless per-call; the LSTM
     * states are maintained externally in [segment] and passed as inputs on every forward pass.
     */
    private val session: OrtSession by lazy {
        val bytes = context.assets.open(MODEL_ASSET).readBytes()
        env.createSession(bytes)
    }

    /**
     * Returns `true` if the bundled ONNX asset loads and the session initialises successfully.
     * Since the model ships with the APK, this returns `true` on any device without any
     * download step.
     */
    override suspend fun available(): Boolean = runCatching { session }.isSuccess

    /**
     * Segments a continuous [PcmFrame] flow into discrete speech utterances.
     *
     * Incoming samples are buffered into 512-sample chunks. Each chunk is run through
     * the Silero VAD model. The state machine transitions:
     * - **Silence → Speech** when a chunk's speech probability ≥ [ONSET_THRESHOLD]
     * - **Speech → Silence** when silence probability persists for
     *   [endpointingThresholdMs] ms (i.e., enough consecutive sub-threshold chunks)
     *
     * A [SpeechSegment] is emitted after endpointing with all accumulated speech frames,
     * or when the upstream [audio] flow completes with an in-progress utterance.
     * The flow runs on [Dispatchers.Default]; all ONNX inference happens on that dispatcher.
     */
    override fun segment(audio: Flow<PcmFrame>): Flow<SpeechSegment> = flow {
        // Silero LSTM hidden/cell states reset to zero for each new segment() call.
        var hState = FloatArray(STATE_SIZE)
        var cState = FloatArray(STATE_SIZE)

        // Re-chunking accumulator: collects samples from variable-size PcmFrames into
        // exactly CHUNK_SAMPLES-sized windows before running inference.
        val accumulator = ShortArray(CHUNK_SAMPLES)
        var accPos = 0

        // State machine
        var inSpeech = false
        val speechFrames = mutableListOf<PcmFrame>()
        var speechStartMs = 0L
        var silentWindowCount = 0
        val silenceWindowsNeeded = ((endpointingThresholdMs + MS_PER_CHUNK - 1) / MS_PER_CHUNK).toInt()
        var chunkIndex = 0L

        audio.collect { frame ->
            var srcPos = 0
            while (srcPos < frame.samples.size) {
                val toCopy = minOf(CHUNK_SAMPLES - accPos, frame.samples.size - srcPos)
                frame.samples.copyInto(accumulator, accPos, srcPos, srcPos + toCopy)
                accPos += toCopy
                srcPos += toCopy

                if (accPos == CHUNK_SAMPLES) {
                    val floatSamples = FloatArray(CHUNK_SAMPLES) { accumulator[it] / 32768f }
                    val (prob, hn, cn) = runInference(floatSamples, hState, cState)
                    hState = hn
                    cState = cn

                    val chunkFrame = PcmFrame(accumulator.copyOf(), CHUNK_SAMPLES * 1_000 / 16_000)

                    if (!inSpeech) {
                        if (prob >= ONSET_THRESHOLD) {
                            inSpeech = true
                            silentWindowCount = 0
                            speechStartMs = chunkIndex * MS_PER_CHUNK
                            speechFrames.clear()
                            speechFrames.add(chunkFrame)
                        }
                    } else {
                        speechFrames.add(chunkFrame)
                        if (prob < SILENCE_THRESHOLD) {
                            silentWindowCount++
                            if (silentWindowCount >= silenceWindowsNeeded) {
                                val endMs = chunkIndex * MS_PER_CHUNK
                                emit(buildSegment(speechFrames, speechStartMs, endMs))
                                inSpeech = false
                                silentWindowCount = 0
                                speechFrames.clear()
                            }
                        } else {
                            silentWindowCount = 0
                        }
                    }

                    accPos = 0
                    chunkIndex++
                }
            }
        }

        // Flush any utterance still in progress when the upstream audio flow ends.
        if (inSpeech && speechFrames.isNotEmpty()) {
            emit(buildSegment(speechFrames, speechStartMs, chunkIndex * MS_PER_CHUNK))
        }
    }.flowOn(Dispatchers.Default)

    /**
     * Runs one Silero VAD forward pass for [samples] (length [CHUNK_SAMPLES], float32 in
     * [-1, 1]), with the given LSTM states. Returns the speech probability and the updated
     * LSTM states.
     *
     * Input names and shapes follow the official Silero VAD v4 ONNX export:
     * - `input`  float32 [1, 512]
     * - `sr`     int64   [1]
     * - `h`      float32 [2, 1, 64]
     * - `c`      float32 [2, 1, 64]
     *
     * Output names:
     * - `output` float32 [1, 1]
     * - `hn`     float32 [2, 1, 64]
     * - `cn`     float32 [2, 1, 64]
     */
    private fun runInference(
        samples: FloatArray,
        h: FloatArray,
        c: FloatArray,
    ): Triple<Float, FloatArray, FloatArray> {
        val inputTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(samples),
            longArrayOf(1, CHUNK_SAMPLES.toLong()),
        )
        val srTensor = OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(longArrayOf(16_000L)),
            longArrayOf(1),
        )
        val hTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(h),
            longArrayOf(2, 1, 64),
        )
        val cTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(c),
            longArrayOf(2, 1, 64),
        )

        val inputs = mapOf(
            "input" to inputTensor,
            "sr" to srTensor,
            "h" to hTensor,
            "c" to cTensor,
        )

        return try {
            session.run(inputs).use { result ->
                val prob = extractProb(result.get("output").get().value)
                val hn = flattenFloat3d(result.get("hn").get().value)
                val cn = flattenFloat3d(result.get("cn").get().value)
                Triple(prob, hn, cn)
            }
        } finally {
            inputTensor.close()
            srTensor.close()
            hTensor.close()
            cTensor.close()
        }
    }

    /** Extracts the scalar speech probability from the `output` tensor value ([1, 1] float). */
    @Suppress("UNCHECKED_CAST")
    private fun extractProb(value: Any?): Float {
        return when (value) {
            is Array<*> -> {
                val row = value[0]
                when (row) {
                    is FloatArray -> row[0]
                    is Array<*> -> (row[0] as? Float) ?: 0f
                    else -> 0f
                }
            }
            is FloatArray -> value[0]
            else -> 0f
        }
    }

    /**
     * Flattens a 3-dimensional float array (as returned by ORT for LSTM state tensors)
     * into a contiguous [FloatArray].
     */
    @Suppress("UNCHECKED_CAST")
    private fun flattenFloat3d(value: Any?): FloatArray {
        if (value !is Array<*>) return FloatArray(STATE_SIZE)
        val out = mutableListOf<Float>()
        for (d1 in value) {
            when (d1) {
                is Array<*> -> for (d2 in d1) {
                    when (d2) {
                        is FloatArray -> d2.forEach { out.add(it) }
                        is Array<*> -> for (d3 in d2) { if (d3 is Float) out.add(d3) }
                    }
                }
                is FloatArray -> d1.forEach { out.add(it) }
            }
        }
        return out.toFloatArray().let {
            if (it.size == STATE_SIZE) it else FloatArray(STATE_SIZE)
        }
    }

    private fun buildSegment(
        frames: List<PcmFrame>,
        startMs: Long,
        endMs: Long,
    ): SpeechSegment {
        val captured = frames.toList()
        return SpeechSegment(
            audio = flowOf(*captured.toTypedArray()),
            startMs = startMs,
            endMs = endMs,
        )
    }
}

/**
 * Pure state-machine helper: given a list of per-chunk speech [probabilities], returns the
 * (startChunkIndex, endChunkIndex) pairs for each detected utterance. End index is the index
 * of the last chunk before endpointing fired, inclusive.
 *
 * Extracted so unit tests can exercise the onset/silence logic without an ONNX runtime.
 *
 * @param silenceWindowsNeeded Number of consecutive sub-[silenceThreshold] chunks required
 *     to endpoint a speech segment. Equivalent to endpointingThresholdMs / [MS_PER_CHUNK].
 */
internal fun detectUtteranceBoundaries(
    probabilities: List<Float>,
    onsetThreshold: Float = ONSET_THRESHOLD,
    silenceThreshold: Float = SILENCE_THRESHOLD,
    silenceWindowsNeeded: Int,
): List<Pair<Int, Int>> {
    val result = mutableListOf<Pair<Int, Int>>()
    var inSpeech = false
    var startIdx = 0
    var silentCount = 0

    for ((i, prob) in probabilities.withIndex()) {
        if (!inSpeech) {
            if (prob >= onsetThreshold) {
                inSpeech = true
                startIdx = i
                silentCount = 0
            }
        } else {
            if (prob < silenceThreshold) {
                silentCount++
                if (silentCount >= silenceWindowsNeeded) {
                    result.add(startIdx to i)
                    inSpeech = false
                    silentCount = 0
                }
            } else {
                silentCount = 0
            }
        }
    }
    // Flush any in-progress utterance at end of input
    if (inSpeech) {
        result.add(startIdx to probabilities.size - 1)
    }
    return result
}
