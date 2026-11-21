package cn.musicpilgrimage.app.ui.room

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush

private fun atmosphereStrength(zoom: Double): Float {
    val z = zoom.toFloat()
    return WorldMapMath.smoothstep(3.8f, 5.2f, z) *
        (1f - WorldMapMath.smoothstep(5.8f, 7.5f, z))
}

@Composable
internal fun WorldTransitionAtmosphere(controller: NativeWorldController, modifier: Modifier = Modifier) {
    val crossing by remember(controller) {
        derivedStateOf { controller.isMoving && controller.pinZoom in 3.0..8.3 }
    }
    val fade = animateFloatAsState(
        targetValue = if (crossing) 1f else 0f,
        animationSpec = tween(durationMillis = 140),
        label = "world atmosphere",
    )
    Spacer(
        modifier.drawWithCache {
            val paper = RoomColors.Paper
            val upper = Brush.radialGradient(
                0f to paper.copy(alpha = 0.32f),
                0.53f to paper.copy(alpha = 0.14f),
                1f to paper.copy(alpha = 0f),
                center = Offset(size.width * 0.18f, -size.height * 0.28f),
                radius = size.height * 0.58f,
            )
            val lower = Brush.radialGradient(
                0f to paper.copy(alpha = 0.29f),
                0.55f to paper.copy(alpha = 0.12f),
                1f to paper.copy(alpha = 0f),
                center = Offset(size.width * 0.92f, size.height * 1.21f),
                radius = size.height * 0.49f,
            )
            val upperSize = Size(size.width, size.height * 0.30f)
            val lowerStart = Offset(0f, size.height * 0.72f)
            val lowerSize = Size(size.width, size.height - lowerStart.y)
            onDrawBehind {
                val amount = fade.value * atmosphereStrength(controller.pinZoom)
                if (amount > 0f) {
                    drawRect(upper, size = upperSize, alpha = amount)
                    drawRect(lower, topLeft = lowerStart, size = lowerSize, alpha = amount)
                }
            }
        }
    )
}
