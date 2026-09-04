package ai.champi.providers.edge

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * Unit tests for [localeTagForRecognizer] — the pure locale-mapping function extracted from
 * [EdgeSttProvider] so it can be exercised on the JVM without an Android context or a real
 * [android.speech.SpeechRecognizer].
 *
 * Criteria requiring a real device and microphone (word accuracy ≥ 90%, ≤ 1 s transcription
 * latency, airplane-mode isolation) are covered in [EdgeSttProviderInstrumentedTest] or require
 * manual on-device verification — they cannot be automated here.
 */
class EdgeSttProviderTest {

    @Test
    fun enUsLocaleReturnsEnUs() {
        assertEquals("en-US", localeTagForRecognizer(Locale.US))
    }

    @Test
    fun enGbLocaleReturnsEnUs() {
        // Any non-Spanish locale falls back to en-US regardless of region.
        assertEquals("en-US", localeTagForRecognizer(Locale.UK))
    }

    @Test
    fun esMxLocaleReturnsEsMx() {
        assertEquals("es-MX", localeTagForRecognizer(Locale.forLanguageTag("es-MX")))
    }

    @Test
    fun esEsLocaleReturnsEsMx() {
        // Any Spanish variant maps to es-MX (the supported Spanish locale for this provider).
        assertEquals("es-MX", localeTagForRecognizer(Locale.forLanguageTag("es-ES")))
    }

    @Test
    fun esOnlyLocaleReturnsEsMx() {
        // Bare language code "es" with no region.
        assertEquals("es-MX", localeTagForRecognizer(Locale("es")))
    }

    @Test
    fun frLocaleReturnsEnUs() {
        assertEquals("en-US", localeTagForRecognizer(Locale.FRENCH))
    }

    @Test
    fun jaLocaleReturnsEnUs() {
        assertEquals("en-US", localeTagForRecognizer(Locale.JAPANESE))
    }
}
