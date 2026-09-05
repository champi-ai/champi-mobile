package ai.champi.core.actions

/**
 * Represents a scheduled alarm or timer that has been committed to [android.app.AlarmManager] and
 * is waiting to fire. The overlay reads this list to render an undo card in the message list.
 *
 * @param id          The [android.app.PendingIntent] request code used when the alarm was
 *                    registered — required to reconstruct an equivalent intent for cancellation.
 * @param label       User-visible label supplied at schedule time, or null if none was given.
 * @param triggersAt  Epoch-millisecond timestamp when the alarm will fire.
 * @param toolName    Either `"set_alarm"` or `"set_timer"`, used to label the undo card.
 */
data class ActiveTimer(
    val id: Int,
    val label: String?,
    val triggersAt: Long,
    val toolName: String,
)
