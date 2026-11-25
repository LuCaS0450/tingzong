package cn.musicpilgrimage.app.ui.room

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.snapTo
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.musicpilgrimage.app.data.MusicTripStop
import cn.musicpilgrimage.app.data.geographicParts
import cn.musicpilgrimage.app.ui.LocalPhysicalMotion
import kotlinx.coroutines.launch

private enum class StopSwipePosition { Closed, Remove }

@Composable internal fun RoomTripStopCard(
    stop: MusicTripStop,
    modifier: Modifier,
    enabled: Boolean,
    canUp: Boolean,
    canDown: Boolean,
    onPlace: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onMoveDay: () -> Unit,
    onRemove: () -> Unit,
    handleModifier: Modifier,
    placeEnabled: Boolean = enabled,
    revealed: Boolean = false,
    onReveal: (Boolean) -> Unit = {},
    swipeEnabled: Boolean = enabled,
) {
    val actionWidth = 64.dp
    val motion = LocalPhysicalMotion.current
    val actionOffset = with(LocalDensity.current) { actionWidth.toPx() }
    val swipe = remember(actionOffset) {
        AnchoredDraggableState(
            StopSwipePosition.Closed,
            DraggableAnchors {
                StopSwipePosition.Closed at 0f
                StopSwipePosition.Remove at -actionOffset
            },
        )
    }
    val scope = rememberCoroutineScope()
    val reportReveal by rememberUpdatedState(onReveal)
    val actionExposed = swipe.settledValue == StopSwipePosition.Remove
    LaunchedEffect(swipe) {
        snapshotFlow { swipe.settledValue }.collect { reportReveal(it == StopSwipePosition.Remove) }
    }
    LaunchedEffect(revealed) {
        if (!revealed && actionExposed) {
            if (motion) swipe.animateTo(StopSwipePosition.Closed) else swipe.snapTo(StopSwipePosition.Closed)
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .then(
                if (swipeEnabled) Modifier.anchoredDraggable(
                    swipe,
                    reverseDirection = false,
                    orientation = Orientation.Horizontal,
                    enabled = enabled,
                ) else Modifier
            ),
    ) {
        if (swipeEnabled) {
            Box(
                Modifier.matchParentSize()
                    .graphicsLayer {
                        val offsetPx = swipe.offset.takeUnless(Float::isNaN) ?: 0f
                        alpha = ((-offsetPx) / 8.dp.toPx()).coerceIn(0f, 1f)
                    }
                    .clip(TripStopCardShape)
                    .background(RoomColors.Red),
                contentAlignment = Alignment.CenterEnd,
            ) {
                if (actionExposed) {
                    IconButton(
                        onClick = onRemove,
                        modifier = Modifier.width(actionWidth).testTag("trip-stop-remove-${stop.id}"),
                        enabled = enabled,
                    ) {
                        Icon(Icons.Outlined.DeleteOutline, "移出${stop.name}", tint = RoomColors.Paper)
                    }
                } else {
                    Box(Modifier.width(actionWidth), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.DeleteOutline, null, tint = RoomColors.Paper)
                    }
                }
            }
        }
        Box(
            Modifier.fillMaxWidth()
                .then(if (swipeEnabled) Modifier.swipeCardOffset { swipe.requireOffset() } else Modifier)
                .shadow(
                    elevation = 3.5.dp,
                    shape = TripStopCardShape,
                    ambientColor = RoomColors.Ink.copy(alpha = 0.08f),
                    spotColor = RoomColors.Ink.copy(alpha = 0.13f),
                    clip = false,
                )
                .clip(TripStopCardShape)
                .background(tripBoardFace)
                .border(0.8.dp, tripCardBorder, TripStopCardShape)
                .semantics {
                    if (enabled) {
                        val actions = buildList {
                            if (canUp) add(CustomAccessibilityAction("上移地点") { onUp(); true })
                            if (canDown) add(CustomAccessibilityAction("下移地点") { onDown(); true })
                            add(CustomAccessibilityAction("移到另一天") { onMoveDay(); true })
                            add(CustomAccessibilityAction("从行程移出") { onRemove(); true })
                        }
                        customActions = actions
                    }
                },
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 66.dp).padding(start = 16.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    Modifier.weight(1f)
                        .then(
                            if (actionExposed || (placeEnabled && stop.placeId != null)) {
                                Modifier.clickable(enabled = enabled, role = Role.Button) {
                                    if (actionExposed) {
                                        scope.launch {
                                            if (motion) swipe.animateTo(StopSwipePosition.Closed)
                                            else swipe.snapTo(StopSwipePosition.Closed)
                                        }
                                    } else if (stop.placeId != null) {
                                        onPlace()
                                    }
                                }
                            } else Modifier
                        )
                        .padding(vertical = 13.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        stop.name,
                        fontSize = 16.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = RoomColors.Ink,
                    )
                    val location = geographicParts(stop.city, stop.country).joinToString(" · ")
                    val subtitle = listOf(location, stop.note).filter(String::isNotBlank).joinToString(" · ")
                    if (subtitle.isNotBlank()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (stop.placeId != null) {
                                Icon(
                                    Icons.Outlined.Place,
                                    contentDescription = null,
                                    modifier = Modifier.size(13.dp),
                                    tint = RoomColors.Red.copy(alpha = 0.72f),
                                )
                            }
                            Text(subtitle, fontSize = 12.sp, lineHeight = 18.sp, color = RoomColors.Muted)
                        }
                    }
                }
                Box(
                    handleModifier
                        .size(44.dp)
                        .testTag("trip-drag-handle-${stop.id}")
                        .semantics(mergeDescendants = true) { contentDescription = "拖动地点" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.DragHandle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = RoomColors.Muted.copy(alpha = 0.42f),
                    )
                }
            }
        }
    }
}
