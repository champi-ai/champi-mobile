package ai.champi.core.context

import ai.champi.core.conversation.Message
import ai.champi.core.persistence.MessageRole

/**
 * Immutable snapshot of the optional ambient context signals collected by
 * [ContextSnapshotSource]. Each field is nullable — `null` means that signal
 * was either disabled or unavailable at collection time.
 *
 * A snapshot with all-null fields carries no information and must not produce
 * a system message (see [toSystemMessage]).
 */
data class ContextSnapshot(
    /** Coarse latitude in decimal degrees. Non-null only when location context is enabled and
     *  [android.Manifest.permission.ACCESS_COARSE_LOCATION] has been granted. */
    val latitude: Double? = null,
    /** Coarse longitude in decimal degrees. Non-null only when location context is enabled and
     *  [android.Manifest.permission.ACCESS_COARSE_LOCATION] has been granted. */
    val longitude: Double? = null,
    /** Battery level as a percentage (0–100). Non-null when battery context is enabled. */
    val batteryPercent: Int? = null,
    /** Whether the device is currently charging (or fully charged). Non-null when battery context
     *  is enabled. */
    val isCharging: Boolean? = null,
    /** Human-readable connectivity type: "WiFi", "mobile", "ethernet", or "none". Non-null when
     *  connectivity context is enabled. */
    val connectivityType: String? = null,
    /** Package name of the most recently used app. Non-null when foreground-app context is enabled
     *  and [android.app.AppOpsManager.OPSTR_GET_USAGE_STATS] access has been granted by the user
     *  (via [android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS]). */
    val foregroundAppPackage: String? = null,
)

/**
 * Converts this snapshot into a system-role [Message] suitable for prepending to a
 * [ai.champi.providers.api.Conversation] before it is sent to an LLM provider. Returns `null`
 * when all signal fields are null (i.e. no context signals are enabled), satisfying the
 * acceptance criterion "disabling all context signals produces no system message prepend".
 */
fun ContextSnapshot.toSystemMessage(): Message? {
    val parts = buildList<String> {
        if (latitude != null && longitude != null) {
            add("Coarse location: (%.3f, %.3f)".format(latitude, longitude))
        }
        if (batteryPercent != null) {
            val charging = if (isCharging == true) ", charging" else if (isCharging == false) ", not charging" else ""
            add("Battery: $batteryPercent%$charging")
        }
        if (connectivityType != null) {
            add("Connectivity: $connectivityType")
        }
        if (foregroundAppPackage != null) {
            add("Foreground app: $foregroundAppPackage")
        }
    }
    if (parts.isEmpty()) return null
    return Message(
        id = "ctx_snapshot_${System.currentTimeMillis()}",
        role = MessageRole.SYSTEM,
        content = "Device context — ${parts.joinToString("; ")}.",
        timestamp = System.currentTimeMillis(),
    )
}
