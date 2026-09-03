package ai.champi.overlay

import ai.champi.core.overlay.BubbleOffset
import ai.champi.core.overlay.OverlayPreferencesRepository
import ai.champi.core.overlay.QuickAction
import ai.champi.core.overlay.QuickActionGeometry
import ai.champi.core.overlay.SETTINGS_ACTIVITY_CLASS
import ai.champi.core.state.AppStateHolder
import ai.champi.core.state.CharacterState
import android.content.Intent
import android.graphics.Rect as AndroidRect
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewTreeObserver
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val LONG_PRESS_TIMEOUT_MS = 400L
private const val BUBBLE_SIZE_DP = 56
private const val PEEK_VISIBLE_DP = 28
// Radial arc: 96 dp radius + 48 dp button diameter means ~240 dp min; use 280 dp to avoid edge clipping
private const val QUICK_ACTIONS_WINDOW_DP = 280
private const val EXPANDED_HEIGHT_FRACTION = 0.6f

/**
 * Everything the overlay bubble/panel/quick-actions renders, plus the state machine driving it.
 * The overlay window itself is sized to whatever's currently visible (see [OverlayManager]) —
 * there is no fullscreen touch-passthrough trick here, since the platform API for it
 * (`ViewTreeObserver.OnComputeInternalInsetsListener`) is `@hide` and not in the public SDK.
 */
