package ai.champi.overlay

import ai.champi.core.state.AppState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

private const val SWIPE_DOWN_DISMISS_THRESHOLD_DP = 48

/**
 * Empty conversation panel shown when the bubble is tapped (phase 1: no real transcript yet).
 * There's no "tap outside" region to detect (see [OverlayRoot]'s comment on why), so this
 * collapses on a background tap or a swipe-down instead.
 */
@Composable
fun ExpandedPanel(appState: AppState, onCollapse: () -> Unit, modifier: Modifier = Modifier) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val dismissThresholdPx = with(density) { SWIPE_DOWN_DISMISS_THRESHOLD_DP.dp.toPx() }

    Surface(
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onCollapse() }
            .pointerInput(Unit) {
                var totalDragY = 0f
                detectVerticalDragGestures(
                    onDragStart = { totalDragY = 0f },
                    onDragEnd = { if (totalDragY > dismissThresholdPx) onCollapse() },
                ) { change, dragAmount ->
                    change.consume()
                    totalDragY += dragAmount
                }
            },
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(width = 32.dp, height = 4.dp)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CharacterPlaceholder(state = appState.characterState, size = 96.dp)
                Text("Champi", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(16.dp))
            if (appState.conversation.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "No conversation yet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(appState.conversation) { entry -> Text(entry.text) }
                }
            }
        }
    }
}
