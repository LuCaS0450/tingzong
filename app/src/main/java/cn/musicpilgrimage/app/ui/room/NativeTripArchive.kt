package cn.musicpilgrimage.app.ui.room

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.musicpilgrimage.app.data.Catalog
import cn.musicpilgrimage.app.data.MusicTrip
import cn.musicpilgrimage.app.data.MusicTripStop
import java.time.format.DateTimeFormatter

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
    val dates = remember { DateTimeFormatter.ofPattern("MM-dd") }
    val linkedPlaces = remember(catalog) { catalog.places.associateBy { it.id } }
    val days = remember(scheduled) { scheduled.groupBy { it.day } }
    val destinations = remember(trip.cities) { trip.cities.distinctBy { it.pickerKey }.joinToString(" · ") { it.label } }
    RoomWall(Modifier.fillMaxSize().testTag("native-trip-archive")) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val unit = maxWidth / 360
            Column(Modifier.fillMaxSize()) {
                Spacer(Modifier.height(unit * 32))
                Row(Modifier.fillMaxWidth().height(unit * 56), verticalAlignment = Alignment.CenterVertically) {
                    RoomHeader("行程档案", onBack = onBack, onSpace = {}, modifier = Modifier.weight(1f))
                    SwissOutlinedButton(
                        onClick = onSettings,
                        modifier = Modifier.padding(end = 14.dp).heightIn(min = 36.dp).testTag("trip-archive-settings"),
                        enabled = !busy,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = lerp(RoomColors.Paper, Color.White, 0.78f),
                            contentColor = RoomColors.Ink,
                        ),
                        border = BorderStroke(0.8.dp, RoomColors.Line.copy(alpha = 0.88f)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Tune,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = RoomColors.Ink.copy(alpha = 0.85f),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text("行程设置", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = RoomColors.Ink)
                    }
                }
                HorizontalDivider(thickness = 0.5.dp, color = RoomColors.Line.copy(alpha = 0.65f))
                RoomTripDragSurface(trip, owner, list, busy, onReorder, Modifier.testTag("room-trip-detail")) { drag ->
                    LazyColumn(
                        Modifier.fillMaxSize().testTag("trip-archive-scroll"), state = list,
                        contentPadding = PaddingValues(start = unit * 20, end = unit * 20, top = 18.dp, bottom = 32.dp),
                    ) {
                        item("itinerary") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                SwissEyebrow("JOURNEY / MUSIC & PLACES")
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        trip.displayName,
                                        Modifier.weight(1f).testTag("trip-archive-title"),
                                        color = RoomColors.Ink,
                                        fontSize = 26.sp,
                                        lineHeight = 33.sp,
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
                                        tint = RoomColors.Muted.copy(alpha = 0.78f),
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
                                            modifier = Modifier.size(15.dp),
                                            tint = RoomColors.Red.copy(alpha = 0.78f),
                                        )
                                        Text(
                                            destinations,
                                            color = RoomColors.Ink.copy(alpha = 0.9f),
                                            fontSize = 14.sp,
                                            lineHeight = 20.sp,
                                        )
                                    }
                                }
                            }
                        }
                        if (error != null || drag.notice != null) {
                            item("itinerary-error") {
                                Box(Modifier.padding(top = 12.dp)) {
                                    TripError(error ?: drag.notice)
                                }
                            }
                        }
                        for (day in 1..trip.days) {
                            val stops = days[day].orEmpty()
                            item("day:$day") {
                                Column(
                                    Modifier.fillMaxWidth()
                                        .padding(top = if (day == 1) 22.dp else 20.dp)
                                        .clip(TripStopCardShape)
                                        .clickable(enabled = !busy, role = Role.Button, onClick = { onEditDay(day) })
                                        .testTag("trip-day-$day"),
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Box(
                                                Modifier
                                                    .clip(RoundedCornerShape(7.dp))
                                                    .background(RoomColors.Red.copy(alpha = 0.09f))
                                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                                            ) {
                                                Text(
                                                    "DAY ${day.toString().padStart(2, '0')}",
                                                    color = RoomColors.Red,
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontSize = 11.sp,
                                                    letterSpacing = 0.8.sp,
                                                )
                                            }
                                            Text(
                                                trip.start.plusDays(day - 1L).format(dates),
                                                color = RoomColors.Ink,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 14.sp,
                                            )
                                            if (stops.isNotEmpty()) {
                                                Text(
                                                    "· ${stops.size} 项",
                                                    color = RoomColors.Muted,
                                                    fontSize = 12.sp,
                                                )
                                            }
                                        }
                                        Row(
                                            Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(lerp(RoomColors.Paper, Color.White, 0.72f))
                                                .border(0.7.dp, RoomColors.Line.copy(alpha = 0.82f), RoundedCornerShape(8.dp))
                                                .padding(horizontal = 10.dp, vertical = 5.dp),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(
                                                Icons.Outlined.Edit,
                                                contentDescription = null,
                                                modifier = Modifier.size(13.dp),
                                                tint = RoomColors.Muted,
                                            )
                                            Text(
                                                "编辑当天",
                                                color = RoomColors.Ink.copy(alpha = 0.86f),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium,
                                            )
                                        }
                                    }
                                    if (stops.isEmpty()) {
                                        Spacer(Modifier.height(8.dp))
                                        Row(
                                            Modifier.fillMaxWidth()
                                                .clip(TripStopCardShape)
                                                .background(lerp(RoomColors.Paper, Color.White, 0.56f))
                                                .border(0.8.dp, RoomColors.Line.copy(alpha = 0.76f), TripStopCardShape)
                                                .padding(horizontal = 16.dp, vertical = 14.dp),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Box(
                                                Modifier.size(28.dp)
                                                    .clip(TripDaysBadgeShape)
                                                    .background(RoomColors.Red.copy(alpha = 0.08f)),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Icon(
                                                    Icons.Outlined.Add,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp),
                                                    tint = RoomColors.Red,
                                                )
                                            }
                                            Text("添加当天活动", color = RoomColors.Muted, fontSize = 14.sp)
                                        }
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
                                RoomTripStopCard(
                                    display,
                                    Modifier.fillMaxWidth()
                                        .padding(top = 10.dp)
                                        .onGloballyPositioned { drag.cards[stop.id] = it.tripDragBounds() }
                                        .graphicsLayer { alpha = if (lifted) 0f else 1f }
                                        .then(if (lifted) Modifier.clearAndSetSemantics {} else Modifier)
                                        .testTag("trip-stop-${stop.id}"),
                                    enabled = !busy && !lifted, canUp = position > 0, canDown = position < stops.lastIndex,
                                    onPlace = { stop.placeId?.let(onPlace) },
                                    onUp = { onReorder(stop.id, day, position - 1) {} },
                                    onDown = { onReorder(stop.id, day, position + 1) {} },
                                    onMoveDay = { onMoveDay(stop.id) }, onRemove = { onRemove(stop.id) },
                                    handleModifier = Modifier.onGloballyPositioned { drag.handles[stop.id] = it.tripDragBounds() },
                                    placeEnabled = !lifted,
                                )
                                TripDropGuide(drag, stop.id, after = true)
                            }
                        }
                        item("discover") {
                            SwissButton(
                                onClick = onDiscover,
                                modifier = Modifier.fillMaxWidth().padding(top = 24.dp).heightIn(min = 48.dp),
                                shape = TripPrimaryButtonShape,
                                colors = tripPrimaryButtonColors(),
                                elevation = tripPrimaryButtonElevation(),
                                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 13.dp),
                            ) {
                                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("继续发现地点", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        }
    }
}
