package ai.champi.providers.edge

import ai.champi.providers.api.BatteryClass
import ai.champi.providers.api.Cost
import ai.champi.providers.api.LatencyClass
import ai.champi.providers.api.Locality
import ai.champi.providers.api.PcmFrame
import ai.champi.providers.api.ProviderCapabilities
import ai.champi.providers.api.SttProvider
import ai.champi.providers.api.Transcript
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device STT provider backed by Android's [SpeechRecognizer] API.
 *
 * On API 31+ (Android 12), [SpeechRecognizer.createOnDeviceSpeechRecognizer] is used, which
 * guarantees recognition never leaves the device. [RecognizerIntent.EXTRA_PREFER_OFFLINE] is
 * always included in the recognition intent as a belt-and-suspenders measure.
 *
 * On-device models are managed by the Android platform (typically the Google app), not
 * downloadable by this app. If no on-device model is installed for the device's locale,
 * [available] returns `false` — the LLM download pattern from issue #18 does not apply here
 * because [SpeechRecognizer]'s model inventory is outside app control.
 *
 * ARCHITECTURAL TRADEOFF — mic re-acquisition: [SpeechRecognizer] provides no public API for
 * accepting pre-recorded PCM (the segment frames captured by the upstream VAD). It always
 * acquires the microphone itself via [SpeechRecognizer.startListening]. The [transcribe]
 * implementation drains the incoming [Flow<PcmFrame>] concurrently to honour the upstream
 * contract, but the actual recognition runs against a fresh microphone capture that begins
 * when [transcribe] is called. For short utterances the user may have finished speaking before
 * [SpeechRecognizer.startListening] begins, in which case the recognizer may capture silence and
 * return an empty or incorrect result. Whisper.cpp (excluded from M3 scope due to the NDK build
 * toolchain and large model download requirement) would not have this limitation because it
 * accepts raw PCM buffers directly.
 */
@Singleton
class EdgeSttProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : SttProvider {

    override val id = "android-stt-edge"
    override val locality = Locality.EDGE
    override val cost = Cost(LatencyClass.MEDIUM, BatteryClass.MEDIUM)
    override val capabilities = ProviderCapabilities(
        languages = listOf("en-US", "es-MX"),
        maxInputTokens = Int.MAX_VALUE,
        supportsStreaming = true,
    )

    /**
     * Returns `true` if the device has an on-device speech recognition model installed and
     * available for use without network access.
     *
     * On API 31+ (Android 12), delegates to [SpeechRecognizer.isOnDeviceRecognitionAvailable].
     * On API < 31, always returns `false` — [SpeechRecognizer.createSpeechRecognizer] on older
     * APIs may silently fall back to a cloud backend, violating the V1 requirement that audio
     * never leaves the device (docs/specs/mobile.md §"offline"). Returning `false` ensures the
     * pipeline router never dispatches to this provider on devices that cannot guarantee
     * on-device recognition.
     *
     * Note: on-device STT models (especially es-MX) may not be pre-installed on all devices.
     * When the Google app's on-device model is absent, this returns `false`. There is no
     * download flow this app can initiate — unlike the LLM provider pattern from issue #18,
     * Android STT models are entirely under platform control.
     */
    override suspend fun available(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        } else {
            false
        }

