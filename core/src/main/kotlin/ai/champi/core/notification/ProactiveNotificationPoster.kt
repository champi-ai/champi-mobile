package ai.champi.core.notification

/**
 * Contract for posting a proactive assistant notification to the system tray.
 *
 * Defined in `:core` so that `:assistant`'s [ProactiveNotificationEngine] can depend on this
 * interface without creating a circular dependency on `:app`, which implements it.
 *
 * @see ai.champi.app.ProactiveNotificationPosterImpl
 */
interface ProactiveNotificationPoster {
    /**
     * Posts a proactive notification.
     *
     * @param notificationId  Caller-managed stable ID — passing the same value updates an existing
     *   notification rather than stacking a new one.
     * @param title  Short notification title.
     * @param text   Notification body text.
     * @param urgent `true` routes to the high-importance channel (audible); `false` routes to the
     *   low-importance channel (silent).
     */
    fun post(notificationId: Int, title: String, text: String, urgent: Boolean)
}
