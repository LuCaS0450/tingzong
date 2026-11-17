package cn.musicpilgrimage.app.ui.room

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import kotlin.math.roundToInt

/** Move the complete surface while making room for actions inside the viewport. */
internal fun Modifier.swipeCardOffset(offset: () -> Float): Modifier = layout { measurable, constraints ->
    val revealed = (-offset()).roundToInt().coerceIn(0, constraints.maxWidth)
    val travel = minOf(revealed, 16.dp.roundToPx())
    val card = measurable.measure(constraints.offset(horizontal = -(revealed - travel)))
    layout(constraints.maxWidth, card.height) {
        card.place(-travel, 0)
    }
}