@Composable
internal fun OverlayRoot(
    appStateHolder: AppStateHolder,
    preferences: OverlayPreferencesRepository,
    scope: CoroutineScope,
    onDismiss: () -> Unit,
    onWindowSpecChanged: (WindowSpec) -> Unit,
) {
    val appState by appStateHolder.state.collectAsState()
    val density = LocalDensity.current
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    // Animatable.animateTo needs a MonotonicFrameClock, which only exists on a coroutine scope
    // tied to the Composition — the `scope` parameter (a plain CoroutineScope from OverlayManager)
    // doesn't have one and crashes with IllegalStateException if used for animation.
    val animationScope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(OverlayMode.COLLAPSED) }
    var bubbleOffset by remember { mutableStateOf(IntOffset(0, 600)) }
    var peeked by remember { mutableStateOf(false) }
    var geometry by remember { mutableStateOf(QuickActionGeometry.RADIAL_ARC) }
    var imeVisible by remember { mutableStateOf(false) }
    var peekMinutes by remember { mutableStateOf(5) }

    // configuration.screenHeightDp is the *raw* display height, but this window's y-coordinate
    // is relative to the status-bar-inset parent frame WindowManager gives it — clamping against
    // the raw height left room for the window to extend past the real bottom of that frame, into
    // where the nav bar sits, clipping quick-actions targets there. Subtract both system bar
    // insets (looked up the classic way, since this overlay has no Activity decor to ask via
    // WindowInsets) to get the actual usable bound.
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.roundToPx() }
    val rawScreenHeightPx = with(density) { configuration.screenHeightDp.dp.roundToPx() }
    val systemBarInsetsPx = remember(view) {
        val resources = view.resources
        fun barHeight(name: String): Int {
            val id = resources.getIdentifier(name, "dimen", "android")
            return if (id > 0) resources.getDimensionPixelSize(id) else 0
        }
        barHeight("status_bar_height") + barHeight("navigation_bar_height")
    }
    val screenHeightPx = (rawScreenHeightPx - systemBarInsetsPx).coerceAtLeast(0)
    val bubblePx = with(density) { BUBBLE_SIZE_DP.dp.roundToPx() }
    val peekVisiblePx = with(density) { PEEK_VISIBLE_DP.dp.roundToPx() }
    val quickActionsPx = with(density) { QUICK_ACTIONS_WINDOW_DP.dp.roundToPx() }
    val expandedHeightPx = (screenHeightPx * EXPANDED_HEIGHT_FRACTION).roundToInt()
    val isAtStartEdge = bubbleOffset.x < (screenWidthPx - bubblePx) / 2

    LaunchedEffect(Unit) {
        preferences.bubbleOffset.collectLatest { bubbleOffset = IntOffset(it.x, it.y) }
    }
    LaunchedEffect(Unit) {
        preferences.quickActionGeometry.collectLatest { geometry = it }
    }
    LaunchedEffect(Unit) {
        preferences.peekMinutes.collectLatest { peekMinutes = it }
    }

    // Best-effort keyboard detection: this overlay window is FLAG_NOT_FOCUSABLE so it never
    // receives WindowInsets for another app's IME. Comparing the visible display frame is the
    // same heuristic long-standing floating-bubble apps use; accuracy varies by OEM.
    DisposableEffect(view) {
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val rect = AndroidRect()
            view.getWindowVisibleDisplayFrame(rect)
            imeVisible = (screenHeightPx - rect.bottom) > screenHeightPx * 0.15
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }

    // Resets on any gesture (bubbleOffset/mode change) and on any non-IDLE character state, per
    // the peek spec — an in-progress conversation shouldn't have the bubble tuck itself away.
    LaunchedEffect(bubbleOffset, mode, appState.characterState) {
        peeked = false
        if (mode == OverlayMode.COLLAPSED &&
            appState.characterState == CharacterState.IDLE &&
            peekMinutes > 0
        ) {
            delay(peekMinutes * 60_000L)
            peeked = true
        }
    }

    val windowSpec = remember(mode, bubbleOffset, peeked, isAtStartEdge, screenWidthPx, screenHeightPx) {
        when (mode) {
            OverlayMode.EXPANDED -> WindowSpec(
                widthPx = screenWidthPx,
                heightPx = expandedHeightPx,
                xPx = 0,
                yPx = 0,
                gravity = Gravity.BOTTOM or Gravity.START,
                focusable = true,
            )

            OverlayMode.QUICK_ACTIONS -> {
                // Keep the bubble's edge of the window fixed to the bubble (so the arc, which
                // sweeps *away* from whichever edge the bubble is snapped to, always sweeps into
                // the screen rather than off it) and only clamp the far edge/vertical position —
                // the window is intentionally larger than the arc's reach (see QUICK_ACTIONS_WINDOW_DP)
                // so this alone keeps every button on-screen without needing the arc off-center.
                val x = if (isAtStartEdge) bubbleOffset.x else bubbleOffset.x - (quickActionsPx - bubblePx)
                val y = (bubbleOffset.y - (quickActionsPx - bubblePx) / 2)
                    .coerceIn(0, (screenHeightPx - quickActionsPx).coerceAtLeast(0))
                WindowSpec(
                    widthPx = quickActionsPx,
                    heightPx = quickActionsPx,
                    xPx = x.coerceIn(0, (screenWidthPx - quickActionsPx).coerceAtLeast(0)),
                    yPx = y,
                    gravity = Gravity.TOP or Gravity.START,
                )
            }

            OverlayMode.COLLAPSED -> {
                val x = when {
                    !peeked -> bubbleOffset.x
                    isAtStartEdge -> -(bubblePx - peekVisiblePx)
                    else -> screenWidthPx - peekVisiblePx
                }
                WindowSpec(bubblePx, bubblePx, x, bubbleOffset.y, Gravity.TOP or Gravity.START)
            }
        }
    }

    SideEffect { if (!imeVisible) onWindowSpecChanged(windowSpec) }

    if (imeVisible) return

    when (mode) {
        // There's no "tap outside the panel" region to detect here: this window covers only the
        // bottom EXPANDED_HEIGHT_FRACTION of the screen (see the class doc on why — no fullscreen
        // touch-passthrough trick is available), so the panel already fills 100% of it. Collapse
        // is reachable within our own bounds via a background tap or swipe-down instead.
        OverlayMode.EXPANDED -> ExpandedPanel(
            appState = appState,
            onCollapse = { mode = OverlayMode.COLLAPSED },
            modifier = Modifier.fillMaxWidth().height(with(density) { expandedHeightPx.toDp() }),
        )

        OverlayMode.QUICK_ACTIONS -> Box(modifier = Modifier.size(QUICK_ACTIONS_WINDOW_DP.dp)) {
            QuickActionsLayer(
                visible = true,
                geometry = geometry,
                anchorEdgeIsStart = isAtStartEdge,
                onSelect = { action ->
                    mode = OverlayMode.COLLAPSED
                    when (action) {
                        QuickAction.SLEEP -> appStateHolder.setCharacterState(CharacterState.SLEEPING)
                        QuickAction.SETTINGS -> view.context.startActivity(
                            Intent()
                                .setClassName(view.context.packageName, SETTINGS_ACTIVITY_CLASS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                        QuickAction.MUTE_MIC, QuickAction.PUSH_TO_TALK -> Unit // stubs until :audio lands
                    }
                },
                modifier = Modifier.align(Alignment.Center),
            )
            Box(modifier = Modifier.align(if (isAtStartEdge) Alignment.TopStart else Alignment.TopEnd)) {
                CharacterPlaceholder(state = appState.characterState, size = BUBBLE_SIZE_DP.dp)
            }
        }

        OverlayMode.COLLAPSED -> Box(
            modifier = Modifier
                .size(BUBBLE_SIZE_DP.dp)
                // TalkBack synthesizes double-tap/two-finger-double-tap into semantics click
                // actions rather than replaying raw touch events, so the custom gesture detector
                // below (needed to disambiguate tap/drag/long-press on one pointer stream) is
                // invisible to it without these — without this block, TalkBack could describe the
                // bubble but never actually activate it.
                .semantics {
                    contentDescription = "Champi assistant"
                    onClick(label = "Open conversation") {
                        mode = OverlayMode.EXPANDED
                        true
                    }
                    onLongClick(label = "Open quick actions") {
                        mode = OverlayMode.QUICK_ACTIONS
                        true
                    }
                }
                .pointerInput(Unit) {
                    detectBubbleGestures(
                        onTouchStart = { peeked = false },
                        onDrag = { delta ->
                            bubbleOffset = IntOffset(
                                (bubbleOffset.x + delta.x.roundToInt())
                                    .coerceIn(0, (screenWidthPx - bubblePx).coerceAtLeast(0)),
                                (bubbleOffset.y + delta.y.roundToInt())
                                    .coerceIn(0, (screenHeightPx - bubblePx).coerceAtLeast(0)),
                            )
                        },
                        onDragEnd = {
                            val centerX = bubbleOffset.x + bubblePx / 2
                            val centerY = bubbleOffset.y + bubblePx / 2
                            val inDismissZone = centerY > screenHeightPx - bubblePx * 2 &&
                                centerX in (screenWidthPx / 2 - bubblePx)..(screenWidthPx / 2 + bubblePx)
                            if (inDismissZone) {
                                onDismiss()
                            } else {
                                val snappedX = if (centerX < screenWidthPx / 2) 0 else screenWidthPx - bubblePx
                                val snappedY = bubbleOffset.y
                                scope.launch { preferences.saveBubbleOffset(BubbleOffset(snappedX, snappedY)) }
                                animationScope.launch {
                                    Animatable(bubbleOffset.x.toFloat()).animateTo(
                                        targetValue = snappedX.toFloat(),
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessMedium,
                                        ),
                                    ) {
                                        bubbleOffset = IntOffset(value.roundToInt(), snappedY)
                                    }
                                }
                            }
                        },
                        onTap = { mode = OverlayMode.EXPANDED },
                        onLongPress = { mode = OverlayMode.QUICK_ACTIONS },
                    )
                },
        ) {
            CharacterPlaceholder(state = appState.characterState, size = BUBBLE_SIZE_DP.dp)
        }
    }
}

