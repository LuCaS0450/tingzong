package cn.musicpilgrimage.app.performance

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import cn.musicpilgrimage.app.ui.AppNavEasing
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

internal data class SectionAmbientCue(
    val sectionIndex: Int,
    val startUs: Long,
    val durationUs: Long,
)

/**
 * Computes eligible finite ambient cues at section boundaries where the judgment zone
 * has clear breathing room. Dense note runs keep brightness strictly steady.
 */
@Composable
internal fun rememberSectionAmbientCues(
    song: SongPackage,
    targets: List<Target>,
    startUs: Long,
    endUs: Long,
): List<SectionAmbientCue> = remember(song.id, targets, startUs, endUs) {
    computeSectionAmbientCues(song, targets, startUs, endUs)
}

internal fun computeSectionAmbientCues(
    song: SongPackage,
    targets: List<Target>,
    startUs: Long,
    endUs: Long,
): List<SectionAmbientCue> {
    val result = mutableListOf<SectionAmbientCue>()
    val durationUs = 1_320_000L
    val guardMarginBeforeUs = 320_000L
    val guardMarginAfterUs = 320_000L
    val densePaddingUs = 650_000L
    for (index in 1 until song.sections.size) {
        val boundaryUs = song.sections[index].startUs
        if (boundaryUs <= startUs + 400_000L || boundaryUs >= endUs - 400_000L) continue
        val cueStartUs = (boundaryUs - 140_000L).coerceAtLeast(startUs)
        val cueEndUs = cueStartUs + durationUs
        if (cueEndUs > endUs - 200_000L) continue
        val checkStartUs = cueStartUs - guardMarginBeforeUs
        val checkEndUs = cueEndUs + guardMarginAfterUs
        val overlapsWindow = targets.any { target ->
            val noteEndUs = maxOf(target.onsetUs, target.endUs)
            noteEndUs >= checkStartUs && target.onsetUs <= checkEndUs
        }
        if (overlapsWindow) continue
        val nearbyCount = targets.count { target ->
            target.onsetUs in (cueStartUs - densePaddingUs)..(cueEndUs + densePaddingUs)
        }
        if (nearbyCount > 1) continue
        result.add(
            SectionAmbientCue(
                sectionIndex = index,
                startUs = cueStartUs,
                durationUs = durationUs,
            ),
        )
    }
    return result
}

internal fun ambientIntensityAt(cues: List<SectionAmbientCue>, positionUs: Long): Float {
    var peak = 0f
    for (cue in cues) {
        val ageUs = positionUs - cue.startUs
        if (ageUs in 0L..cue.durationUs) {
            val phase = (ageUs.toFloat() / cue.durationUs.toFloat()).coerceIn(0f, 1f)
            val bell = sin(PI * phase).toFloat().coerceAtLeast(0f).pow(1.35f)
            if (bell > peak) peak = bell
        }
    }
    return peak.coerceIn(0f, 1f)
}

internal fun computeAccuracyPercent(judgments: List<Judgment>): Float? {
    if (judgments.isEmpty()) return null
    var scored = 0.0
    var settled = 0
    for (judgment in judgments) {
        when (judgment) {
            Judgment.PERFECT -> { scored += 1.0; settled++ }
            Judgment.GOOD -> { scored += 0.7; settled++ }
            Judgment.PARTIAL -> { scored += 0.35; settled++ }
            Judgment.MISSED -> { settled++ }
            Judgment.WAITING, Judgment.HOLDING -> Unit
        }
    }
    if (settled == 0) return null
    return ((scored / judgments.size) * 100.0).toFloat().coerceIn(0f, 100f)
}

internal fun formatAccuracy(accuracy: Float): String {
    val tenths = kotlin.math.round(accuracy * 10f).toInt()
    return if (tenths % 10 == 0) "${tenths / 10}%" else "${tenths / 10}.${tenths % 10}%"
}

/**
 * Fixed-width tabular numeral renderer where ONLY changed digit slots roll vertically in place.
 * Semantics expose the full formatted string atomically so TalkBack and tests remain clean.
 */
@Composable
internal fun RollingTabularDigits(
    valueText: String,
    fontSize: TextUnit,
    color: Color,
    motion: Boolean,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight = FontWeight.Normal,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    durationMillis: Int = 115,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.End,
) {
    val style = remember(fontSize, fontWeight, letterSpacing) {
        TextStyle(
            fontSize = fontSize,
            fontWeight = fontWeight,
            letterSpacing = letterSpacing,
            fontFeatureSettings = "tnum",
        )
    }
    Row(
        modifier = modifier.clearAndSetSemantics {
            this.text = AnnotatedString(valueText)
            this.contentDescription = valueText
        },
        horizontalArrangement = horizontalArrangement,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val len = valueText.length
        for (i in 0 until len) {
            val ch = valueText[i]
            val slotKey = len - 1 - i // Key from least-significant side so digit growth doesn't churn existing slots.
            Box(contentAlignment = Alignment.Center) {
                AnimatedContent(
                    targetState = ch,
                    transitionSpec = {
                        if (!motion || !targetState.isDigit() || !initialState.isDigit()) {
                            fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                        } else {
                            (slideInVertically(tween(durationMillis, easing = AppNavEasing)) { h -> h / 2 } +
                                fadeIn(tween(durationMillis, easing = AppNavEasing))) togetherWith
                                (slideOutVertically(tween(durationMillis, easing = AppNavEasing)) { h -> -h / 2 } +
                                    fadeOut(tween((durationMillis * 0.75f).toInt(), easing = AppNavEasing)))
                        }.using(SizeTransform(clip = true))
                    },
                    label = "digit-slot-$slotKey",
                ) { digit ->
                    Text(
                        text = digit.toString(),
                        color = color,
                        style = style,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}
