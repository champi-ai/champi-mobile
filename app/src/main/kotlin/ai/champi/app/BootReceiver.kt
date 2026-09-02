package ai.champi.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat

/** Restarts [ChampiService] after reboot, provided the overlay permission is still granted. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Settings.canDrawOverlays(context)) return

        ContextCompat.startForegroundService(context, Intent(context, ChampiService::class.java))
    }
}
