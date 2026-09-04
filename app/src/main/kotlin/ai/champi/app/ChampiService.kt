package ai.champi.app

import ai.champi.assistant.QueueReplayWorker
import ai.champi.overlay.OverlayManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import javax.inject.Inject

/** Foreground service that keeps the placeholder overlay bubble alive. */
@AndroidEntryPoint
class ChampiService : Service() {

    @Inject lateinit var overlayManager: OverlayManager
    @Inject lateinit var queueReplayWorker: QueueReplayWorker

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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-shows the bubble if the user dragged it into the dismiss zone; show() is a no-op
        // otherwise since it already checks whether the overlay view exists.
        overlayManager.show()
        return START_STICKY
    }

    override fun onDestroy() {
        queueReplayWorker.stop()
        serviceScope.cancel()
        overlayManager.hide()
        super.onDestroy()
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

    private companion object {
        const val CHANNEL_ID = "champi_overlay"
        const val NOTIFICATION_ID = 1
    }
}
