package ai.champi.app

import ai.champi.core.notification.ProactiveNotificationPoster
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * [ProactiveNotificationPoster] implementation that delegates to [ProactiveNotificationHelper].
 *
 * Lives in `:app` so it can reference [ProactiveNotificationHelper] directly while keeping
 * `:assistant` free of any dependency on `:app`.
 */
class ProactiveNotificationPosterImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : ProactiveNotificationPoster {

    override fun post(notificationId: Int, title: String, text: String, urgent: Boolean) {
        ProactiveNotificationHelper.post(
            context = context,
            notificationId = notificationId,
            title = title,
            text = text,
            urgent = urgent,
        )
    }
}
