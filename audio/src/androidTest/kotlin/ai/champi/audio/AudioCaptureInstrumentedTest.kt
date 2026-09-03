package ai.champi.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onEach
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
 * Real-hardware verification for issue #23's acceptance criteria that unit tests can't cover:
 * actual `AudioRecord` frame emission and the no-second-instance guarantee under concurrent
 * collection. Requires `RECORD_AUDIO` granted to this test APK's package before running.
 */
@RunWith(AndroidJUnit4::class)
class AudioCaptureInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val capture = AudioCapture(context)

    @Test
    fun pcmFlowEmitsFramesWhileGranted() = runBlocking {
        val frames = withTimeout(5_000) { capture.pcmFlow().take(3).toList() }
        assertTrue("expected at least one frame", frames.isNotEmpty())
        assertTrue("every frame should carry samples", frames.all { it.samples.isNotEmpty() })
        assertTrue("sample rate should be 16kHz", frames.all { it.sampleRateHz == 16_000 })
    }

    @Test
    fun concurrentCollectorsBothReceiveFramesWithoutASecondAudioRecord() = runBlocking {
        // If pcmFlow() opened a second AudioRecord per collector, one of these would fail to
        // initialize (STATE_UNINITIALIZED) on most devices, since only one app can hold the
        // VOICE_RECOGNITION audio source at a time — this would time out instead of completing.
        val first = async { withTimeout(5_000) { capture.pcmFlow().take(2).toList() } }
        val second = async { withTimeout(5_000) { capture.pcmFlow().take(2).toList() } }
        val (framesA, framesB) = awaitAll(first, second)
        assertTrue("first collector got frames", framesA.isNotEmpty())
        assertTrue("second collector got frames", framesB.isNotEmpty())
    }

    @Test
    fun cancellationStopsEmissionQuickly() = runBlocking {
        val emitCount = AtomicInteger(0)
        val job = launch {
            capture.pcmFlow().onEach { emitCount.incrementAndGet() }.collect { }
        }
        // Let real frames start arriving before cancelling.
        withTimeout(5_000) { while (emitCount.get() == 0) delay(10) }
        job.cancelAndJoin()
        val countAtCancel = emitCount.get()
        delay(300)
        assertTrue(
            "emission should stop within one buffer's worth of time after cancellation",
            emitCount.get() - countAtCancel <= 1,
        )
    }

    // Permission-denial (pcmFlow() closing with a SecurityException rather than crashing) isn't
    // covered by an automated test here: revoking a dangerous permission from an already-running
    // process makes Android kill the whole process outright (confirmed on-device — logcat shows
    // "Killing ... permissions revoked" followed by the instrumentation reporting "Process
    // crashed"). That's the OS's own enforcement, not a bug in AudioCapture, but it means "revoke
    // mid-session, then assert the flow errors gracefully" isn't testable as an instrumented test
    // against a live process. The check itself (pcmFlow's checkSelfPermission guard, and the
    // post-startRecording() recordingState check added alongside these tests) is verified by
    // code review instead.
}
