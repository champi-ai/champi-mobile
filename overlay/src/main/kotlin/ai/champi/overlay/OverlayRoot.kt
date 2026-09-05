package ai.champi.overlay

import ai.champi.assistant.ConversationManager
import ai.champi.assistant.TurnOrchestrator
import ai.champi.audio.AudioCapture
import ai.champi.core.overlay.BubbleOffset
import ai.champi.core.overlay.OverlayPreferencesRepository
import ai.champi.core.overlay.QuickAction
import ai.champi.core.overlay.QuickActionGeometry
import ai.champi.core.overlay.SETTINGS_ACTIVITY_CLASS
import ai.champi.core.persistence.MessageRole
import ai.champi.core.state.AppStateHolder
import ai.champi.core.state.CharacterState
import ai.champi.core.state.ConversationEntry
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.Gravity
import androidx.core.content.ContextCompat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val LONG_PRESS_TIMEOUT_MS = 400L
internal const val BUBBLE_SIZE_DP = 56
private const val PEEK_VISIBLE_DP = 28
// Radial arc: 96 dp radius + 48 dp button diameter means ~240 dp min; use 280 dp to avoid edge clipping
internal const val QUICK_ACTIONS_WINDOW_DP = 280
private const val EXPANDED_HEIGHT_FRACTION = 0.6f

// Dismiss zone: width ≥ 96 dp (spec B3); 120 dp gives comfortable visual padding around the target.
private const val DISMISS_ZONE_WIDTH_DP = 120
private const val DISMISS_ZONE_HEIGHT_DP = 64

/**
 * Everything the overlay bubble/panel/quick-actions renders, plus the state machine driving it.
 * The overlay window itself is sized to whatever's currently visible (see [OverlayManager]) —
 * there is no fullscreen touch-passthrough trick here, since the platform API for it
 * (`ViewTreeObserver.OnComputeInternalInsetsListener`) is `@hide` and not in the public SDK.
 *
 * Exception: while a drag gesture is in progress the window temporarily expands to full-screen
 * so the bottom-center dismiss zone indicator can be rendered. `FLAG_NOT_TOUCH_MODAL` ensures
 * that touches outside any interactive element still pass through to the underlying app; during
 * the brief drag the user's single finger is already on the bubble, so nothing else needs to
 * receive touches anyway.
 *
 * The bubble [Box] and its `pointerInput(Unit)` modifier occupy the **same structural position**
 * in the Compose tree for both [OverlayMode.COLLAPSED] and [OverlayMode.QUICK_ACTIONS] — they
 * are merged into a single `else` branch of the mode switch. This preserves the pointer-stream
 * coroutine across the COLLAPSED→QUICK_ACTIONS transition so that a finger still held down after
 * the long-press fires continues to be tracked: [AppState.attention] is updated as the finger
 * moves between targets, and the release position is compared against the target geometry to fire
 * the correct action (or cancel cleanly). Without this, the coroutine would be cancelled by
 * Compose when the composition branch changed, leaving `clickable` on the new composables to
 * handle a pointer stream they can never receive because it started in a different window layout.
 */
