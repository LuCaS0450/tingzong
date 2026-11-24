package cn.musicpilgrimage.app.performance

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.musicpilgrimage.app.ui.AppNavEasing
import cn.musicpilgrimage.app.ui.LocalPhysicalMotion
import cn.musicpilgrimage.app.ui.room.PianoWallTextures
import cn.musicpilgrimage.app.ui.room.tiledMaterial
import kotlin.math.roundToInt

private const val HorizonUs = 1_550_000L
private val KeycapZoneHeight = 84.dp
private val CavitySlotDark = Color(0xFF141715)
private val CavitySlotAlternate = Color(0xFF171B19)
private val LaneDividerSilver = Color(0xFFE2E8E8)
private val IvoryKeyFace = Color(0xFFF8F6F0)
private val IvoryKeyFront = Color(0xFFD5CFC2)
private val IvoryKeyBorder = Color(0xFF242B27)
private val IvoryNoteTop = Color(0xFFFDFBF6)
private val IvoryNoteBottom = Color(0xFFEBE6D9)
private val CrimsonSignal = Color(0xFFBD382B)
private val CrimsonHighlight = Color(0xFFD64B3D)
private val MutedSilverText = Color(0xFF9DA49E)

@Composable
internal fun MusicLaneBackdrop(modifier: Modifier, stageClarity: Float = 1f) {
    val clarity = stageClarity.coerceIn(0.6f, 1.3f)
    Canvas(modifier.fillMaxSize()) {
        val laneWidth = size.width / 4f
        val line = (size.height - KeycapZoneHeight.toPx()).coerceAtLeast(1f)
        for (lane in 0..3) {
            val left = lane * laneWidth
            drawRect(
                color = if (lane % 2 == 0) CavitySlotDark.copy(alpha = 0.42f) else CavitySlotAlternate.copy(alpha = 0.30f),
                topLeft = Offset(left, 0f),
                size = Size(laneWidth, line),
            )
            if (lane > 0) {
                drawLine(
                    color = LaneDividerSilver.copy(alpha = 0.10f * clarity),
                    start = Offset(left, 0f),
                    end = Offset(left, line),
                    strokeWidth = 0.75.dp.toPx(),
                )
            }
        }
    }
}

