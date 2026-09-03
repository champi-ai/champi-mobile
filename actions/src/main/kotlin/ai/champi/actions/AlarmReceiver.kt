package ai.champi.actions

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

internal const val EXTRA_ALARM_ID = "alarm_id"
internal const val EXTRA_LABEL = "label"
internal const val ACTIONS_NOTIFICATION_CHANNEL = "champi_actions"

/** Fires when a scheduled [AlarmTimerActionProvider] alarm/timer triggers; posts a notification. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getIntExtra(EXTRA_ALARM_ID, -1)
        val label = intent.getStringExtra(EXTRA_LABEL)

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                ACTIONS_NOTIFICATION_CHANNEL,
                "Champi alarms & timers",
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
        val notification = android.app.Notification.Builder(context, ACTIONS_NOTIFICATION_CHANNEL)
            .setContentTitle(label?.takeIf { it.isNotBlank() } ?: "Champi alarm")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setAutoCancel(true)
            .build()
        manager.notify(alarmId, notification)
    }
}
