package ai.champi.overlay

internal enum class OverlayMode { COLLAPSED, QUICK_ACTIONS, EXPANDED }

/** Desired `WindowManager.LayoutParams` geometry for the single overlay window, in pixels. */
internal data class WindowSpec(
    val widthPx: Int,
    val heightPx: Int,
    val xPx: Int,
    val yPx: Int,
    val gravity: Int,
)