@Composable
internal fun MusicLanes(
    controller: PerformanceController,
    ui: PerformanceUi,
    motion: Boolean,
    modifier: Modifier,
    stageClarity: Float = 1f,
) {
    val listen = controller.options.mode == PlayMode.LISTEN
    val view = LocalView.current
    val context = LocalContext.current
    val ivory = remember(context) { PianoWallTextures.load(context, "design/music-space/piano-ivory.jpg") }
    var pressed by remember(controller) { mutableStateOf(emptySet<Int>()) }
    val held = controller.targets.filter {
        it.hold && ui.judgments.getOrNull(it.id) == Judgment.HOLDING
    }
    val acceptsInput = ui.state == PlayState.PLAYING && ui.countIn == 0 && !listen
    val timeUs = ui.positionUs
    val clarity = stageClarity.coerceIn(0.6f, 1.3f)
    val noteIvoryColor = IvoryKeyFace
    val noteRedColor = CrimsonSignal

    Box(modifier.fillMaxSize().clipToBounds()) {
        // 1. Falling tap blocks & hold ribbons clipped at the judgment seam.
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width / 4f
            val line = (size.height - KeycapZoneHeight.toPx()).coerceAtLeast(1f)
            val pps = line / HorizonUs
            val min = timeUs - 240_000L
            val max = timeUs + HorizonUs

            clipRect(left = 0f, top = 0f, right = size.width, bottom = line + 1.5.dp.toPx()) {
                controller.targets.forEach { t ->
                    if (t.endUs < min || t.onsetUs > max) return@forEach
                    val j = ui.judgments.getOrElse(t.id) { Judgment.WAITING }
                    if (j in listOf(Judgment.PERFECT, Judgment.GOOD, Judgment.PARTIAL)) return@forEach
                    val x = t.lane * w + w / 2f
                    val y = line - (t.onsetUs - timeUs) * pps
                    if (t.hold) {
                        val heldY = if (j == Judgment.HOLDING) line else y
                        val topY = (line - (t.endUs - timeUs) * pps).coerceAtLeast(-20f)
                        performanceRibbon(x, topY, heldY, w, noteRedColor, j == Judgment.HOLDING)
                        if (j != Judgment.HOLDING) {
                            drawHoldHead(x, y, w)
                        }
                    } else {
                        drawIvoryTapNote(x, y, w, faded = j == Judgment.MISSED)
                    }
                }
            }

            // Judgment threshold groove directly above the ivory keycaps.
            drawRect(
                color = Color(0xFF0B0D0C),
                topLeft = Offset(0f, line - 1.5.dp.toPx()),
                size = Size(size.width, 3.dp.toPx()),
            )
            drawLine(
                color = IvoryKeyFace.copy(alpha = 0.28f * clarity),
                start = Offset(0f, line),
                end = Offset(size.width, line),
                strokeWidth = 1.dp.toPx(),
            )
        }

        // 2. Four micro-3D ivory keycaps across the bottom strike zone.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(KeycapZoneHeight)
                .align(Alignment.BottomCenter)
                .background(Color(0xFF141715))
                .padding(top = 1.5.dp),
            horizontalArrangement = Arrangement.spacedBy(1.5.dp),
        ) {
            for (lane in 0..3) {
                val active = lane in pressed || held.any { it.lane == lane }
                val keyDrop by animateDpAsState(
                    targetValue = if (active) 2.dp else 0.dp,
                    animationSpec = if (motion) tween(60, easing = AppNavEasing) else tween(0),
                    label = "keycap-drop-$lane",
                )
                val keyShape = RoundedCornerShape(
                    topStart = 1.dp,
                    topEnd = 1.dp,
                    bottomStart = 2.dp,
                    bottomEnd = 2.dp,
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .offset { IntOffset(0, keyDrop.toPx().roundToInt()) }
                        .clip(keyShape)
                        .background(IvoryKeyFace)
                        .tiledMaterial(ivory, 220.dp, IvoryKeyFace),
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        // Top crimson actuation bar or resting ivory specular lip.
                        if (active) {
                            drawRect(
                                color = Color(0x160C0E0D),
                                topLeft = Offset.Zero,
                                size = size,
                            )
                            drawRect(
                                color = CrimsonSignal,
                                topLeft = Offset.Zero,
                                size = Size(size.width, 2.5.dp.toPx()),
                            )
                        } else {
                            drawRect(
                                color = Color.White.copy(alpha = 0.72f),
                                topLeft = Offset.Zero,
                                size = Size(size.width, 1.2.dp.toPx()),
                            )
                        }
                        // Left 11 o'clock bevel highlight.
                        drawRect(
                            color = Color.White.copy(alpha = if (active) 0.16f else 0.34f),
                            topLeft = Offset.Zero,
                            size = Size(1.dp.toPx(), size.height),
                        )
                        // Bottom key front thickness (thinner when pressed down into the keybed).
                        val frontHeight = (if (active) 2.dp else 4.dp).toPx()
                        drawRect(
                            color = IvoryKeyFront,
                            topLeft = Offset(0f, size.height - frontHeight),
                            size = Size(size.width, frontHeight),
                        )
                        drawLine(
                            color = IvoryKeyBorder.copy(alpha = 0.16f),
                            start = Offset(0f, size.height - frontHeight),
                            end = Offset(size.width, size.height - frontHeight),
                            strokeWidth = 0.75.dp.toPx(),
                        )
                    }
                }
            }
        }

        // 3. Judgment beams, sustain hold progress above keycaps, and geometric impact bursts.
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width / 4f
            val line = (size.height - KeycapZoneHeight.toPx()).coerceAtLeast(1f)
            for (lane in 0..3) {
                val x = lane * w + w / 2f
                performanceReceptor(x, line, w, noteIvoryColor, lane in pressed)
            }
            held.forEach { t ->
                performanceSustain(
                    x = t.lane * w + w / 2f,
                    line = line,
                    w = w,
                    color = noteIvoryColor,
                    fraction = ui.holdFractions.getOrElse(t.id) { 0f },
                    timeUs = timeUs,
                    motion = motion,
                )
            }
            ui.feedbacks.forEach { event ->
                val x = event.lane * w + w / 2f
                performanceImpact(event, timeUs, x, line, w, noteRedColor, motion)
            }
        }

        // 4. Full-height touch & semantics layer keeping existing testTags and labels intact.
        if (!listen) {
            Row(Modifier.fillMaxSize()) {
                for (lane in 0..3) {
                    val hold = held.firstOrNull { it.lane == lane }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .testTag("performance-lane-$lane")
                            .semantics {
                                contentDescription = "轨道 ${lane + 1}，轻点短音，按住长音"
                                stateDescription = if (hold != null) "长按中" else "等待音符"
                            }
                            .pointerInput(controller, lane, view, acceptsInput) {
                                if (acceptsInput) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        down.consume()
                                        pressed = pressed + lane
                                        if (controller.down(lane)) {
                                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                        }
                                        try {
                                            do {
                                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                                change.consume()
                                            } while (change.pressed)
                                        } finally {
                                            pressed = pressed - lane
                                            controller.up(lane)
                                        }
                                    }
                                }
                            },
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        if (hold != null) {
                            Text(
                                text = "按住 ${(ui.holdFractions.getOrElse(hold.id) { 0f } * 100).toInt()}%",
                                modifier = Modifier
                                    .padding(bottom = 30.dp)
                                    .testTag("performance-hold-$lane"),
                                color = CrimsonSignal,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        } else {
                            Text(
                                text = (lane + 1).toString(),
                                modifier = Modifier.padding(bottom = 30.dp),
                                color = IvoryKeyBorder.copy(alpha = 0.42f),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawIvoryTapNote(x: Float, y: Float, w: Float, faded: Boolean) {
    val nw = w * 0.78f
    val nh = 16.dp.toPx()
    val left = x - nw * 0.5f
    val top = y - nh * 0.5f
    val alpha = if (faded) 0.28f else 1f
    val corner = CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx())

    // 11 o'clock cast shadow on matte charcoal cavity.
    drawRoundRect(
        color = Color(0xFF0A0C0B).copy(alpha = 0.54f * alpha),
        topLeft = Offset(left + 1.5.dp.toPx(), top + 2.5.dp.toPx()),
        size = Size(nw, nh),
        cornerRadius = corner,
    )
    // Brushed ivory top face gradient.
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                IvoryNoteTop.copy(alpha = alpha),
                IvoryKeyFace.copy(alpha = alpha),
                IvoryNoteBottom.copy(alpha = alpha),
            ),
            startY = top,
            endY = top + nh,
        ),
        topLeft = Offset(left, top),
        size = Size(nw, nh),
        cornerRadius = corner,
    )
    // Subtle front thickness strip + top highlight.
    val lip = 2.5.dp.toPx()
    drawRoundRect(
        color = IvoryKeyFront.copy(alpha = alpha),
        topLeft = Offset(left, top + nh - lip),
        size = Size(nw, lip),
        cornerRadius = corner,
    )
    drawLine(
        color = Color.White.copy(alpha = 0.85f * alpha),
        start = Offset(left + 1.dp.toPx(), top + 1.dp.toPx()),
        end = Offset(left + nw - 1.dp.toPx(), top + 1.dp.toPx()),
        strokeWidth = 1.dp.toPx(),
    )
    drawRoundRect(
        color = IvoryKeyBorder.copy(alpha = 0.85f * alpha),
        topLeft = Offset(left, top),
        size = Size(nw, nh),
        cornerRadius = corner,
        style = Stroke(width = 0.75.dp.toPx()),
    )
}

