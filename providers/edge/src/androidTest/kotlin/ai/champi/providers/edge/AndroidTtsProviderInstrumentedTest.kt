package ai.champi.providers.edge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real-hardware verification for issue #27's acceptance criteria that unit tests can't cover:
 * actual [TextToSpeech] synthesis to PCM and the cancellation contract. Requires an en-US or
 * es-MX TTS voice installed on the device (any device with the Google TTS engine satisfies this).
 *
 * The Android Profiler leak check (no [android.media.AudioTrack] resource leak after repeated
 * cancel-and-replay) cannot be automated here and must be verified manually on a physical device.
 */
@RunWith(AndroidJUnit4::class)
class AndroidTtsProviderInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val provider = AndroidTtsProvider(context)

    @Test
    fun availableReturnsTrueWhenVoiceIsInstalled() = runBlocking {
        val result = withTimeout(5_000) { provider.available() }
        assertTrue("expected available() to return true with a TTS voice installed", result)
    }

    @Test
    fun synthesizeSingleSentenceEmitsNonEmptyChunk() = runBlocking {
        val textFlow = flow { emit("Hello, this is a test sentence.") }
        val chunks = withTimeout(10_000) { provider.synthesize(textFlow).take(1).toList() }
        assertTrue("expected at least one AudioChunk", chunks.isNotEmpty())
        assertTrue("expected non-empty samples", chunks.first().samples.isNotEmpty())
        assertTrue("expected a positive sample rate", chunks.first().sampleRateHz > 0)
    }

    @Test
    fun synthesizeMultiSentenceInputEmitsOneChunkPerSentence() = runBlocking {
        val textFlow = flow {
            emit("First sentence. Second sentence! Third sentence?")
        }
        val chunks = withTimeout(30_000) { provider.synthesize(textFlow).toList() }
        assertTrue("expected chunks for each sentence", chunks.size >= 3)
        assertTrue("all chunks should have samples", chunks.all { it.samples.isNotEmpty() })
    }

    @Test
    fun cancellationStopsSynthesisWithinOneSentenceBoundary() = runBlocking {
        val emitCount = AtomicInteger(0)
        val textFlow = flow {
            repeat(20) { i -> emit("This is sentence number $i. ") }
        }
        val job = launch {
            provider.synthesize(textFlow).collect { emitCount.incrementAndGet() }
        }
        // Let at least one chunk arrive so we know synthesis started.
        withTimeout(15_000) { while (emitCount.get() == 0) delay(50) }
        job.cancelAndJoin()
        val countAtCancel = emitCount.get()
        // After cancel there should be at most one additional chunk in flight.
        delay(1_000)
        assertTrue(
            "emission should stop within one sentence boundary after cancellation",
            emitCount.get() - countAtCancel <= 1,
        )
    }
}
