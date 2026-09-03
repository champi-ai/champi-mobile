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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

private data class QuickActionSpec(val action: QuickAction, val icon: ImageVector, val label: String)

private val ACTIONS = listOf(
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