private fun DrawScope.drawHoldHead(x: Float, y: Float, w: Float) {
    val nw = w * 0.76f
    val nh = 14.dp.toPx()
    val left = x - nw * 0.5f
    val top = y - nh * 0.5f
    val corner = CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx())
    drawRoundRect(
        color = Color(0xFF0A0C0B).copy(alpha = 0.52f),
        topLeft = Offset(left + 1.5.dp.toPx(), top + 2.5.dp.toPx()),
        size = Size(nw, nh),
        cornerRadius = corner,
    )
    drawRoundRect(
        color = CrimsonSignal,
        topLeft = Offset(left, top),
        size = Size(nw, nh),
        cornerRadius = corner,
    )
    drawLine(
        color = IvoryKeyFace.copy(alpha = 0.88f),
        start = Offset(left + 2.dp.toPx(), top + 2.dp.toPx()),
        end = Offset(left + nw - 2.dp.toPx(), top + 2.dp.toPx()),
        strokeWidth = 1.5.dp.toPx(),
    )
    drawRoundRect(
        color = IvoryKeyBorder.copy(alpha = 0.9f),
        topLeft = Offset(left, top),
        size = Size(nw, nh),
        cornerRadius = corner,
        style = Stroke(width = 0.75.dp.toPx()),
    )
}

@Composable
internal fun PerformanceFeedback(ui: PerformanceUi, modifier: Modifier = Modifier) {
    val latest = ui.feedbacks.lastOrNull { (ui.positionUs - it.atUs) in 0..550_000 }
    val motion = LocalPhysicalMotion.current
    Column(
        modifier = modifier.testTag("performance-feedback"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (ui.combo > 0) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                RollingTabularDigits(
                    valueText = ui.combo.toString(),
                    fontSize = 36.sp,
                    color = IvoryKeyFace,
                    motion = motion,
                    fontWeight = FontWeight.Bold,
                    durationMillis = 90,
                    horizontalArrangement = Arrangement.Center,
                )
                Text(
                    text = "COMBO",
                    color = MutedSilverText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.6.sp,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
        }
        if (latest != null) {
            val tone = when {
                !latest.kind.success -> CrimsonHighlight
                latest.grade == Judgment.PERFECT -> IvoryKeyFace
                else -> IvoryNoteBottom
            }
            Row(
                modifier = Modifier
                    .background(Color(0xFF1A1E1C).copy(alpha = 0.88f), RoundedCornerShape(1.5.dp))
                    .border(0.75.dp, Color(0xFF2E3531), RoundedCornerShape(1.5.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier
                        .width(2.5.dp)
                        .height(10.dp)
                        .background(if (latest.kind.success) CrimsonSignal else CrimsonHighlight),
                )
                Text(
                    text = latest.label,
                    color = tone,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.1.sp,
                )
            }
        }
    }
}
