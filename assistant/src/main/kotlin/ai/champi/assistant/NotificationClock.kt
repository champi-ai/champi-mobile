package ai.champi.assistant

/**
 * Abstraction over the system clock used by [ProactiveNotificationEngine]'s token-bucket rate
 * limiter. Injected via Hilt so tests can provide a deterministic fake without relying on real
 * wall-clock time.
 */
fun interface NotificationClock {
    /** Returns the current epoch time in milliseconds. */
    fun nowMillis(): Long
}
