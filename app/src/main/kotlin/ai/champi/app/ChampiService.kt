package ai.champi.app

import ai.champi.assistant.QueueReplayWorker
import ai.champi.core.state.AppStateHolder
import ai.champi.overlay.OverlayManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Foreground service that keeps the placeholder overlay bubble alive. */
@AndroidEntryPoint
class ChampiService : Service() {

    @Inject lateinit var overlayManager: OverlayManager
    @Inject lateinit var queueReplayWorker: QueueReplayWorker
    @Inject lateinit var appStateHolder: AppStateHolder

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        overlayManager.show()
        queueReplayWorker.start(serviceScope)
        observeExactAlarmSettingsRedirect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-shows the bubble if the user dragged it into the dismiss zone; show() is a no-op
        // otherwise since it already checks whether the overlay view exists.
        overlayManager.show()
        if (intent?.action == ACTION_OPEN_PANEL) {
            appStateHolder.requestOpenPanel()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        queueReplayWorker.stop()
        serviceScope.cancel()
        overlayManager.hide()
        super.onDestroy()
    }

    /**
     * Observes [AppStateHolder.exactAlarmSettingsRedirectId] and launches the system "Alarms &
     * reminders" permission screen whenever [AlarmTimerActionProvider] signals that exact-alarm
     * permission is missing. [drop(1)] skips the initial StateFlow value so only real increments
     * (from actual permission-denied events) trigger the intent.
     */
    private fun observeExactAlarmSettingsRedirect() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        serviceScope.launch {
            appStateHolder.exactAlarmSettingsRedirectId.drop(1).collect {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "Champi", NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Champi is running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    companion object {
        /** Intent action: tells [ChampiService.onStartCommand] to open the conversation panel. */
        const val ACTION_OPEN_PANEL = "ai.champi.app.action.OPEN_PANEL"

        private const val CHANNEL_ID = "champi_overlay"
        private const val NOTIFICATION_ID = 1
    }
}
