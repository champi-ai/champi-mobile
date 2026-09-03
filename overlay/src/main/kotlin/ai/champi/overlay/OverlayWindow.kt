package ai.champi.overlay

internal enum class OverlayMode { COLLAPSED, QUICK_ACTIONS, EXPANDED }

/** Desired `WindowManager.LayoutParams` geometry for the single overlay window, in pixels. */
internal data class WindowSpec(
    val widthPx: Int,
    val heightPx: Int,
    val xPx: Int,
    val yPx: Int,
    val gravity: Int,
    // FLAG_NOT_FOCUSABLE must be cleared while the expanded panel is open so a future text input
    // row can receive the keyboard; every other mode stays unfocusable so the overlay never steals
    // input focus from whatever app is running underneath.
    val focusable: Boolean = false,
)
