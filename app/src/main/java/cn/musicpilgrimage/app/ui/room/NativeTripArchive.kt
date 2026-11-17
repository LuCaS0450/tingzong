package cn.musicpilgrimage.app.ui.room

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
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
                    SwissTextButton(onSettings, Modifier.padding(end = 12.dp).testTag("trip-archive-settings"), enabled = !busy) {
                        Text("行程设置", color = RoomColors.Ink)
                    }
                }
                HorizontalDivider(color = RoomColors.Line)
                RoomTripDragSurface(trip, owner, list, busy, onReorder, Modifier.testTag("room-trip-detail")) { drag ->
                    LazyColumn(
                        Modifier.fillMaxSize().testTag("trip-archive-scroll"), state = list,
                        contentPadding = PaddingValues(start = unit * 22, end = unit * 22, top = 20.dp, bottom = 32.dp),
                    ) {
                        item("itinerary") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                SwissEyebrow("JOURNEY / MUSIC & PLACES")
                                Text(trip.displayName, Modifier.testTag("trip-archive-title"), color = RoomColors.Ink,
                                    fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("${trip.start} — ${trip.end}", color = RoomColors.Muted, fontSize = 13.sp)
                                    Text("${trip.days} ${if (trip.days == 1) "DAY" else "DAYS"}", color = RoomColors.Muted, fontSize = 12.sp)
                                }
                                if (destinations.isNotBlank()) Text(destinations, color = RoomColors.Ink, fontSize = 14.sp)
                            }
                        }
                        if (error != null || drag.notice != null) item("itinerary-error") { TripError(error ?: drag.notice) }
                        for (day in 1..trip.days) {
                            val stops = days[day].orEmpty()
                            item("day:$day") {
                                Column(Modifier.fillMaxWidth().padding(top = 22.dp).border(1.dp, RoomColors.Line)
                                    .clickable(enabled = !busy, role = Role.Button, onClick = { onEditDay(day) })
                                    .testTag("trip-day-$day").padding(horizontal = 12.dp, vertical = 11.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(
                                            "DAY ${day.toString().padStart(2, '0')} / ${trip.start.plusDays(day - 1L).format(dates)}",
                                            color = RoomColors.Ink, fontWeight = FontWeight.Medium, fontSize = 14.sp,
                                        )
                                        Text("编辑当天", color = RoomColors.Muted, fontSize = 12.sp)
                                    }
                                    if (stops.isEmpty()) Text("添加当天活动", Modifier.padding(top = 7.dp), color = RoomColors.Muted, fontSize = 13.sp)
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
                                    Modifier.fillMaxWidth().drawWithContent {
                                        drawContent()
                                        val border = 1.dp.toPx()
                                        val inset = border / 2
                                        drawLine(RoomColors.Line, Offset(inset, 0f), Offset(inset, size.height), border)
                                        drawLine(RoomColors.Line, Offset(size.width - inset, 0f), Offset(size.width - inset, size.height), border)
                                        drawLine(RoomColors.Line, Offset(0f, size.height - inset), Offset(size.width, size.height - inset), border)
                                    }
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
                            SwissOutlinedButton(onDiscover, Modifier.fillMaxWidth().padding(top = 22.dp)) {
                                Text("继续发现地点", Modifier.weight(1f))
                                Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
