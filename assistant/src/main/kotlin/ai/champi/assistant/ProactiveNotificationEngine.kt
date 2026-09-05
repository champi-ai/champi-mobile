package ai.champi.assistant

import ai.champi.core.actions.ActionSettingsRepository
import ai.champi.core.notification.ProactiveNotificationPoster
import ai.champi.core.state.AppStateHolder
import ai.champi.core.state.CharacterState
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts proactive assistant notifications subject to a client-side token-bucket rate limit.
 *
 * The token bucket holds up to [ActionSettingsRepository.proactiveRateLimitPerHour] tokens and
 * replenishes at 1 token per hour (sliding-window implementation: a notification timestamp is
 * retained per token used, and a token is considered replenished once that timestamp is older than
 * one hour). State is in-memory only; it resets on process restart. Because [ChampiService] is a
 * long-running foreground service, this is acceptable for the V1 scope.
 *
 * After posting, the character is set to [CharacterState.NOTIFYING] and automatically reverted to
 * [CharacterState.IDLE] after [NOTIFYING_REVERT_MS] — unless the user opens the panel within that
 * window, in which case the revert is skipped immediately (the panel opening signals engagement).
 *
 * @param clock  Provides the current epoch-millisecond time; injectable for unit tests.
 */
@Singleton
class ProactiveNotificationEngine @Inject constructor(
    private val poster: ProactiveNotificationPoster,
    private val appStateHolder: AppStateHolder,
    private val actionSettingsRepository: ActionSettingsRepository,
    internal val clock: NotificationClock,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Timestamps (epoch millis) of notifications posted within the current sliding one-hour window.
     * Each entry consumes one token; entries older than one hour are expired before checking
     * capacity, which effectively replenishes the bucket at 1 token per hour.
     */
    private val tokenTimestamps = ArrayDeque<Long>()

    /** Currently running revert-to-IDLE job; cancelled if the panel opens within 3 s. */
    private var revertJob: Job? = null

    /**
     * Attempts to post a proactive notification for [message].
     *
     * Checks the rate limiter first. If the bucket is empty, logs the suppression and returns
     * without posting. Otherwise posts via [ProactiveNotificationPoster], sets the character to
     * [CharacterState.NOTIFYING], and schedules an automatic revert to [CharacterState.IDLE] after
     * [NOTIFYING_REVERT_MS] unless the user opens the panel first.
     *
     * @param message The notification body text. The title is always "champi".
     * @param urgent  `true` routes to the high-importance (audible) channel; `false` is silent.
     */
    suspend fun raise(message: String, urgent: Boolean) {
        val capacityLimit = actionSettingsRepository.proactiveRateLimitPerHour.first()
        val now = clock.nowMillis()
        val oneHourAgo = now - ONE_HOUR_MS

        synchronized(tokenTimestamps) {
            // Expire tokens older than one hour (bucket replenishment).
            while (tokenTimestamps.isNotEmpty() && tokenTimestamps.first() < oneHourAgo) {
                tokenTimestamps.removeFirst()
            }

            if (tokenTimestamps.size >= capacityLimit) {
                Log.d(TAG, "Proactive notification suppressed — rate limit ($capacityLimit/hr) reached.")
                return
            }

            tokenTimestamps.addLast(now)
        }

        poster.post(
            notificationId = NOTIFICATION_ID,
            title = NOTIFICATION_TITLE,
            text = message,
            urgent = urgent,
        )

        appStateHolder.setCharacterState(CharacterState.NOTIFYING)

        val panelIdAtPost = appStateHolder.openPanelRequestId.value
        revertJob?.cancel()
        revertJob = scope.launch {
            // Skip the revert if the user opens the panel within the window — they are engaged.
            val panelOpened = withTimeoutOrNull(NOTIFYING_REVERT_MS) {
                appStateHolder.openPanelRequestId.first { id -> id != panelIdAtPost }
            }
            if (panelOpened == null) {
                appStateHolder.setCharacterState(CharacterState.IDLE)
            }
        }
    }

    private companion object {
        const val TAG = "ProactiveNotificationEngine"
        const val NOTIFICATION_TITLE = "champi"
        const val NOTIFICATION_ID = 2
        const val NOTIFYING_REVERT_MS = 3_000L
        const val ONE_HOUR_MS = 3_600_000L
    }
}
