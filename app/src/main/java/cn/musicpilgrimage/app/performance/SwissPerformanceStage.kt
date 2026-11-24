package cn.musicpilgrimage.app.performance

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.musicpilgrimage.app.ui.AppNavEasing
import cn.musicpilgrimage.app.ui.room.PianoWallTextures
import cn.musicpilgrimage.app.ui.room.tiledMaterial

private val CavityCharcoalBase = Color(0xFF161A18)
private val CavityDeepVignette = Color(0xFF0A0C0B)
private val IvoryStageHighlight = Color(0xFFF8F6F0)
private val CrimsonStageAccent = Color(0xFFBD382B)

@Composable
internal fun SwissPerformanceStage(
    song: SongPackage,
    ui: PerformanceUi,
    motion: Boolean,
    modifier: Modifier = Modifier,
    stageLightIntensity: Float = 1f,
    ambientGlow: Float = 0f,
    residualGlow: Float = 0f,
) {
    val context = LocalContext.current
    val ebony = remember(context) { PianoWallTextures.load(context, "design/music-space/piano-ebony.jpg") }
    val playing = ui.state == PlayState.PLAYING || ui.state == PlayState.STARTING || ui.state == PlayState.RECOVERING
    val targetDim = when {
        ui.state == PlayState.PAUSED -> 0.42f
        ui.state == PlayState.READY || ui.state == PlayState.LOADING -> 0.28f
        else -> 0f
    }
    val dimAlpha by animateFloatAsState(
        targetValue = targetDim,
        animationSpec = if (motion) tween(220, easing = AppNavEasing) else tween(0),
        label = "bauhaus-stage-dim",
    )
    val clarity = stageLightIntensity.coerceIn(0.65f, 1.35f)
    val sectionPulse = (ambientGlow * (if (playing) 1f else 0.35f)).coerceIn(0f, 1f)
    val hitPulse = (residualGlow * (if (playing) 1f else 0.25f)).coerceIn(0f, 1f)
    // Keep parameter referenced without adding off-palette per-song backgrounds.
    val hasChartSections = remember(song.id) { song.sections.isNotEmpty() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CavityCharcoalBase)
            .tiledMaterial(ebony, 240.dp, CavityCharcoalBase) {
                drawRect(Color(0x40121513))
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val topSpot = Offset(size.width * 0.22f, size.height * 0.14f)
            val ambientRadius = size.maxDimension * 0.72f
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        IvoryStageHighlight.copy(alpha = 0.045f * clarity),
                        Color.Transparent,
                    ),
                    center = topSpot,
                    radius = ambientRadius,
                ),
            )
            if (hasChartSections && sectionPulse > 0.01f) {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            CrimsonStageAccent.copy(alpha = 0.12f * sectionPulse),
                            IvoryStageHighlight.copy(alpha = 0.04f * sectionPulse),
                            Color.Transparent,
                        ),
                        center = Offset(size.width * 0.5f, size.height * 0.28f),
                        radius = size.maxDimension * 0.52f,
                    ),
                )
            }
            if (hitPulse > 0.01f) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            IvoryStageHighlight.copy(alpha = 0.055f * hitPulse),
                            CrimsonStageAccent.copy(alpha = 0.04f * hitPulse),
                        ),
                        startY = size.height * 0.45f,
                        endY = size.height,
                    ),
                    topLeft = Offset.Zero,
                    size = Size(size.width, size.height),
                )
            }
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        CavityDeepVignette.copy(alpha = 0.34f),
                        Color.Transparent,
                        CavityDeepVignette.copy(alpha = 0.42f),
                    ),
                ),
            )
            if (dimAlpha > 0.005f) {
                drawRect(Color(0xFF0A0C0B).copy(alpha = dimAlpha))
            }
        }
    }
}
