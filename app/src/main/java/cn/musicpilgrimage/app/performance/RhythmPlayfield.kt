package cn.musicpilgrimage.app.performance

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.musicpilgrimage.app.ui.LocalPhysicalMotion
import kotlin.math.atan2

private const val HorizonUs = 1_550_000L
private val StrikeLineFromBottom = 72.dp
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
        for (lane in 0..3) {
            val left = lane * laneWidth
            drawRect(
                color = if (lane % 2 == 0) CavitySlotDark.copy(alpha = 0.42f) else CavitySlotAlternate.copy(alpha = 0.30f),
                topLeft = Offset(left, 0f),
                size = Size(laneWidth, size.height),
            )
            if (lane > 0) {
                drawLine(
                    color = LaneDividerSilver.copy(alpha = 0.09f * clarity),
                    start = Offset(left, 0f),
                    end = Offset(left, size.height),
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
        // 1. Minimalist judgment hairline & falling notes passing cleanly through the strike line.
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width / 4f
            val line = (size.height - StrikeLineFromBottom.toPx()).coerceAtLeast(1f)
            val pps = line / HorizonUs
            val min = timeUs - 340_000L
            val max = timeUs + HorizonUs

            // Clean, minimalist solid-color Bauhaus judgment line.
            drawLine(
                color = IvoryKeyFace.copy(alpha = 0.78f * clarity),
                start = Offset(0f, line),
                end = Offset(size.width, line),
                strokeWidth = 1.5.dp.toPx(),
            )

            clipRect(left = 0f, top = 0f, right = size.width, bottom = size.height) {
                // Draw subtle horizontal Bauhaus sync bars connecting simultaneous chord pairs.
                var prevVisible: Target? = null
                controller.targets.forEach { t ->
                    if (t.endUs < min || t.onsetUs > max) return@forEach
                    val j = ui.judgments.getOrElse(t.id) { Judgment.WAITING }
                    if (j in listOf(Judgment.PERFECT, Judgment.GOOD, Judgment.PARTIAL)) return@forEach
                    val prev = prevVisible
                    if (prev != null && prev.onsetUs == t.onsetUs && prev.lane != t.lane) {
                        val y = line - (t.onsetUs - timeUs) * pps
                        val passAlpha = if (y > line) {
                            (1f - ((y - line) / (StrikeLineFromBottom.toPx() * 0.92f))).coerceIn(0f, 1f)
                        } else 1f
                        if (passAlpha > 0.04f) {
                            val x1 = prev.lane * w + w / 2f
                            val x2 = t.lane * w + w / 2f
                            drawLine(
                                color = IvoryKeyFace.copy(alpha = 0.44f * passAlpha),
                                start = Offset(x1, y),
                                end = Offset(x2, y),
                                strokeWidth = 1.25.dp.toPx(),
                            )
                        }
                    }
                    prevVisible = t
                }

                controller.targets.forEach { t ->
                    if (t.endUs < min || t.onsetUs > max) return@forEach
                    val j = ui.judgments.getOrElse(t.id) { Judgment.WAITING }
                    if (j in listOf(Judgment.PERFECT, Judgment.GOOD, Judgment.PARTIAL)) return@forEach
                    val x = t.lane * w + w / 2f
                    val y = line - (t.onsetUs - timeUs) * pps
                    if (t.hold) {
                        val isHolding = j == Judgment.HOLDING
                        val heldY = if (isHolding) line else y
                        val topY = (line - (t.endUs - timeUs) * pps).coerceAtLeast(-20f)
                        val holdAlpha = when {
                            isHolding -> 1f
                            j == Judgment.MISSED -> 0.30f
                            y > line -> (1f - ((y - line) / (StrikeLineFromBottom.toPx() * 1.25f))).coerceIn(0.30f, 1f)
                            else -> 1f
                        }
                        if (heldY > topY) {
                            performanceRibbon(
                                x = x,
                                top = topY,
                                bottom = heldY,
                                w = w,
                                color = noteRedColor,
                                holding = isHolding,
                                timeUs = timeUs,
                                motion = motion,
                                alphaScale = holdAlpha,
                                seed = t.id + t.lane * 17,
                                heldFraction = ui.holdFractions.getOrElse(t.id) { 0f },
                            )
                        }
                    } else if (t.flick) {
                        val passAlpha = if (y > line) {
                            (1f - ((y - line) / (StrikeLineFromBottom.toPx() * 0.92f))).coerceIn(0f, 1f)
                        } else 1f
                        if (passAlpha <= 0.02f) return@forEach
                        drawFlickNote(x, y, w, alphaScale = if (j == Judgment.MISSED) 0.26f * passAlpha else passAlpha)
                    } else {
                        val passAlpha = if (y > line) {
                            (1f - ((y - line) / (StrikeLineFromBottom.toPx() * 0.92f))).coerceIn(0f, 1f)
                        } else 1f
                        if (passAlpha <= 0.02f) return@forEach
                        drawIvoryTapNote(x, y, w, alphaScale = if (j == Judgment.MISSED) 0.26f * passAlpha else passAlpha)
                    }
                }
            }
        }

        // 2. Judgment beams, sustain hold light effects, and layered Bauhaus impact bursts.
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width / 4f
            val line = (size.height - StrikeLineFromBottom.toPx()).coerceAtLeast(1f)
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
                val partnerX = event.chordPartnerLane?.let { it * w + w / 2f }
                performanceImpact(event, timeUs, x, line, w, noteRedColor, motion, partnerX = partnerX)
            }
        }

        // 3. Full-height multi-touch & omnidirectional flick layer keeping testTags intact.
        if (!listen) {
            Row(Modifier.fillMaxSize()) {
                for (lane in 0..3) {
                    val hold = held.firstOrNull { it.lane == lane }
                    val active = lane in pressed || hold != null
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .testTag("performance-lane-$lane")
                            .semantics {
                                contentDescription = "轨道 ${lane + 1}，轻点短音，按住长音，轻划滑键"
                                stateDescription = if (hold != null) "长按中" else "等待音符"
                            }
                            .pointerInput(controller, lane, view, acceptsInput) {
                                if (acceptsInput) {
                                    val flickThresholdPx = 12.dp.toPx()
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        down.consume()
                                        val downPos = down.position
                                        val downChartUs = controller.currentChartPositionUs()
                                        var flicked = false
                                        pressed = pressed + lane
                                        if (controller.down(lane)) {
                                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                        }
                                        try {
                                            do {
                                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                                change.consume()
                                                if (!flicked && change.pressed) {
                                                    val delta = change.position - downPos
                                                    if (delta.getDistance() >= flickThresholdPx) {
                                                        val angleRad = atan2(delta.y, delta.x)
                                                        if (controller.flick(lane, angleRad, downChartUs)) {
                                                            flicked = true
                                                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                                        }
                                                    }
                                                }
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
                        Text(
                            text = (lane + 1).toString(),
                            modifier = Modifier
                                .padding(bottom = 24.dp)
                                .let { if (hold != null) it.testTag("performance-hold-$lane") else it },
                            color = if (active) IvoryKeyFace.copy(alpha = 0.88f) else MutedSilverText.copy(alpha = 0.36f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawIvoryTapNote(x: Float, y: Float, w: Float, alphaScale: Float) {
    val nw = w * NoteWidthRatio
    val nh = 32.dp.toPx()
    val left = x - nw * 0.5f
    // Align note center with the elevated judgment line coordinate `y` for unambiguous strike timing.
    val top = y - nh * 0.5f
    val alpha = alphaScale.coerceIn(0f, 1f)
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
    // Top highlight + precision center mark (clean bottom edge without any lip bar).
    drawLine(
        color = Color.White.copy(alpha = 0.85f * alpha),
        start = Offset(left + 1.dp.toPx(), top + 1.dp.toPx()),
        end = Offset(left + nw - 1.dp.toPx(), top + 1.dp.toPx()),
        strokeWidth = 1.dp.toPx(),
    )
    drawLine(
        color = IvoryKeyBorder.copy(alpha = 0.22f * alpha),
        start = Offset(left + nw * 0.28f, y),
        end = Offset(left + nw * 0.72f, y),
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

private fun DrawScope.drawFlickNote(x: Float, y: Float, w: Float, alphaScale: Float) {
    val nw = w * NoteWidthRatio
    val nh = 30.dp.toPx()
    val left = x - nw * 0.5f
    val top = y - nh * 0.5f
    val alpha = alphaScale.coerceIn(0f, 1f)
    val cornerPx = 3.dp.toPx()
    val corner = CornerRadius(cornerPx, cornerPx)

    // Soft 11 o'clock cast shadow matching tap & hold notes seamlessly.
    drawRoundRect(
        color = Color(0xFF0A0C0B).copy(alpha = 0.46f * alpha),
        topLeft = Offset(left + 1.5.dp.toPx(), top + 2.5.dp.toPx()),
        size = Size(nw, nh),
        cornerRadius = corner,
    )
    // Silky luminous anodized crimson-coral keycap surface (refined gradient, zero muddiness).
    drawRoundRect(
        brush = Brush.linearGradient(
            0.00f to Color(0xFFED6852).copy(alpha = alpha),
            0.48f to Color(0xFFD43E35).copy(alpha = alpha),
            1.00f to Color(0xFFB82A38).copy(alpha = alpha),
            start = Offset(left, top),
            end = Offset(left + nw, top + nh),
        ),
        topLeft = Offset(left, top),
        size = Size(nw, nh),
        cornerRadius = corner,
    )
    // Delicate upper specular rim (clean bottom edge without any white lip bar).
    drawLine(
        color = Color.White.copy(alpha = 0.58f * alpha),
        start = Offset(left + 2.dp.toPx(), top + 1.dp.toPx()),
        end = Offset(left + nw - 2.dp.toPx(), top + 1.dp.toPx()),
        strokeWidth = 1.dp.toPx(),
    )
    // Soft radial inner lift behind the centered ivory directional glyph.
    val cy = y + 1.dp.toPx()
    drawCircle(
        brush = Brush.radialGradient(
            0.0f to Color(0xFFFFEDE2).copy(alpha = 0.24f * alpha),
            1.0f to Color.Transparent,
            center = Offset(x, cy),
            radius = nw * 0.34f,
        ),
        radius = nw * 0.34f,
        center = Offset(x, cy),
    )
    // Refined, silky-rounded ivory upward chevron with subtle shadow & faint echo arc above.
    val chevW = nw * 0.17f
    val chevH = 5.2.dp.toPx()
    val mainChev = Path().apply {
        moveTo(x - chevW, cy + chevH * 0.45f)
        lineTo(x, cy - chevH * 0.55f)
        lineTo(x + chevW, cy + chevH * 0.45f)
    }
    drawPath(
        path = mainChev,
        color = Color(0xFF420D12).copy(alpha = 0.24f * alpha),
        style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    drawPath(
        path = mainChev,
        color = IvoryKeyFace.copy(alpha = 0.96f * alpha),
        style = Stroke(width = 1.85.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    val echoW = nw * 0.105f
    val echoH = 3.dp.toPx()
    val echoY = cy - 4.2.dp.toPx()
    val echoChev = Path().apply {
        moveTo(x - echoW, echoY + echoH * 0.45f)
        lineTo(x, echoY - echoH * 0.55f)
        lineTo(x + echoW, echoY + echoH * 0.45f)
    }
    drawPath(
        path = echoChev,
        color = IvoryKeyFace.copy(alpha = 0.46f * alpha),
        style = Stroke(width = 1.15.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    drawRoundRect(
        color = Color.White.copy(alpha = 0.26f * alpha),
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
