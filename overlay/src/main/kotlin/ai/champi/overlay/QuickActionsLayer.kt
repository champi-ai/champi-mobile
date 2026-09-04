package ai.champi.overlay

import ai.champi.core.overlay.QuickAction
import ai.champi.core.overlay.QuickActionGeometry
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

internal data class QuickActionSpec(val action: QuickAction, val icon: ImageVector, val label: String)

internal val ACTIONS = listOf(
    QuickActionSpec(QuickAction.MUTE_MIC, Icons.Filled.MicOff, "Mute mic"),
    QuickActionSpec(QuickAction.PUSH_TO_TALK, Icons.Filled.Mic, "Push to talk"),
    QuickActionSpec(QuickAction.SLEEP, Icons.Filled.Bedtime, "Sleep"),
    QuickActionSpec(QuickAction.SETTINGS, Icons.Filled.Settings, "Settings"),
)

/**
 * Long-press quick-actions surface. Both geometries from the phase-1 spec's open question are
 * implemented; [geometry] (persisted in [ai.champi.core.overlay.OverlayPreferencesRepository])
 * picks which one renders — there's no settings UI yet to switch it, that lands with the real
 * settings screen.
 */
@Composable
fun QuickActionsLayer(
    visible: Boolean,
    geometry: QuickActionGeometry,
    anchorEdgeIsStart: Boolean,
    onSelect: (QuickAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(initialScale = 0.8f),
        exit = fadeOut() + scaleOut(targetScale = 0.8f),
        modifier = modifier,
    ) {
        when (geometry) {
            QuickActionGeometry.RADIAL_ARC -> RadialArcActions(anchorEdgeIsStart, onSelect)
            QuickActionGeometry.EDGE_RAIL -> EdgeRailActions(onSelect)
        }
    }
}

@Composable
private fun RadialArcActions(anchorEdgeIsStart: Boolean, onSelect: (QuickAction) -> Unit) {
    val radiusDp = 96.dp
    Box(modifier = Modifier.size(radiusDp * 2)) {
        val (startAngle, endAngle) = if (anchorEdgeIsStart) -60f to 60f else 120f to 240f
        ACTIONS.forEachIndexed { index, spec ->
            val angleDeg = startAngle + index * (endAngle - startAngle) / (ACTIONS.size - 1)
            val angleRad = Math.toRadians(angleDeg.toDouble())
            val offsetX = (radiusDp.value * cos(angleRad)).dp
            val offsetY = (radiusDp.value * sin(angleRad)).dp
            QuickActionTarget(
                icon = spec.icon,
                contentDescription = spec.label,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(x = offsetX, y = offsetY)
                    .clickable { onSelect(spec.action) },
            )
        }
    }
}

@Composable
private fun EdgeRailActions(onSelect: (QuickAction) -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
    ) {
        Column {
            ACTIONS.forEach { spec ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clickable { onSelect(spec.action) }
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(spec.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
                    Text(spec.label, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
private fun QuickActionTarget(icon: ImageVector, contentDescription: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.size(48.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, tint = Color.White)
        }
    }
}

/**
 * Returns the [QuickAction] whose target contains [posFromBubbleCenter] (in pixels, relative to
 * the bubble's centre), or null if the position is over no target. Used by [OverlayRoot]'s
 * gesture handler to resolve a release event to an action without relying on the new composition
 * branch's own [androidx.compose.foundation.clickable] handlers, which cannot receive a pointer
 * stream that started in a different window layout.
 *
 * @param quickActionsWindowDp total size of the quick-actions window (square) in dp — must match
 *   [QUICK_ACTIONS_WINDOW_DP] in OverlayRoot.kt.
 * @param bubbleSizeDp collapsed bubble diameter in dp — must match [BUBBLE_SIZE_DP] in OverlayRoot.kt.
 */
internal fun quickActionsHitTest(
    geometry: QuickActionGeometry,
    anchorEdgeIsStart: Boolean,
    posFromBubbleCenter: Offset,
    density: Density,
    quickActionsWindowDp: Int,
    bubbleSizeDp: Int,
): QuickAction? = when (geometry) {
    QuickActionGeometry.RADIAL_ARC -> radialArcHitTest(
        pos = posFromBubbleCenter,
        anchorEdgeIsStart = anchorEdgeIsStart,
        density = density,
        quickActionsWindowDp = quickActionsWindowDp,
        bubbleSizeDp = bubbleSizeDp,
    )
    QuickActionGeometry.EDGE_RAIL -> edgeRailHitTest(
        pos = posFromBubbleCenter,
        anchorEdgeIsStart = anchorEdgeIsStart,
        density = density,
    )
}

/**
 * Hit-tests against the four radial arc targets. The arc centre is offset from the bubble centre
 * by half the difference between the quick-actions window and the bubble (horizontally), which
 * keeps the arc centred in the window while the bubble stays at the snapped edge.
 */
private fun radialArcHitTest(
    pos: Offset,
    anchorEdgeIsStart: Boolean,
    density: Density,
    quickActionsWindowDp: Int,
    bubbleSizeDp: Int,
): QuickAction? {
    val radiusPx = with(density) { 96.dp.toPx() }
    val hitRadiusPx = with(density) { 24.dp.toPx() }
    val arcOffsetPx = with(density) { ((quickActionsWindowDp - bubbleSizeDp) / 2f).dp.toPx() }
    val arcCenter = if (anchorEdgeIsStart) Offset(arcOffsetPx, 0f) else Offset(-arcOffsetPx, 0f)
    val posFromArc = pos - arcCenter
    val (startAngle, endAngle) = if (anchorEdgeIsStart) -60f to 60f else 120f to 240f
    for (i in ACTIONS.indices) {
        val angleDeg = startAngle + i * (endAngle - startAngle) / (ACTIONS.size - 1)
        val angleRad = Math.toRadians(angleDeg.toDouble())
        val targetPos = Offset(
            x = (radiusPx * cos(angleRad)).toFloat(),
            y = (radiusPx * sin(angleRad)).toFloat(),
        )
        if ((posFromArc - targetPos).getDistance() <= hitRadiusPx) return ACTIONS[i].action
    }
    return null
}

/**
 * Hit-tests against the four edge-rail rows. Rows are 48 dp tall, stacked in a column that is
 * vertically centred on the bubble centre. A hit requires the finger to be on the correct
 * horizontal side (away from the snapped edge) and within the column's vertical span.
 */
private fun edgeRailHitTest(
    pos: Offset,
    anchorEdgeIsStart: Boolean,
    density: Density,
): QuickAction? {
    val rowHeightPx = with(density) { 48.dp.toPx() }
    val totalHeightPx = rowHeightPx * ACTIONS.size
    val railTopFromCenterPx = -totalHeightPx / 2f
    val relY = pos.y - railTopFromCenterPx
    if (relY < 0 || relY > totalHeightPx) return null
    // Finger must have moved toward the rail side (away from the snapped edge).
    if (anchorEdgeIsStart && pos.x <= 0f) return null
    if (!anchorEdgeIsStart && pos.x >= 0f) return null
    val rowIndex = (relY / rowHeightPx).toInt().coerceIn(0, ACTIONS.size - 1)
    return ACTIONS[rowIndex].action
}
