package ai.champi.overlay

import ai.champi.assistant.ConversationManager
import ai.champi.assistant.TurnOrchestrator
import ai.champi.core.overlay.OverlayPreferencesRepository
import ai.champi.core.state.AppStateHolder
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the single overlay window. [OverlayRoot] renders bubble/panel/quick-actions content and
 * reports the [WindowSpec] it currently needs; this class is the only thing that talks to
 * [WindowManager], resizing/repositioning that one window to match — the same technique classic
 * "chat heads" overlays use, since fullscreen-window-with-touch-region-masking requires a
 * `@hide` platform API ([android.view.ViewTreeObserver.OnComputeInternalInsetsListener]) that
 * isn't available in the public SDK.
 */
@Singleton
class OverlayManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appStateHolder: AppStateHolder,
    private val preferences: OverlayPreferencesRepository,
    private val conversationManager: ConversationManager,
    private val turnOrchestrator: TurnOrchestrator,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var composeView: ComposeView? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var scope: CoroutineScope? = null

    fun show() {
        if (composeView != null) return

        val owner = OverlayLifecycleOwner()
        val overlayScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = overlayScope

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Only takes effect while the window is also focusable (EXPANDED mode clears
            // FLAG_NOT_FOCUSABLE) — shrinks the window so the input row stays above the IME
            // instead of being covered by it.
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        val view = ComposeView(context)
        view.setContent {
            OverlayRoot(
                appStateHolder = appStateHolder,
                preferences = preferences,
                conversationManager = conversationManager,
                turnOrchestrator = turnOrchestrator,
                scope = overlayScope,
                // Deferred via post(): onDismiss fires from inside the bubble's own touch-event
                // dispatch (the drag-end callback), so removing that same view synchronously
                // here would tear down the view hierarchy mid-dispatch.
                onDismiss = { view.post(::hide) },
                onWindowSpecChanged = { spec ->
                    layoutParams.width = spec.widthPx
                    layoutParams.height = spec.heightPx
                    layoutParams.x = spec.xPx
                    layoutParams.y = spec.yPx
                    layoutParams.gravity = spec.gravity
                    layoutParams.flags = if (spec.focusable) {
                        layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                    } else {
                        layoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    }
                    runCatching { windowManager.updateViewLayout(view, layoutParams) }
                },
            )
        }
        owner.attachToView(view)
        owner.onStart()

        windowManager.addView(view, layoutParams)
        composeView = view
        lifecycleOwner = owner
    }

    fun hide() {
        composeView?.let { runCatching { windowManager.removeView(it) } }
        lifecycleOwner?.onDestroy()
        scope?.cancel()
        composeView = null
        lifecycleOwner = null
        scope = null
    }
}