@Composable
internal fun OverlayRoot(
    appStateHolder: AppStateHolder,
    preferences: OverlayPreferencesRepository,
    conversationManager: ConversationManager,
    turnOrchestrator: TurnOrchestrator,
    audioCapture: AudioCapture,
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
    val openPanelRequestId by appStateHolder.openPanelRequestId.collectAsState()
    var lastHandledOpenPanelRequestId by remember { mutableStateOf(0L) }
    LaunchedEffect(openPanelRequestId) {
        if (openPanelRequestId != lastHandledOpenPanelRequestId) {
            lastHandledOpenPanelRequestId = openPanelRequestId
            mode = OverlayMode.EXPANDED
        }
    }
    var bubbleOffset by remember { mutableStateOf(IntOffset(0, 600)) }
    var peeked by remember { mutableStateOf(false) }
    // True while a drag gesture is in progress; expands the overlay window to full-screen so
    // the bottom-center dismiss zone indicator can be drawn below the bubble.
    var isDragging by remember { mutableStateOf(false) }
    // True when the bubble center is currently over the dismiss zone during a drag.
    var isDismissZoneActive by remember { mutableStateOf(false) }
    // Captured at touch-down to decide whether a tap should restore from peek or open the panel.
    var peekedAtDown by remember { mutableStateOf(false) }
    // Incremented on every gesture start so the peek idle timer restarts after any interaction,
    // even a tap that doesn't move the bubble or change mode.
    var gestureCount by remember { mutableStateOf(0) }
    var geometry by remember { mutableStateOf(QuickActionGeometry.RADIAL_ARC) }
    var peekMinutes by remember { mutableStateOf(5) }
    var micMuted by remember { mutableStateOf(false) }
    var listeningJob by remember { mutableStateOf<Job?>(null) }

    fun stopListening() {
        listeningJob?.cancel()
        listeningJob = null
        appStateHolder.setAudioLevel(0f)
        if (appStateHolder.state.value.characterState == CharacterState.LISTENING) {
            appStateHolder.setCharacterState(CharacterState.IDLE)
        }
    }

    fun flashError() {
        animationScope.launch {
            appStateHolder.setCharacterState(CharacterState.ERROR)
            delay(500)
            appStateHolder.setCharacterState(CharacterState.IDLE)
        }
    }

    fun executeQuickAction(action: QuickAction) {
        when (action) {
            QuickAction.SLEEP -> appStateHolder.setCharacterState(CharacterState.SLEEPING)
            QuickAction.SETTINGS -> view.context.startActivity(
                Intent()
                    .setClassName(view.context.packageName, SETTINGS_ACTIVITY_CLASS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            QuickAction.MUTE_MIC -> animationScope.launch {
                preferences.setMicMuted(!micMuted)
            }
            QuickAction.PUSH_TO_TALK -> when {
                listeningJob != null -> stopListening()
                micMuted -> flashError()
                ContextCompat.checkSelfPermission(view.context, Manifest.permission.RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED -> flashError()
                else -> {
                    appStateHolder.setCharacterState(CharacterState.LISTENING)
                    listeningJob = scope.launch {
                        audioCapture.pcmFlow()
                            .catch { stopListening() }
                            .collect { frame -> appStateHolder.setAudioLevel(rmsLevel(frame.samples)) }
                    }
                }
            }
        }
    }

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

    // Seeds the in-memory conversation list from Room once per process — appState.conversation
    // otherwise starts empty on every restart even though the history is still persisted.
    LaunchedEffect(Unit) {
        if (appStateHolder.state.value.conversation.isEmpty()) {
            val persisted = conversationManager.messages.first()
            if (persisted.isNotEmpty()) {
                appStateHolder.setConversation(
                    persisted.map { message ->
                        ConversationEntry(
                            id = message.id,
                            text = message.content,
                            fromUser = message.role == MessageRole.USER,
                            attachmentUri = message.attachmentUri,
                            attachmentType = message.attachmentType,
                        )
                    },
                )
            }
        }
    }
    LaunchedEffect(Unit) {
        preferences.bubbleOffset.collectLatest { bubbleOffset = IntOffset(it.x, it.y) }
    }
    LaunchedEffect(Unit) {
        preferences.quickActionGeometry.collectLatest { geometry = it }
    }
    LaunchedEffect(Unit) {
        preferences.peekMinutes.collectLatest { peekMinutes = it }
    }
    LaunchedEffect(Unit) {
        preferences.micMuted.collectLatest { muted ->
            micMuted = muted
            if (muted) stopListening()
        }
    }

    // Reset attention whenever the quick-actions surface is dismissed — covers both the pointer
    // path (onQuickActionsRelease resets it inline) and the TalkBack path (onSelect fires from
    // a clickable handler, which sets mode = COLLAPSED without going through the gesture loop).
    LaunchedEffect(mode) {
        if (mode != OverlayMode.QUICK_ACTIONS) {
            appStateHolder.setAttention(0f)
        }
    }

    // Resets on any gesture (via gestureCount), on any non-IDLE character state, on mode change,
    // and immediately when peekMinutes changes (including to 0 which disables peek entirely).
    // Using gestureCount as a key ensures even a stationary tap resets the full idle window.
    LaunchedEffect(bubbleOffset, mode, appState.characterState, gestureCount, peekMinutes) {
        peeked = false
        if (mode == OverlayMode.COLLAPSED &&
            appState.characterState == CharacterState.IDLE &&
            peekMinutes > 0
        ) {
            delay(peekMinutes * 60_000L)
            peeked = true
        }
    }

    val windowSpec = remember(mode, bubbleOffset, peeked, isAtStartEdge, screenWidthPx, screenHeightPx, isDragging) {
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
                if (isDragging) {
                    // Full-screen during drag so the dismiss zone indicator can be rendered at
                    // the bottom-center while the bubble tracks the finger anywhere on screen.
                    WindowSpec(
                        widthPx = screenWidthPx,
                        heightPx = screenHeightPx,
                        xPx = 0,
                        yPx = 0,
                        gravity = Gravity.TOP or Gravity.START,
                    )
                } else {
                    val x = when {
                        !peeked -> bubbleOffset.x
                        isAtStartEdge -> -(bubblePx - peekVisiblePx)
                        else -> screenWidthPx - peekVisiblePx
                    }
                    WindowSpec(bubblePx, bubblePx, x, bubbleOffset.y, Gravity.TOP or Gravity.START)
                }
            }
        }
    }

    SideEffect { onWindowSpecChanged(windowSpec) }

    Box(modifier = Modifier.fillMaxSize()) {
    when (mode) {
        // There's no "tap outside the panel" region to detect here: this window covers only the
        // bottom EXPANDED_HEIGHT_FRACTION of the screen (see the class doc on why — no fullscreen
        // touch-passthrough trick is available), so the panel already fills 100% of it. Collapse
        // is reachable within our own bounds via a background tap or swipe-down instead.
        OverlayMode.EXPANDED -> ExpandedPanel(
            appState = appState,
            conversationManager = conversationManager,
            turnOrchestrator = turnOrchestrator,
            onCollapse = { mode = OverlayMode.COLLAPSED },
            modifier = Modifier.fillMaxWidth().height(with(density) { expandedHeightPx.toDp() }),
        )

        // COLLAPSED and QUICK_ACTIONS share a single composition subtree so the bubble Box and
        // its pointerInput(Unit) block stay at the same structural position — Compose preserves
        // the node identity (and therefore the running gesture coroutine) across the window resize
        // that happens when the long-press fires and mode flips to QUICK_ACTIONS. This is the same
        // technique used for the drag/dismiss full-screen expansion in the COLLAPSED branch, where
        // isDragging changes the window spec but the bubble node is never recreated.
        else -> {
            // Outer Box fills the current window, whatever size it is for the current mode.
            Box(modifier = Modifier.fillMaxSize()) {
                // Quick-actions layer — composed whenever the mode is QUICK_ACTIONS; centred in
                // the 280 × 280 dp quick-actions window. The clickable handlers on each target
                // work for TalkBack (which synthesises a new pointer stream) and for direct taps
                // after the user lifts the long-press finger. For the hold-and-release gesture
                // (finger still down when targets appear) the action is resolved via
                // onQuickActionsRelease in the detectBubbleGestures loop instead, because the
                // original pointer stream cannot be delivered to a composable that entered the
                // tree after the stream started.
                if (mode == OverlayMode.QUICK_ACTIONS) {
                    QuickActionsLayer(
                        visible = true,
                        geometry = geometry,
                        anchorEdgeIsStart = isAtStartEdge,
                        onSelect = { action ->
                            executeQuickAction(action)
                            mode = OverlayMode.COLLAPSED
                        },
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                // Dismiss zone indicator — only during a drag in COLLAPSED mode.
                if (isDragging) {
                    DismissZoneIndicator(
                        active = isDismissZoneActive,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }

                // Bubble Box — stable across COLLAPSED ↔ QUICK_ACTIONS mode changes.
                // absoluteOffset shifts to keep the bubble at the same screen position as the
                // window resizes (bubble-sized ↔ quick-actions-sized ↔ full-screen for drag).
                val bubbleRelativeOffset: IntOffset = when {
                    mode == OverlayMode.QUICK_ACTIONS ->
                        IntOffset(bubbleOffset.x - windowSpec.xPx, bubbleOffset.y - windowSpec.yPx)
                    isDragging -> bubbleOffset
                    else -> IntOffset.Zero
                }
                Box(
                    modifier = Modifier
                        .absoluteOffset { bubbleRelativeOffset }
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
                            val arcRadiusPx = with(density) { 96.dp.toPx() }
                            detectBubbleGestures(
                                onTouchStart = {
                                    // Capture peek state before clearing it so onTap can distinguish
                                    // "restore from peek" from "open panel".
                                    peekedAtDown = peeked
                                    peeked = false
                                    gestureCount++
                                },
                                onDragStart = {
                                    isDragging = true
                                },
                                onDrag = { delta ->
                                    val newOffset = IntOffset(
                                        (bubbleOffset.x + delta.x.roundToInt())
                                            .coerceIn(0, (screenWidthPx - bubblePx).coerceAtLeast(0)),
                                        (bubbleOffset.y + delta.y.roundToInt())
                                            .coerceIn(0, (screenHeightPx - bubblePx).coerceAtLeast(0)),
                                    )
                                    bubbleOffset = newOffset
                                    val centerX = newOffset.x + bubblePx / 2
                                    val centerY = newOffset.y + bubblePx / 2
                                    isDismissZoneActive = centerY > screenHeightPx - bubblePx * 2 &&
                                        centerX in (screenWidthPx / 2 - bubblePx)..(screenWidthPx / 2 + bubblePx)
                                },
                                onDragEnd = {
                                    val centerX = bubbleOffset.x + bubblePx / 2
                                    val centerY = bubbleOffset.y + bubblePx / 2
                                    val inDismissZone = centerY > screenHeightPx - bubblePx * 2 &&
                                        centerX in (screenWidthPx / 2 - bubblePx)..(screenWidthPx / 2 + bubblePx)
                                    isDismissZoneActive = false
                                    if (inDismissZone) {
                                        // Animate the bubble off the bottom of the screen before
                                        // removing the window — keeps isDragging=true so the full-screen
                                        // window stays alive long enough for the animation to complete.
                                        animationScope.launch {
                                            Animatable(bubbleOffset.y.toFloat()).animateTo(
                                                targetValue = (screenHeightPx + bubblePx).toFloat(),
                                                animationSpec = tween(durationMillis = 250),
                                            ) {
                                                bubbleOffset = IntOffset(bubbleOffset.x, value.roundToInt())
                                            }
                                            isDragging = false
                                            onDismiss()
                                        }
                                    } else {
                                        isDragging = false
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
                                onTap = {
                                    // When the bubble was peeked at touch-down, the tap's purpose is
                                    // to restore full visibility — don't also open the panel.
                                    if (!peekedAtDown) {
                                        mode = OverlayMode.EXPANDED
                                    }
                                    peekedAtDown = false
                                },
                                onLongPress = { mode = OverlayMode.QUICK_ACTIONS },
                                onQuickActionsMove = { posFromBubbleCenter ->
                                    // Drive AppState.attention from how far the finger has moved
                                    // from the bubble centre toward the arc perimeter (0 = at
                                    // centre, 1 = at the arc radius or beyond).
                                    val distance = posFromBubbleCenter.getDistance()
                                    appStateHolder.setAttention((distance / arcRadiusPx).coerceIn(0f, 1f))
                                },
                                onQuickActionsRelease = { posFromBubbleCenter ->
                                    // Reset attention first — covers cancel and select paths alike.
                                    appStateHolder.setAttention(0f)
                                    val hitAction = quickActionsHitTest(
                                        geometry = geometry,
                                        anchorEdgeIsStart = isAtStartEdge,
                                        posFromBubbleCenter = posFromBubbleCenter,
                                        density = density,
                                        quickActionsWindowDp = QUICK_ACTIONS_WINDOW_DP,
                                        bubbleSizeDp = BUBBLE_SIZE_DP,
                                    )
                                    if (hitAction != null) executeQuickAction(hitAction)
                                    mode = OverlayMode.COLLAPSED
                                },
                            )
                        },
                ) {
                    CharacterPlaceholder(state = appState.characterState, size = BUBBLE_SIZE_DP.dp)
                }
            }
        }
    }

    // Confirmation dialog overlay — rendered on top of whatever mode is active when a destructive
    // tool action is pending user approval.
    appState.pendingConfirmation?.let { request ->
        ConfirmationDialog(request = request, appStateHolder = appStateHolder)
    }
    } // end Box
}

/**
 * Bottom-center target rendered only while a drag gesture is active. [active] turns the
 * background from a muted hint to a prominent highlight so the user knows releasing here will
 * dismiss the bubble. Width ≥ 96 dp per spec B3.
 */
@Composable
private fun DismissZoneIndicator(active: Boolean, modifier: Modifier = Modifier) {
    val backgroundColor = if (active) {
        MaterialTheme.colorScheme.error.copy(alpha = 0.80f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.60f)
    }
    val iconTint = if (active) {
        MaterialTheme.colorScheme.onError
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = modifier
            .size(width = DISMISS_ZONE_WIDTH_DP.dp, height = DISMISS_ZONE_HEIGHT_DP.dp)
            .background(backgroundColor),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = "Dismiss bubble",
            tint = iconTint,
        )
    }
}

/**
 * Disambiguates tap / drag / long-press on a single pointer stream. Compose's built-in
 * `detectTapGestures`/`detectDragGestures` can't be combined on one target since both would
 * race for the same down event, so this races a long-press timer against incoming pointer
 * events by hand.
 *
 * After the long-press fires, pointer movement and the eventual release are delivered via
 * [onQuickActionsMove] and [onQuickActionsRelease] respectively. Both receive the finger
 * position relative to the bubble centre in pixels, so the caller can drive [AppState.attention]
 * and resolve which quick-action target (if any) was released on.
 */
private suspend fun PointerInputScope.detectBubbleGestures(
    onTouchStart: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onQuickActionsMove: (posFromBubbleCenter: Offset) -> Unit = {},
    onQuickActionsRelease: (posFromBubbleCenter: Offset) -> Unit = {},
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    onTouchStart()
    val downUptimeMillis = SystemClock.uptimeMillis()
    var dragging = false
    var longPressFired = false
    var totalDrag = Offset.Zero
    val slop = viewConfiguration.touchSlop
    val bubbleCenter = Offset(size.width / 2f, size.height / 2f)

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
            when {
                dragging -> onDragEnd()
                longPressFired -> onQuickActionsRelease(change.position - bubbleCenter)
                else -> onTap()
            }
            break
        }
        val delta = change.positionChange()
        totalDrag += delta
        // Do not start a drag after the long-press has fired — finger movement post-long-press
        // drives quick-action attention, not a bubble drag.
        if (!dragging && !longPressFired && totalDrag.getDistance() > slop) {
            dragging = true
            onDragStart()
        }
        if (dragging) {
            change.consume()
            onDrag(delta)
        } else if (longPressFired) {
            onQuickActionsMove(change.position - bubbleCenter)
        }
    }
}

/** RMS amplitude of a 16-bit PCM frame, normalized to 0f..1f. */
private fun rmsLevel(samples: ShortArray): Float {
    if (samples.isEmpty()) return 0f
    var sumSquares = 0.0
    for (sample in samples) sumSquares += sample.toDouble() * sample.toDouble()
    val rms = kotlin.math.sqrt(sumSquares / samples.size)
    return (rms / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
}
