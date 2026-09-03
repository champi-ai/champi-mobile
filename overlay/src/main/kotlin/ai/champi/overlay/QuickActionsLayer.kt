package ai.champi.overlay

import ai.champi.core.overlay.QuickAction
import ai.champi.core.overlay.QuickActionGeometry
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

private val ACTIONS = listOf(
    QuickAction.MUTE_MIC to "Mute",
    QuickAction.PUSH_TO_TALK to "Talk",
    QuickAction.SLEEP to "Sleep",
    QuickAction.SETTINGS to "Settings",
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
        ACTIONS.forEachIndexed { index, (action, label) ->
            val angleDeg = startAngle + index * (endAngle - startAngle) / (ACTIONS.size - 1)
            val angleRad = Math.toRadians(angleDeg.toDouble())
            val offsetX = (radiusDp.value * cos(angleRad)).dp
            val offsetY = (radiusDp.value * sin(angleRad)).dp
            QuickActionTarget(
                label = label,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(x = offsetX, y = offsetY)
                    .clickable { onSelect(action) },
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
            ACTIONS.forEach { (action, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clickable { onSelect(action) }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
private fun QuickActionTarget(label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.size(48.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label.take(2), color = Color.White)
        }
    }
}
