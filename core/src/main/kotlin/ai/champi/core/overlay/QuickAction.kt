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
