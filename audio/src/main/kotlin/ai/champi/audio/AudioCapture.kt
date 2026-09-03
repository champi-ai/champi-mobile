package ai.champi.audio

import ai.champi.providers.api.PcmFrame
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private const val SAMPLE_RATE_HZ = 16_000
private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

/**
 * Shared 16kHz mono PCM capture wrapping [AudioRecord]. Every caller of [pcmFlow] gets its own
 * cold [Flow], but underneath there is only ever one [AudioRecord] instance — the actual hardware
 * capture starts on the first concurrent collector and stops when the last one cancels, guarded
 * by [mutex] so wake word/VAD/STT (all potential concurrent collectors, per the module doc) never
 * fight over the audio session.
 *
 * Deliberately never touches any flag or API that would hide the system microphone indicator —
 * there's no legitimate public API for that anyway, and V3 in the spec requires it stay visible
 * whenever the mic is actually active.
 */
@Singleton
class AudioCapture @Inject constructor(@ApplicationContext private val context: Context) {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val frames = MutableSharedFlow<PcmFrame>(extraBufferCapacity = 64)

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private var subscriberCount = 0

    fun pcmFlow(): Flow<PcmFrame> = channelFlow {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            close(SecurityException("RECORD_AUDIO permission not granted"))
            return@channelFlow
        }

        mutex.withLock {
            subscriberCount++
            if (subscriberCount == 1) startCaptureLocked { close(it) }
        }

        val forwardJob = launch { frames.collect { send(it) } }

        awaitClose {
            forwardJob.cancel()
            scope.launch {
                mutex.withLock {
                    subscriberCount--
                    if (subscriberCount == 0) stopCaptureLocked()
                }
            }
        }
    }

    // Lint's cross-method flow analysis can't see that pcmFlow() already checked RECORD_AUDIO
    // before this is ever called — it's the sole call site.
    @SuppressLint("MissingPermission")
    private fun startCaptureLocked(onError: (Throwable) -> Unit) {
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL_CONFIG, ENCODING)
        if (minBufferSize <= 0) {
            onError(IllegalStateException("AudioRecord.getMinBufferSize failed: $minBufferSize"))
            return
        }
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE_HZ,
            CHANNEL_CONFIG,
            ENCODING,
            minBufferSize * 2,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            onError(IllegalStateException("AudioRecord failed to initialize"))
            return
        }
        record.startRecording()
        audioRecord = record
        captureJob = scope.launch {
            val buffer = ShortArray(minBufferSize)
            while (isActive) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) frames.emit(PcmFrame(buffer.copyOf(read), SAMPLE_RATE_HZ))
            }
        }
    }

    private fun stopCaptureLocked() {
        captureJob?.cancel()
        captureJob = null
        audioRecord?.let { record ->
            runCatching { record.stop() }
            record.release()
        }
        audioRecord = null
    }
}
