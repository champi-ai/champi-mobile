package ai.champi.providers.edge

import android.os.Build
import android.speech.SpeechRecognizer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [EdgeSttProvider] that can run on a real device or emulator.
 *
 * IMPORTANT — criteria NOT verified here (require physical device + real speech input):
 * - "set a timer for five minutes" (en-US) → Transcript.Final with > 90% word accuracy
 * - Equivalent es-MX sentence → Transcript.Final with > 90% word accuracy
 * - Transcription completes within 1 s of speech end (5-word utterance, mid-range device)
 * - STT runs in airplane mode with no network errors
 *
 * These criteria cannot be automated without a real microphone and a human speaker (or a
 * device-side audio injection mechanism not available in this environment). They must be
 * verified manually on a physical device with the Google on-device speech model installed.
 *
 * What IS verified here:
 * - [EdgeSttProvider.available] reflects device capability correctly (API-gated).
 * - On API 31+, [SpeechRecognizer.isOnDeviceRecognitionAvailable] is consulted.
 * - On API < 31, [available] returns false unconditionally (no on-device guarantee).
 */
@RunWith(AndroidJUnit4::class)
class EdgeSttProviderInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val provider = EdgeSttProvider(context)

    /**
     * Verifies [EdgeSttProvider.available] is consistent with the raw platform API.
     *
     * On API 31+, both the provider and [SpeechRecognizer.isOnDeviceRecognitionAvailable] must
     * agree. On API < 31, the provider must return false regardless of platform state.
     */
    @Test
    fun availableMatchesPlatformCapability() = runBlocking {
        val result = provider.available()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val platformAvailable = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            assertTrue(
                "available() should match isOnDeviceRecognitionAvailable on API 31+: " +
                    "provider=$result platform=$platformAvailable",
                result == platformAvailable,
            )
        } else {
            assertFalse(
                "available() must return false on API < 31 (no on-device guarantee)",
                result,
            )
        }
    }
}
