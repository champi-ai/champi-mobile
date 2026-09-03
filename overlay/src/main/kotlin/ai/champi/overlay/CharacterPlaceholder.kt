package ai.champi.overlay

import ai.champi.core.state.CharacterState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/**
 * Stand-in for the Rive `champi_mushroom` artboard (see phase-1 spec) until that asset lands.
 * Color and pulse speed vary per [CharacterState] so the seven states read as visually distinct.
 */
@Composable
fun CharacterPlaceholder(state: CharacterState, size: Dp, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "breathing")
    val scale by transition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = pulseDurationMs(state), easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scale",
    )
    Box(
        modifier = modifier
            .size(size)
            .scale(scale)
            .clip(CircleShape)
            .background(colorFor(state)),
    )
}

private fun pulseDurationMs(state: CharacterState): Int = when (state) {
    CharacterState.IDLE -> 2200
    CharacterState.LISTENING -> 900
    CharacterState.THINKING -> 600
    CharacterState.SPEAKING -> 450
    CharacterState.NOTIFYING -> 350
    CharacterState.ERROR -> 250
    CharacterState.SLEEPING -> 3500
}

private fun colorFor(state: CharacterState): Color = when (state) {
    CharacterState.IDLE -> Color(0xFF6750A4)
    CharacterState.LISTENING -> Color(0xFF3D8BFF)
    CharacterState.THINKING -> Color(0xFFB388FF)
    CharacterState.SPEAKING -> Color(0xFF00C9A7)
    CharacterState.NOTIFYING -> Color(0xFFFFB300)
    CharacterState.ERROR -> Color(0xFFE53935)
    CharacterState.SLEEPING -> Color(0xFF546E7A)
}
