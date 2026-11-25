package cn.musicpilgrimage.app.ui.room

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.musicpilgrimage.app.data.Catalog
import cn.musicpilgrimage.app.data.MusicTrip
import cn.musicpilgrimage.app.data.MusicTripStop
import cn.musicpilgrimage.app.ui.LocalPhysicalMotion

@Composable
internal fun NativeTripArchive(
    trip: MusicTrip,
    owner: String,
    scheduled: List<MusicTripStop>,
    catalog: Catalog,
    busy: Boolean,
    error: String?,
    onBack: () -> Unit,
    onDiscover: () -> Unit,
    onPlace: (String) -> Unit,
    onSettings: () -> Unit,
    onEditDay: (Int) -> Unit,
    onMoveDay: (String) -> Unit,
    onRemove: (String) -> Unit,
    onReorder: (String, Int, Int, () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val list = rememberLazyListState()
    val motion = LocalPhysicalMotion.current
    var revealedStopId by remember(trip.id) { mutableStateOf<String?>(null) }
    val linkedPlaces = remember(catalog) { catalog.places.associateBy { it.id } }
    val destinations = remember(trip.cities) { trip.cities.distinctBy { it.pickerKey }.joinToString(" · ") { it.label } }
    val placementSpec = remember(motion) {
        if (motion) spring<IntOffset>(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ) else snap()
    }
    RoomWall(Modifier.fillMaxSize().testTag("native-trip-archive")) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val unit = maxWidth / 360
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
                Row(
                    Modifier.fillMaxWidth().height(56.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoomHeader("行程档案", onBack = onBack, onSpace = {}, modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = { revealedStopId = null; onSettings() },
                        modifier = Modifier.padding(end = 8.dp).testTag("trip-archive-settings"),
                        enabled = !busy,
                    ) {
                        Icon(
                            Icons.Outlined.Tune,
                            contentDescription = "行程设置",
                            tint = RoomColors.Ink,
                        )
                    }
                }
                RoomTripDragSurface(trip, owner, list, busy, onReorder, Modifier.testTag("room-trip-detail")) { drag ->
                    LaunchedEffect(drag.active) {
                        if (drag.active) revealedStopId = null
                    }
                    val activeStops = remember(trip, scheduled, drag.source, drag.previewTarget) {
                        if (drag.source == null) scheduled
                        else drag.previewTrip(trip).stops.filter { it.day != null }
                    }
                    val days = remember(activeStops) { activeStops.groupBy { it.day } }
                    LazyColumn(
                        Modifier.fillMaxSize().testTag("trip-archive-scroll"),
                        state = list,
                        contentPadding = PaddingValues(start = unit * 22, end = unit * 22, top = 16.dp, bottom = 44.dp),
                    ) {
                        item("itinerary") {
                            Column(
                                Modifier.padding(bottom = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        trip.displayName,
                                        Modifier.weight(1f).testTag("trip-archive-title"),
                                        color = RoomColors.Ink,
                                        fontSize = 27.sp,
                                        lineHeight = 34.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        letterSpacing = (-0.4).sp,
                                    )
                                    Box(
                                        Modifier
                                            .clip(TripDaysBadgeShape)
                                            .background(RoomColors.Red.copy(alpha = 0.09f))
                                            .padding(horizontal = 10.dp, vertical = 5.dp),
                                    ) {
                                        Text(
                                            "${trip.days} 天",
                                            color = RoomColors.Red,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                }
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Outlined.CalendarToday,
                                        contentDescription = null,
                                        modifier = Modifier.size(13.dp),
                                        tint = RoomColors.Muted.copy(alpha = 0.75f),
                                    )
                                    Text(
                                        "${trip.start} — ${trip.end}",
                                        color = RoomColors.Muted,
                                        fontSize = 13.sp,
                                        letterSpacing = 0.2.sp,
                                    )
                                }
                                if (destinations.isNotBlank()) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            Icons.Outlined.Place,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                            tint = RoomColors.Red.copy(alpha = 0.74f),
                                        )
                                        Text(
                                            destinations,
                                            color = RoomColors.Ink.copy(alpha = 0.86f),
                                            fontSize = 14.sp,
                                            lineHeight = 20.sp,
                                        )
                                    }
                                }
                            }
                        }
                        if (error != null || drag.notice != null) {
                            item("itinerary-error") {
                                Box(Modifier.padding(top = 8.dp)) {
                                    TripError(error ?: drag.notice)
                                }
                            }
                        }
                        for (day in 1..trip.days) {
                            val stops = days[day].orEmpty()
                            item("day:$day") {
                                Column(
                                    Modifier.fillMaxWidth()
                                        .animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = placementSpec)
                                        .padding(top = 24.dp, bottom = if (stops.isEmpty()) 6.dp else 6.dp),
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .heightIn(min = 40.dp)
                                            .clip(TripControlShape)
                                            .clickable(
                                                enabled = !busy,
                                                role = Role.Button,
                                                onClickLabel = "为第${day}天添加活动",
                                            ) {
                                                revealedStopId = null
                                                onEditDay(day)
                                            }
                                            .testTag("trip-day-$day")
                                            .padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            "DAY ${day.toString().padStart(2, '0')}",
                                            color = RoomColors.Red,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 12.sp,
                                            letterSpacing = 1.4.sp,
                                        )
                                        Icon(
                                            Icons.Outlined.Add,
                                            contentDescription = "为第${day}天添加活动",
                                            modifier = Modifier.size(20.dp),
                                            tint = RoomColors.Muted,
                                        )
                                    }
                                    if (stops.isEmpty()) {
                                        HorizontalDivider(
                                            Modifier.padding(top = 4.dp),
                                            thickness = 0.5.dp,
                                            color = RoomColors.Line.copy(alpha = 0.6f),
                                        )
                                    }
                                    TripDropGuide(drag, "day:$day", after = true)
                                }
                            }
                            itemsIndexed(stops, key = { _, stop -> stop.id }) { position, stop ->
                                DisposableEffect(stop.id) {
                                    onDispose { drag.cards.remove(stop.id); drag.handles.remove(stop.id) }
                                }
                                val place = linkedPlaces[stop.placeId]
                                val display = remember(stop, place, context) {
                                    if (place == null) stop else stop.copy(name = PlaceEditorialCopy.name(context, place))
                                }
                                val lifted = drag.source?.id == stop.id
                                TripDropGuide(drag, stop.id, after = false)
                                Box(
                                    Modifier.fillMaxWidth()
                                        .animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = placementSpec)
                                        .padding(vertical = 6.dp)
                                        .onGloballyPositioned {
                                            val bounds = it.tripDragBounds()
                                            drag.cards[stop.id] = bounds
                                            drag.handles[stop.id] = bounds
                                        },
                                ) {
                                    if (lifted) {
                                        val strokeColor = RoomColors.Red.copy(alpha = 0.36f)
                                        Box(
                                            Modifier.matchParentSize()
                                                .clip(TripStopCardShape)
                                                .background(RoomColors.Red.copy(alpha = 0.045f))
                                                .drawBehind {
                                                    val strokePx = 1.2.dp.toPx()
                                                    val radiusPx = 14.dp.toPx()
                                                    drawRoundRect(
                                                        color = strokeColor,
                                                        topLeft = Offset(strokePx / 2f, strokePx / 2f),
                                                        size = Size(size.width - strokePx, size.height - strokePx),
                                                        cornerRadius = CornerRadius(radiusPx, radiusPx),
                                                        style = Stroke(
                                                            width = strokePx,
                                                            pathEffect = PathEffect.dashPathEffect(
                                                                floatArrayOf(6.dp.toPx(), 5.dp.toPx()),
                                                                0f,
                                                            ),
                                                        ),
                                                    )
                                                }
                                                .testTag("trip-drop-placeholder"),
                                        )
                                    }
                                    RoomTripStopCard(
                                        stop = display,
                                        modifier = Modifier.fillMaxWidth()
                                            .graphicsLayer { alpha = if (lifted) 0f else 1f }
                                            .then(if (lifted) Modifier.clearAndSetSemantics {} else Modifier)
                                            .testTag("trip-stop-${stop.id}"),
                                        enabled = !busy && !lifted,
                                        canUp = position > 0,
                                        canDown = position < stops.lastIndex,
                                        onPlace = { revealedStopId = null; stop.placeId?.let(onPlace) },
                                        onUp = { onReorder(stop.id, day, position - 1) {} },
                                        onDown = { onReorder(stop.id, day, position + 1) {} },
                                        onMoveDay = { revealedStopId = null; onMoveDay(stop.id) },
                                        onRemove = { revealedStopId = null; onRemove(stop.id) },
                                        handleModifier = Modifier,
                                        placeEnabled = !lifted,
                                        revealed = revealedStopId == stop.id,
                                        onReveal = { revealed ->
                                            if (revealed) revealedStopId = stop.id
                                            else if (revealedStopId == stop.id) revealedStopId = null
                                        },
                                    )
                                }
                                TripDropGuide(drag, stop.id, after = true)
                            }
                        }
                        item("discover") {
                            Box(
                                Modifier.fillMaxWidth()
                                    .animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = placementSpec)
                                    .padding(top = 28.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                SwissTextButton(
                                    onClick = { revealedStopId = null; onDiscover() },
                                    shape = TripControlShape,
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                                ) {
                                    Icon(
                                        Icons.Outlined.Add,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = RoomColors.Muted,
                                    )
                                    Spacer(Modifier.width(5.dp))
                                    Text("继续发现地点", fontSize = 14.sp, color = RoomColors.Muted)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
