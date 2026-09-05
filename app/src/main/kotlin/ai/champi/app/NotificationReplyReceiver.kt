package ai.champi.app

import ai.champi.assistant.TurnOrchestrator
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Receives the inline reply submitted from a proactive notification's [RemoteInput] action.
 *
 * Extracts the reply text from the [RemoteInput] bundle, submits it to [TurnOrchestrator] as a new
 * text turn, and cancels the originating notification so the reply action disappears — the standard
 * Android "direct reply" UX pattern.
 */
@AndroidEntryPoint
class NotificationReplyReceiver : BroadcastReceiver() {

    @Inject lateinit var turnOrchestrator: TurnOrchestrator

    /** Receiver-local scope; short-lived and cancelled if the process dies before reply completes. */
    private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REPLY) return

        val replyText = extractReplyText(intent) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)

        // Cancel the notification immediately so the inline-reply spinner does not spin forever.
        if (notificationId != -1) {
            context.getSystemService(NotificationManager::class.java)
                .cancel(notificationId)
        }

        receiverScope.launch {
            turnOrchestrator.submitText(replyText)
        }
    }

    companion object {
        const val ACTION_REPLY = "ai.champi.app.action.NOTIFICATION_REPLY"
        const val EXTRA_NOTIFICATION_ID = "notification_id"

        /**
         * Reads the inline-reply text from the [RemoteInput] results bundle carried in [intent].
         *
         * Returns `null` if the bundle is absent or the text is blank. The post-extraction
         * filtering (null/blank check) is delegated to [filterReplyText] so that logic can be
         * unit-tested without a real Android context.
         */
        fun extractReplyText(intent: Intent): String? {
            val bundle = RemoteInput.getResultsFromIntent(intent) ?: return null
            val raw = bundle.getCharSequence(ProactiveNotificationHelper.KEY_REPLY_TEXT)
            return filterReplyText(raw)
        }

        /**
         * Pure function: converts a raw [CharSequence] from the [RemoteInput] bundle to a
         * non-blank [String], or `null` if the input is null or blank.
         *
         * Extracted so it can be tested without Android instrumentation.
         */
        fun filterReplyText(raw: CharSequence?): String? =
            raw?.toString()?.takeIf { it.isNotBlank() }
    }
}