    /**
     * Transcribes a VAD-delimited speech segment using Android's on-device [SpeechRecognizer].
     *
     * Emits zero or more [Transcript] with [Transcript.isFinal] = `false` for partial/interim
     * results as the recognizer processes audio, followed by exactly one [Transcript] with
     * [Transcript.isFinal] = `true` when recognition completes (or an empty string on error,
     * so callers always receive a terminal event).
     *
     * Threading: [SpeechRecognizer] must be created and used from a thread with a [Looper].
     * All recognizer interactions are posted to the main thread internally; the returned [Flow]
     * may be collected on any dispatcher.
     *
     * Cancellation: if the flow is cancelled (e.g. barge-in), [SpeechRecognizer.stopListening]
     * and [SpeechRecognizer.destroy] are posted to the main thread from the close handler.
     *
     * See class-level KDoc for the mic re-acquisition architectural tradeoff.
     */
    override fun transcribe(segment: Flow<PcmFrame>): Flow<Transcript> = callbackFlow {
        val recognizer = withContext(Dispatchers.Main) { createRecognizer() }

        // Drain the segment flow concurrently. The upstream VAD will not emit the next segment
        // until this one is fully collected, so we must consume it regardless of whether we can
        // feed the frames to the recognizer (which we cannot — see class KDoc tradeoff note).
        launch(Dispatchers.Default) {
            segment.collect { /* consume frames; SpeechRecognizer cannot accept pre-recorded PCM */ }
        }

        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onPartialResults(partialResults: Bundle?) {
                val texts = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val partial = texts?.firstOrNull() ?: return
                if (partial.isNotBlank()) {
                    trySend(Transcript(text = partial, isFinal = false))
                }
            }

            override fun onResults(results: Bundle?) {
                val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val best = texts?.firstOrNull().orEmpty()
                trySend(Transcript(text = best, isFinal = true))
                close()
            }

            override fun onError(error: Int) {
                // Emit an empty final transcript on any recognizer error so the pipeline always
                // receives a terminal event and can move on (graceful degradation).
                trySend(Transcript(text = "", isFinal = true, confidence = 0f))
                close()
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }

        withContext(Dispatchers.Main) {
            recognizer.setRecognitionListener(listener)
            recognizer.startListening(buildRecognizerIntent())
        }

        awaitClose {
            // Post cleanup to the main thread — awaitClose runs on the collector's dispatcher
            // and SpeechRecognizer must only be called from a Looper thread.
            Handler(Looper.getMainLooper()).post {
                recognizer.stopListening()
                recognizer.destroy()
            }
        }
    }

    /**
     * Creates the appropriate [SpeechRecognizer] instance for this device's API level.
     *
     * On API 31+, [SpeechRecognizer.createOnDeviceSpeechRecognizer] is used — this guarantees
     * the recognizer never routes audio off-device. [available] already returns `false` when
     * [SpeechRecognizer.isOnDeviceRecognitionAvailable] is false, so this path is only reached
     * when an on-device recognizer is confirmed available.
     *
     * Must be called from a thread with a [Looper] (main thread).
     */
    private fun createRecognizer(): SpeechRecognizer =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            // Fallback for pre-API-31: use the default recognizer with EXTRA_PREFER_OFFLINE.
            // available() returns false on these API levels so this branch is defensive only.
            SpeechRecognizer.createSpeechRecognizer(context)
        }

    /**
     * Builds the [RecognizerIntent] used for each [transcribe] call.
     *
     * [RecognizerIntent.EXTRA_PREFER_OFFLINE] is always set to `true` to prevent the system from
     * silently routing audio to a cloud backend even if an on-device model is available. On API
     * 31+ this is belt-and-suspenders since [SpeechRecognizer.createOnDeviceSpeechRecognizer]
     * already guarantees on-device operation, but it is included on all API levels for safety.
     *
     * The language is derived from [localeTagForRecognizer] applied to the device's default
     * locale, which maps any Spanish variant to "es-MX" and anything else to "en-US" — matching
     * the two locales declared in [capabilities].
     */
    private fun buildRecognizerIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            // Never route audio off-device — V1 spec requirement (docs/specs/mobile.md §"offline").
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            // Request partial results so Transcript.Partial events can be emitted.
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTagForRecognizer(Locale.getDefault()))
        }
}

/**
 * Maps the device's current [locale] to one of the two locales this provider supports.
 *
 * Returns "es-MX" for any Spanish variant (language tag starting with "es"), and "en-US" for
 * everything else. Extracted as a top-level internal function so it can be unit-tested without
 * requiring an Android context or real [SpeechRecognizer].
 */
internal fun localeTagForRecognizer(locale: Locale): String =
    if (locale.language == "es") "es-MX" else "en-US"
