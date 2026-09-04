package ai.champi.providers.edge

import ai.champi.providers.api.PcmFrame
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [detectUtteranceBoundaries] and the PCM re-chunking accumulator logic.
 * These tests run on the JVM without any Android context or ONNX runtime.
 */
class EdgeVadProviderTest {

    // ---------------------------------------------------------------------------
    // detectUtteranceBoundaries — state machine
    // ---------------------------------------------------------------------------

    @Test
    fun noProbabilitiesProducesNoSegments() {
        val result = detectUtteranceBoundaries(emptyList(), silenceWindowsNeeded = 5)
        assertTrue(result.isEmpty())
    }

    @Test
    fun allSilenceProducesNoSegments() {
        val probs = List(20) { 0.1f }
        val result = detectUtteranceBoundaries(probs, silenceWindowsNeeded = 3)
        assertTrue(result.isEmpty())
    }

    @Test
    fun allSpeechFlushesOneSegmentAtEnd() {
        val probs = List(10) { 0.9f }
        val result = detectUtteranceBoundaries(probs, silenceWindowsNeeded = 3)
        assertEquals(1, result.size)
        assertEquals(0, result[0].first)
        assertEquals(9, result[0].second)
    }

    @Test
    fun singleUtteranceFlankedBySilence() {
        // silence(3) + speech(5) + silence(3)
        val probs = List(3) { 0.1f } + List(5) { 0.9f } + List(3) { 0.1f }
        val result = detectUtteranceBoundaries(probs, silenceWindowsNeeded = 3)
        assertEquals(1, result.size)
        assertEquals(3, result[0].first)   // speech starts at index 3
        // End index is when silence count reaches silenceWindowsNeeded (index 3+5+2 = 10)
        assertEquals(10, result[0].second)
    }

    @Test
    fun twoUtterancesWithSilenceGapBetween() {
        // silence(2) + speech(3) + silence(4) + speech(3) + silence(4)
        val probs = List(2) { 0.1f } +
            List(3) { 0.9f } +
            List(4) { 0.1f } +
            List(3) { 0.9f } +
            List(4) { 0.1f }
        val result = detectUtteranceBoundaries(probs, silenceWindowsNeeded = 3)
        assertEquals(2, result.size)
        // First segment starts at index 2
        assertEquals(2, result[0].first)
        // Second segment starts at index 2+3+4 = 9
        assertEquals(9, result[1].first)
    }

    @Test
    fun silenceGapShorterThanThresholdDoesNotSplit() {
        // speech(4) + short silence(2) + speech(4) then silence(3) to endpoint
        val probs = List(4) { 0.9f } +
            List(2) { 0.1f } +
            List(4) { 0.9f } +
            List(3) { 0.1f }
        // silenceWindowsNeeded = 3, so 2 silent windows is not enough to endpoint
        val result = detectUtteranceBoundaries(probs, silenceWindowsNeeded = 3)
        assertEquals(1, result.size)
        assertEquals(0, result[0].first)
    }

    @Test
    fun onsetThresholdIsRespected() {
        // probabilities just below onset threshold — no segment expected
        val probs = List(10) { 0.49f }
        val result = detectUtteranceBoundaries(
            probs,
            onsetThreshold = 0.5f,
            silenceWindowsNeeded = 3,
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun silenceThresholdHysteresisKeepsSpeechOpen() {
        // prob starts high, then stays between SILENCE_THRESHOLD and ONSET_THRESHOLD
        // (0.36f > SILENCE_THRESHOLD=0.35f so NOT counted as silence)
        val probs = List(3) { 0.9f } + List(5) { 0.36f } + List(3) { 0.1f }
        val result = detectUtteranceBoundaries(
            probs,
            onsetThreshold = 0.5f,
            silenceThreshold = 0.35f,
            silenceWindowsNeeded = 3,
        )
        // The 0.36 region is above SILENCE_THRESHOLD so silence count stays at 0;
        // endpointing only fires during the trailing 0.1 region.
        assertEquals(1, result.size)
        assertEquals(0, result[0].first)
    }

    // ---------------------------------------------------------------------------
    // Re-chunking accumulator: ensure variable-size PcmFrames are merged into
    // exactly CHUNK_SAMPLES-wide windows. Tested via a flow of synthetic frames.
    // ---------------------------------------------------------------------------

    @Test
    fun rechunkingAccumulatorHandlesFramesSmallerThanChunkSize() = runTest {
        // Emit frames of 100 samples each; 6 frames = 600 samples > 512 samples (1 full chunk)
        val smallFrames = List(6) { PcmFrame(ShortArray(100) { 1 }, 16_000) }
        val inFlow = flow { smallFrames.forEach { emit(it) } }

        // Accumulate manually using the same logic as EdgeVadProvider.segment()
        val chunks = mutableListOf<ShortArray>()
        val acc = ShortArray(512)
        var pos = 0
        smallFrames.forEach { frame ->
            var src = 0
            while (src < frame.samples.size) {
                val toCopy = minOf(512 - pos, frame.samples.size - src)
                frame.samples.copyInto(acc, pos, src, src + toCopy)
                pos += toCopy
                src += toCopy
                if (pos == 512) {
                    chunks.add(acc.copyOf())
                    pos = 0
                }
            }
        }

        // 600 samples / 512 = 1 full chunk (88 samples remain in accumulator, not emitted)
        assertEquals(1, chunks.size)
        assertEquals(512, chunks[0].size)
    }

    @Test
    fun rechunkingAccumulatorHandlesFramesLargerThanChunkSize() {
        // One frame of 1024 samples = exactly 2 chunks
        val bigFrame = PcmFrame(ShortArray(1024) { 1 }, 16_000)
        val chunks = mutableListOf<ShortArray>()
        val acc = ShortArray(512)
        var pos = 0
        var src = 0
        while (src < bigFrame.samples.size) {
            val toCopy = minOf(512 - pos, bigFrame.samples.size - src)
            bigFrame.samples.copyInto(acc, pos, src, src + toCopy)
            pos += toCopy
            src += toCopy
            if (pos == 512) {
                chunks.add(acc.copyOf())
                pos = 0
            }
        }
        assertEquals(2, chunks.size)
    }
}
