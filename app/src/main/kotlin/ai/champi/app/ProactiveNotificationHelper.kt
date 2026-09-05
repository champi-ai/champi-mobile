package ai.champi.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput

/**
 * Builds and posts proactive assistant notifications with a [RemoteInput] inline-reply action and a
 * tap-to-open-panel content intent.
 *
 * Two channels are registered: [CHANNEL_URGENT] (IMPORTANCE_DEFAULT) for time-sensitive prompts
 * and [CHANNEL_SILENT] (IMPORTANCE_LOW) for ambient ones. The caller picks the channel via
 * [urgent]; the rate-limit policy lives in issue #39 and is not applied here.
 *
 * This class is stateless — it holds no instance fields, so callers may call [post] from any
 * context without worrying about lifecycle.
 */
object ProactiveNotificationHelper {

    const val CHANNEL_URGENT = "champi_proactive_urgent"
    const val CHANNEL_SILENT = "champi_proactive_silent"

    /** Key used to read the reply text from the [RemoteInput] results bundle. */
    const val KEY_REPLY_TEXT = "champi_reply_text"

    /**
     * Registers both proactive notification channels. Safe to call repeatedly; the OS is
     * idempotent if the channel already exists with the same ID and importance.
     *
     * Call once in [ChampiApplication.onCreate] or [ChampiService.onCreate].
     */
    fun registerChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_URGENT,
                context.getString(R.string.notification_channel_proactive_urgent),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SILENT,
                context.getString(R.string.notification_channel_proactive_silent),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    /**
     * Constructs and posts a proactive notification.
     *
     * @param context  Application or service context.
     * @param notificationId  Caller-managed ID; must be stable per logical notification so the OS
     *   can update an existing notification rather than stack duplicates.
     * @param title    Short notification title (shown on lock screen).
     * @param text     Notification body text.
     * @param urgent   `true` posts to [CHANNEL_URGENT] (audible, default importance);
     *   `false` posts to [CHANNEL_SILENT] (silent, low importance).
     */
    fun post(
        context: Context,
        notificationId: Int,
        title: String,
        text: String,
        urgent: Boolean = false,
    ) {
        val channelId = if (urgent) CHANNEL_URGENT else CHANNEL_SILENT

        val replyInput = RemoteInput.Builder(KEY_REPLY_TEXT)
            .setLabel(context.getString(R.string.notification_reply_hint))
            .build()

        val replyIntent = Intent(context, NotificationReplyReceiver::class.java).apply {
            action = NotificationReplyReceiver.ACTION_REPLY
            putExtra(NotificationReplyReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            context.getString(R.string.notification_action_reply),
            replyPendingIntent,
        )
            .addRemoteInput(replyInput)
            .build()

        // Tapping the notification body: start (or resume) ChampiService with OPEN_PANEL so
        // onStartCommand calls appStateHolder.requestOpenPanel().
        val openPanelIntent = Intent(context, ChampiService::class.java).apply {
            action = ChampiService.ACTION_OPEN_PANEL
        }
        val openPanelPendingIntent = PendingIntent.getForegroundService(
            context,
            0,
            openPanelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openPanelPendingIntent)
            .addAction(replyAction)
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify(notificationId, notification)
    }
}
