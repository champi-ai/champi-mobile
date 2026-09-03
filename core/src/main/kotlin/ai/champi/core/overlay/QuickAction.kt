package ai.champi.core.overlay

/** The four long-press quick actions from the overlay bubble (phase 1: visual only, stubs). */
enum class QuickAction {
    MUTE_MIC,
    PUSH_TO_TALK,
    SLEEP,
    SETTINGS,
}

/** Quick-actions surface geometry — both are implemented; selection persists via [OverlayPreferencesRepository]. */
enum class QuickActionGeometry {
    RADIAL_ARC,
    EDGE_RAIL,
}

/**
 * `:app`'s `SettingsActivity`, referenced by fully-qualified class name (via
 * `Intent.setClassName`) rather than a compile-time dependency, since `:overlay` doesn't (and
 * shouldn't) depend on `:app`. Must be an *explicit* intent, not an action-based implicit one:
 * apps targeting API 31+ can't resolve implicit intents to a non-exported activity, even within
 * the same app — confirmed on-device (`ActivityNotFoundException` despite the manifest filter
 * being correctly registered) before switching to this.
 */
const val SETTINGS_ACTIVITY_CLASS = "ai.champi.app.SettingsActivity"
