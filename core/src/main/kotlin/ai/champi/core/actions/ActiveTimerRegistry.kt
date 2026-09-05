package ai.champi.core.actions

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory registry of active alarms and timers committed to [AlarmManager].
 *
 * [AlarmTimerActionProvider] (in `:actions`) writes to this registry on every successful
 * `set_alarm`/`set_timer` invocation. The overlay reads [timers] to render inline undo cards;
 * the user tapping "Undo" calls [cancel], which reconstructs the equivalent [PendingIntent] (same
 * request code + intent action) and calls [AlarmManager.cancel] before removing the entry.
 *
 * An in-memory `StateFlow` is appropriate for `set_timer` (short countdowns); `set_alarm` entries
 * for future time-of-day alarms survive only the current process lifetime. Process death before a
 * short timer fires is unlikely and acceptable; for robustness a Room-backed registry could be
 * added later.
 */
@Singleton
class ActiveTimerRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _timers = MutableStateFlow<List<ActiveTimer>>(emptyList())

    /** Live list of active alarms/timers; observed by the overlay to render undo cards. */
    val timers: StateFlow<List<ActiveTimer>> = _timers

    /** Adds [timer] to the active list after [AlarmManager] has accepted the schedule. */
    fun add(timer: ActiveTimer) {
        _timers.update { it + timer }
    }

    /**
     * Cancels the alarm with [timerId] and removes it from the active list.
     *
     * Android matches a [PendingIntent] for cancellation by request code + intent contents, not
     * object identity. The intent is reconstructed here using [Intent.setClassName] rather than a
     * direct class reference so `:core` does not need to depend on `:actions` at compile time.
     * [PendingIntent.FLAG_NO_CREATE] returns null if the alarm was already fired or cancelled by
     * the system, in which case this is a safe no-op.
     */
    fun cancel(timerId: Int) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val intent = Intent().apply {
            setClassName(context.packageName, "ai.champi.actions.AlarmReceiver")
            setPackage(context.packageName)
            putExtra("alarm_id", timerId)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            timerId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        )
        pending?.let { alarmManager.cancel(it) }
        _timers.update { list -> list.filter { it.id != timerId } }
    }
}