/**
 * Disambiguates tap / drag / long-press on a single pointer stream. Compose's built-in
 * `detectTapGestures`/`detectDragGestures` can't be combined on one target since both would
 * race for the same down event, so this races a long-press timer against incoming pointer
 * events by hand.
 */
private suspend fun PointerInputScope.detectBubbleGestures(
    onTouchStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    onTouchStart()
    val downUptimeMillis = SystemClock.uptimeMillis()
    var dragging = false
    var longPressFired = false
    var totalDrag = Offset.Zero
    val slop = viewConfiguration.touchSlop

    while (true) {
        // AwaitPointerEventScope is @RestrictsSuspension, so a long-press timer can't run as a
        // separate launched coroutine racing this loop (as detectTapGestures-style code usually
        // would) — instead, wrap the *wait for the next event* itself in a timeout: null means
        // the finger has been held still for the remaining budget, i.e. a long press.
        val remaining = LONG_PRESS_TIMEOUT_MS - (SystemClock.uptimeMillis() - downUptimeMillis)
        val event = if (!dragging && !longPressFired && remaining > 0) {
            withTimeoutOrNull(remaining) { awaitPointerEvent() }
        } else {
            awaitPointerEvent()
        }
        if (event == null) {
            longPressFired = true
            onLongPress()
            continue
        }
        val change = event.changes.firstOrNull { it.id == down.id } ?: break
        if (!change.pressed) {
            if (dragging) onDragEnd() else if (!longPressFired) onTap()
            break
        }
        val delta = change.positionChange()
        totalDrag += delta
        if (!dragging && totalDrag.getDistance() > slop) {
            dragging = true
        }
        if (dragging) {
            change.consume()
            onDrag(delta)
        }
    }
}
