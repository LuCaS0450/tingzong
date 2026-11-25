package cn.musicpilgrimage.app.ui.room

import android.app.DatePickerDialog
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
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import cn.musicpilgrimage.app.MusicTripState
import cn.musicpilgrimage.app.MusicTripViewModel
import cn.musicpilgrimage.app.data.*
import cn.musicpilgrimage.app.design.AppArt
import cn.musicpilgrimage.app.ui.AppBottomSheet
import cn.musicpilgrimage.app.ui.AppConfirmation
import cn.musicpilgrimage.app.ui.LocalPhysicalMotion
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable internal fun RoomTripList(state:MusicTripState,owner:String,cities:List<MusicTripCity>,vm:MusicTripViewModel,onOpen:(String)->Unit) {
    var create by rememberSaveable{mutableStateOf(false)}
    var deleteId by rememberSaveable{mutableStateOf<String?>(null)}
    var revealedId by remember{mutableStateOf<String?>(null)}
    val deleting=state.trips.find{it.id==deleteId}
    LazyColumn(
        Modifier.fillMaxSize().testTag("room-trips"),
        contentPadding = PaddingValues(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        "出发的日子",
                        fontSize = 25.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.3).sp,
                        color = RoomColors.Ink,
                    )
                    if (state.trips.isNotEmpty()) {
                        Text(
                            "${state.trips.size} 个行程",
                            fontSize = 13.sp,
                            color = RoomColors.Muted,
                        )
                    }
                }
                SwissButton(
                    onClick = { vm.dismissError(); create = true; revealedId = null },
                    modifier = Modifier.heightIn(min = 40.dp),
                    enabled = !state.busy,
                    shape = TripPrimaryButtonShape,
                    colors = ButtonDefaults.buttonColors(containerColor = RoomColors.Red, contentColor = RoomColors.Paper),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp, pressedElevation = 0.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("新建行程", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        if(state.busy)item{LinearProgressIndicator(Modifier.fillMaxWidth().clip(TripPillShape),color=RoomColors.Navy)}
        if(state.error!=null&&deleting==null)item{TripError(state.error)}
        if(state.trips.isEmpty()&&!state.busy)item{
            Box(
                Modifier.fillMaxWidth()
                    .clip(TripCardShape)
                    .background(tripBoardFace)
                    .border(0.8.dp, RoomColors.Line.copy(alpha = 0.78f), TripCardShape)
                    .padding(horizontal = 18.dp, vertical = 20.dp),
            ) {
                Text(
                    "还没有行程。先选出行日期，再把喜欢的地点安排到每天。",
                    fontSize = 14.sp,
                    lineHeight = 23.sp,
                    color = RoomColors.Muted,
                )
            }
        }
        items(state.trips,key={it.id}){trip->
            TripSwipeRow(trip,revealedId==trip.id,!state.busy,{revealed->
                if(revealed)revealedId=trip.id else if(revealedId==trip.id)revealedId=null
            },{revealedId=null;onOpen(trip.id)},{vm.dismissError();deleteId=trip.id})
        }
    }
    if(create)RoomTripCreateDialog(cities,state.busy,state.error,{create=false}){name,start,end,selected->
        vm.create(owner,name,start,end,selected){id->create=false;onOpen(id)}
    }
    if(deleting!=null) {
        val close={deleteId=null;revealedId=null;vm.dismissError()}
        AppConfirmation(
            title="删除行程？",
            message="「${deleting.displayName}」及每天的全部安排都会被删除。此操作无法撤销。",
            confirmLabel=if(state.busy)"正在删除…" else "删除行程",
            onConfirm={vm.deleteTrip(owner,deleting.id,close)},
            onDismiss=close,busy=state.busy,error=state.error,destructive=true,
        )
    }
}

private enum class TripRowPosition { Closed, Delete }

internal val TripCardShape = RoundedCornerShape(16.dp)
internal val TripStopCardShape = RoundedCornerShape(14.dp)
internal val TripPrimaryButtonShape = RoundedCornerShape(12.dp)
internal val TripControlShape = RoundedCornerShape(12.dp)
internal val TripDaysBadgeShape = RoundedCornerShape(8.dp)
internal val TripPillShape = RoundedCornerShape(99.dp)

internal val tripBoardFace = Brush.verticalGradient(
    listOf(lerp(RoomColors.Paper, Color.White, .88f), lerp(RoomColors.Paper, AppArt.Sand, .12f))
)

internal val tripCardBorder = Brush.verticalGradient(
    listOf(Color.White.copy(alpha = .94f), RoomColors.Line.copy(alpha = .85f))
)

@Composable internal fun tripPrimaryButtonColors() = ButtonDefaults.buttonColors(
    containerColor = RoomColors.Red,
    contentColor = RoomColors.Paper,
)

@Composable internal fun tripPrimaryButtonElevation() = ButtonDefaults.buttonElevation(
    defaultElevation = 2.dp,
    pressedElevation = 0.dp,
    disabledElevation = 0.dp,
)

@Composable internal fun tripTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = lerp(RoomColors.Paper, Color.White, .76f),
    unfocusedContainerColor = lerp(RoomColors.Paper, Color.White, .62f),
    disabledContainerColor = lerp(RoomColors.Paper, AppArt.Sand, .28f),
    focusedBorderColor = RoomColors.Red.copy(alpha = .78f),
    unfocusedBorderColor = RoomColors.Line.copy(alpha = .9f),
    focusedLabelColor = RoomColors.Red,
    unfocusedLabelColor = RoomColors.Muted,
    cursorColor = RoomColors.Red,
)

@Composable private fun TripSwipeRow(
    trip:MusicTrip,revealed:Boolean,enabled:Boolean,onReveal:(Boolean)->Unit,onOpen:()->Unit,onDelete:()->Unit
) {
    val actionWidth=64.dp
    val motion = LocalPhysicalMotion.current
    val actionOffset=with(LocalDensity.current){actionWidth.toPx()}
    val swipe=remember(actionOffset) {
        AnchoredDraggableState(TripRowPosition.Closed,DraggableAnchors {
            TripRowPosition.Closed at 0f
            TripRowPosition.Delete at -actionOffset
        })
    }
    val scope=rememberCoroutineScope()
    val reportReveal by rememberUpdatedState(onReveal)
    val actionExposed=swipe.settledValue==TripRowPosition.Delete
    val destinations = remember(trip.cities) {
        trip.cities.distinctBy { it.pickerKey }.joinToString(" · ") { it.label }
    }
    LaunchedEffect(swipe) {
        snapshotFlow{swipe.settledValue}.collect{reportReveal(it==TripRowPosition.Delete)}
    }
    LaunchedEffect(revealed) {
        if (!revealed && actionExposed) {
            if (motion) swipe.animateTo(TripRowPosition.Closed) else swipe.snapTo(TripRowPosition.Closed)
        }
    }
    Box(Modifier.fillMaxWidth().anchoredDraggable(
        swipe,reverseDirection=false,orientation=Orientation.Horizontal,enabled=enabled
    )) {
        Box(
            Modifier.matchParentSize()
                .graphicsLayer {
                    val offsetPx = swipe.offset.takeUnless(Float::isNaN) ?: 0f
                    alpha = ((-offsetPx) / 8.dp.toPx()).coerceIn(0f, 1f)
                }
                .clip(TripCardShape)
                .background(RoomColors.Red),
            contentAlignment=Alignment.CenterEnd,
        ) {
            if(actionExposed)IconButton(onDelete,Modifier.width(actionWidth).testTag("trip-delete-${trip.id}"),enabled=enabled) {
                Icon(Icons.Outlined.DeleteOutline,"删除${trip.displayName}",tint=RoomColors.Paper)
            } else Box(Modifier.width(actionWidth),contentAlignment=Alignment.Center) {
                Icon(Icons.Outlined.DeleteOutline,null,tint=RoomColors.Paper)
            }
        }
        Box(
            Modifier.fillMaxWidth().swipeCardOffset { swipe.requireOffset() }
                .shadow(
                    elevation = 4.dp,
                    shape = TripCardShape,
                    ambientColor = RoomColors.Ink.copy(alpha = 0.08f),
                    spotColor = RoomColors.Ink.copy(alpha = 0.14f),
                    clip = false,
                )
                .clip(TripCardShape)
                .background(tripBoardFace)
                .border(0.8.dp, tripCardBorder, TripCardShape)
                .testTag("trip-row-${trip.id}")
                .clickable(enabled=enabled,role=Role.Button) {
                    if (!actionExposed) onOpen()
                    else scope.launch {
                        if (motion) swipe.animateTo(TripRowPosition.Closed) else swipe.snapTo(TripRowPosition.Closed)
                    }
                }.semantics {
                    if(enabled)customActions=listOf(CustomAccessibilityAction("删除行程"){onDelete();true})
                },
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            trip.displayName,
                            Modifier.weight(1f),
                            fontSize = 19.sp,
                            lineHeight = 25.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = RoomColors.Ink,
                        )
                        Box(
                            Modifier
                                .clip(TripDaysBadgeShape)
                                .background(RoomColors.Red.copy(alpha = 0.09f))
                                .padding(horizontal = 9.dp, vertical = 4.dp),
                        ) {
                            Text(
                                "${trip.days} 天",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = RoomColors.Red,
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
                            fontSize = 13.sp,
                            letterSpacing = 0.2.sp,
                            color = RoomColors.Muted,
                        )
                    }
                }
                if (destinations.isNotBlank()) {
                    HorizontalDivider(thickness = 0.5.dp, color = RoomColors.Line.copy(alpha = 0.55f))
                    Row(
                        Modifier.fillMaxWidth(),
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
                            Modifier.weight(1f),
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            color = RoomColors.Ink.copy(alpha = 0.9f),
                        )
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = RoomColors.Muted.copy(alpha = 0.55f),
                        )
                    }
                }
            }
        }
    }
}

@Composable internal fun RoomAddToTripDialog(
    place:Place,state:MusicTripState,owner:String,cities:List<MusicTripCity>,vm:MusicTripViewModel,
    onAdded:(()->Unit)?=null,onClose:()->Unit
) {
    var creating by rememberSaveable(place.id){mutableStateOf(false)}
    var selected by rememberSaveable(place.id){mutableStateOf<String?>(null)}
    val today=LocalDate.now()
    val choices=state.trips.filter{it.acceptsNewPlacesOn(today)}
    val trip=choices.find{it.id==selected}
    val primaryColors=tripPrimaryButtonColors()
    if(creating) {
        RoomTripCreateDialog(cities,state.busy,state.error,{creating=false},initialCity=MusicTripCity(place.city,place.country),
            actionColors=primaryColors){name,start,end,rows->
            vm.create(owner,name,start,end,rows){id->selected=id;creating=false}
        }
        return
    }

    val compact=trip==null&&choices.isEmpty()
    val dateFormat=remember{DateTimeFormatter.ofPattern("M月d日")}
    AppBottomSheet(visible=true,busy=state.busy,onDismissRequest=onClose,allowExpandDetent=!compact) { _,_ ->
        Column(Modifier.fillMaxWidth().padding(start=20.dp,end=20.dp,bottom=20.dp,top=2.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                IconButton(
                    onClick={if(trip==null)onClose() else {selected=null;vm.dismissError()}},
                    modifier=Modifier.offset(x=(-10).dp),enabled=!state.busy,
                ) {
                    if(trip==null)Icon(Icons.Outlined.Close,"关闭加入行程",tint=RoomColors.Ink)
                    else Icon(Icons.AutoMirrored.Outlined.ArrowBack,"返回选择行程",tint=RoomColors.Ink)
                }
                Text(if(trip==null)"加入行程" else "选择日期",fontSize=21.sp,
                    fontWeight=FontWeight.SemiBold,color=RoomColors.Ink)
            }
            when {
                compact -> Box(
                    Modifier.fillMaxWidth()
                        .clip(TripControlShape)
                        .background(lerp(RoomColors.Paper, Color.White, 0.56f))
                        .border(0.8.dp, RoomColors.Line.copy(alpha = 0.72f), TripControlShape)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Text("新建行程后，为这个地点选择日期。",fontSize=14.sp,color=RoomColors.Muted)
                }
                trip==null -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Place, contentDescription = null, modifier = Modifier.size(15.dp), tint = RoomColors.Red.copy(alpha = 0.78f))
                        Text(place.regionalTitle(place.name),fontSize=14.sp,color=RoomColors.Muted)
                    }
                    LazyColumn(
                        Modifier.weight(1f,fill=false).fillMaxWidth().testTag("add-trip-choices"),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(choices,key={it.id}){row->
                            val format=if(row.start.year==row.end.year)dateFormat else DateTimeFormatter.ofPattern("yyyy年M月d日")
                            AddTripOption(row.displayName,"${row.start.format(format)} – ${row.end.format(format)}",
                                !state.busy,Modifier.testTag("trip-choice-${row.id}")) {
                                selected=row.id
                                vm.dismissError()
                            }
                        }
                    }
                }
                else -> {
                    Text(trip.displayName,fontSize=15.sp,fontWeight=FontWeight.Medium,color=RoomColors.Muted)
                    val existing=trip.stops.filter{it.placeId==place.id}.mapNotNull{it.day}.toSet()
                    LazyColumn(
                        Modifier.weight(1f,fill=false).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(trip.days) { index ->
                            val day=index+1
                            val date=trip.start.plusDays(index.toLong())
                            AddTripOption(
                                "第 ${day} 天 · ${date.format(dateFormat)}",
                                if(day in existing)"已安排" else null,
                                !state.busy&&day !in existing,Modifier.testTag("trip-date-$day"),
                            ) {
                                vm.add(owner,trip.id,place,date,requireActive=true,done=onAdded?:onClose)
                            }
                        }
                    }
                }
            }
            TripError(state.error)
            if(trip==null) {
                val createTrip={creating=true;vm.dismissError()}
                SwissButton(
                    onClick = createTrip,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    enabled = !state.busy,
                    shape = TripPrimaryButtonShape,
                    colors = primaryColors,
                    elevation = tripPrimaryButtonElevation(),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("新建行程", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable private fun AddTripOption(title:String,subtitle:String?,enabled:Boolean,modifier:Modifier,onClick:()->Unit) {
    Box(
        modifier.fillMaxWidth()
            .then(
                if (enabled) Modifier.shadow(
                    elevation = 2.dp,
                    shape = TripControlShape,
                    ambientColor = RoomColors.Ink.copy(alpha = 0.06f),
                    spotColor = RoomColors.Ink.copy(alpha = 0.10f),
                    clip = false,
                ) else Modifier
            )
            .clip(TripControlShape)
            .background(if (enabled) tripBoardFace else androidx.compose.ui.graphics.SolidColor(RoomColors.Paper))
            .border(
                0.8.dp,
                if (enabled) RoomColors.Line.copy(alpha = 0.82f) else RoomColors.Line.copy(alpha = 0.55f),
                TripControlShape,
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) RoomColors.Ink else RoomColors.Muted,
                )
                if (subtitle != null && enabled) {
                    Text(subtitle, fontSize = 13.sp, color = RoomColors.Muted)
                }
            }
            if (enabled) {
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(19.dp),
                    tint = RoomColors.Muted.copy(alpha = 0.65f),
                )
            } else if (subtitle != null) {
                Box(
                    Modifier.clip(TripDaysBadgeShape)
                        .background(RoomColors.Line.copy(alpha = 0.42f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(subtitle, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = RoomColors.Muted)
                }
            }
        }
    }
}

@Composable private fun TripSettingsActionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier.fillMaxWidth()
            .shadow(
                elevation = 2.dp,
                shape = TripControlShape,
                ambientColor = RoomColors.Ink.copy(alpha = 0.06f),
                spotColor = RoomColors.Ink.copy(alpha = 0.10f),
                clip = false,
            )
            .clip(TripControlShape)
            .background(tripBoardFace)
            .border(0.8.dp, tripCardBorder, TripControlShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 14.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(34.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(RoomColors.Red.copy(alpha = 0.09f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = RoomColors.Red)
            }
            Text(title, Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = RoomColors.Ink)
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
                tint = RoomColors.Muted.copy(alpha = 0.65f),
            )
        }
    }
}

private enum class TripSettingsPane { Menu, Dates, Details }

@Composable internal fun RoomTripDetail(
    trip:MusicTrip,state:MusicTripState,owner:String,vm:MusicTripViewModel,onPlace:(String)->Unit,
    onBack:()->Unit={},onDiscover:()->Unit={},catalog:Catalog=Catalog()
) {
    var settings by rememberSaveable(trip.id){mutableStateOf<TripSettingsPane?>(null)}
    var editingDay by rememberSaveable(trip.id){mutableIntStateOf(0)}
    var moving by rememberSaveable(trip.id){mutableStateOf<String?>(null)}
    var deleting by rememberSaveable(trip.id){mutableStateOf<String?>(null)}
    var custom by rememberSaveable(trip.id){mutableStateOf(false)}
    val scheduled=remember(trip.stops){trip.stops.filter{it.day!=null}}
    NativeTripArchive(
        trip=trip,owner=owner,scheduled=scheduled,catalog=catalog,busy=state.busy,error=state.error,
        onBack=onBack,onDiscover=onDiscover,onPlace=onPlace,
        onSettings={vm.dismissError();settings=TripSettingsPane.Menu},
        onEditDay={day->vm.dismissError();editingDay=day},
        onMoveDay={id->vm.dismissError();moving=id},
        onRemove={id->vm.dismissError();deleting=id},
        onReorder={id,day,index,done->vm.move(owner,trip.id,id,day,index,settled=done)},
    )
    if(settings==TripSettingsPane.Menu) {
        TripPanel("行程设置",state.busy,{settings=null},animated=false) {
            TripSettingsActionCard(
                title = "修改日期",
                icon = Icons.Outlined.CalendarToday,
                enabled = !state.busy,
            ) { vm.dismissError(); settings = TripSettingsPane.Dates }
            TripSettingsActionCard(
                title = "名称与目的地",
                icon = Icons.Outlined.Edit,
                enabled = !state.busy,
            ) { vm.dismissError(); settings = TripSettingsPane.Details }
            TripError(state.error)
        }
    }

    if(settings==TripSettingsPane.Dates)TripDateDialog(trip,{settings=null;vm.dismissError()},state.busy,state.error){from,to,beforeStart,beforeEnd->
        vm.dates(owner,trip.id,from,to,beforeStart,beforeEnd){settings=null}
    }
    if(settings==TripSettingsPane.Details) {
        val cities=remember(catalog){tripCatalogueCities(catalog)}
        TripDetailsDialog(trip,cities,state,owner,vm){settings=null;vm.dismissError()}
    }
    if(editingDay in 1..trip.days && moving==null && deleting==null && !custom) {
        val publicPlaces=remember(catalog){catalog.publicOnly().places}
        key(editingDay) {
            TripDayEditor(trip,editingDay,publicPlaces,state,owner,vm,
                onMove={vm.dismissError();moving=it.id},
                onRemove={vm.dismissError();deleting=it.id},
                onCustom={vm.dismissError();custom=true},
                onClose={vm.dismissError();editingDay=0})
        }
    }
    if(custom && editingDay in 1..trip.days)TripCustomDialog(trip,editingDay,state,owner,vm){custom=false;vm.dismissError()}

    val movingStop=scheduled.find{it.id==moving}
    if(movingStop!=null) {
        TripPanel("移到另一天",state.busy,{moving=null;vm.dismissError()}) {
            Text(movingStop.name,fontSize=15.sp,fontWeight=FontWeight.Medium,color=RoomColors.Muted)
            val unavailable=scheduled.filter{it.id==movingStop.id||movingStop.placeId!=null&&it.placeId==movingStop.placeId}
                .mapNotNull{it.day}.toSet()
            TripDateOptions(trip,unavailable,state.busy,Modifier.weight(1f,fill=false)){day->
                vm.move(owner,trip.id,movingStop.id,day,scheduled.count{it.day==day}){moving=null}
            }
            TripError(state.error)
            SwissTextButton(
                onClick = { moving = null; vm.dismissError() },
                modifier = Modifier.align(Alignment.End),
                enabled = !state.busy,
                shape = RoundedCornerShape(10.dp),
            ) { Text("取消", color = RoomColors.Muted) }
        }
    }
    if(deleting!=null)AppConfirmation(
        title="从行程移出这个活动？",
        message="地点收藏和到访认证会保留。",
        confirmLabel=if(state.busy)"正在移出…" else "移出",
        onConfirm={deleting?.let{vm.remove(owner,trip.id,it){deleting=null}}},
        onDismiss={deleting=null;vm.dismissError()},busy=state.busy,error=state.error,destructive=true,
    )
    LaunchedEffect(trip.days,movingStop) {
        if(editingDay !in 1..trip.days){editingDay=0;custom=false}
        if(moving!=null&&movingStop==null)moving=null
    }
}

@Composable private fun DateChoice(label:String,date:LocalDate,onChange:(LocalDate)->Unit) {
    val context=LocalContext.current
    Box(
        Modifier.fillMaxWidth()
            .shadow(
                elevation = 2.dp,
                shape = TripControlShape,
                ambientColor = RoomColors.Ink.copy(alpha = 0.06f),
                spotColor = RoomColors.Ink.copy(alpha = 0.10f),
                clip = false,
            )
            .clip(TripControlShape)
            .background(tripBoardFace)
            .border(0.8.dp, tripCardBorder, TripControlShape)
            .clickable(role = Role.Button) {
                DatePickerDialog(context,{_,y,m,d->onChange(LocalDate.of(y,m+1,d))},date.year,date.monthValue-1,date.dayOfMonth).show()
            }
            .padding(horizontal = 15.dp, vertical = 14.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Outlined.CalendarToday,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = RoomColors.Red.copy(alpha = 0.8f),
            )
            Text(label, Modifier.weight(1f), fontSize = 14.sp, color = RoomColors.Muted)
            Text(date.toString(), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = RoomColors.Ink)
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = RoomColors.Muted.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable private fun TripDateDialog(
    trip:MusicTrip,onClose:()->Unit,busy:Boolean,error:String?,onSave:(LocalDate,LocalDate,LocalDate,LocalDate)->Unit
) {
    val beforeStart by rememberSaveable{mutableStateOf(trip.start.toString())}
    val beforeEnd by rememberSaveable{mutableStateOf(trip.end.toString())}
    var from by rememberSaveable{mutableStateOf(beforeStart)}
    var to by rememberSaveable{mutableStateOf(beforeEnd)}
    var attempted by rememberSaveable{mutableStateOf(false)}
    val first=LocalDate.parse(from)
    val last=LocalDate.parse(to)
    val count=last.toEpochDay()-first.toEpochDay()+1
    val valid=count in 1..365
    val overflow=if(valid&&attempted)trip.stopsBeyond(count.toInt()) else emptyList()
    TripPanel("修改出行日期",busy,onClose) {
        DateChoice("出发",first){from=it.toString();attempted=false}
        DateChoice("结束",last){to=it.toString();attempted=false}
        Box(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(if (valid) RoomColors.Red.copy(alpha = 0.06f) else RoomColors.Red.copy(alpha = 0.09f))
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            Text(
                if (valid) "共 ${count} 天。原活动保留在对应的第几天。" else "结束日期不能早于出发日期，最长365天。",
                fontSize = 13.sp,
                color = if (valid) RoomColors.Muted else MaterialTheme.colorScheme.error,
            )
        }
        if(overflow.isNotEmpty()) {
            TripError("以下活动超出新日期，请先移到保留的天数内。")
            LazyColumn(Modifier.weight(1f,fill=false),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                items(overflow,key={it.id}){stop->Text("第 ${stop.day} 天 · ${stop.name}",fontSize=14.sp,color=RoomColors.Ink)}
            }
        } else TripError(error)
        SwissButton(
            onClick = {
                attempted = true
                if (trip.stopsBeyond(count.toInt()).isEmpty()) onSave(first, last, LocalDate.parse(beforeStart), LocalDate.parse(beforeEnd))
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            enabled = valid && !busy,
            shape = TripPrimaryButtonShape,
            colors = tripPrimaryButtonColors(),
            elevation = tripPrimaryButtonElevation(),
        ) { Text("保存", fontSize = 15.sp, fontWeight = FontWeight.Medium) }
        SwissTextButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.End),
            enabled = !busy,
            shape = RoundedCornerShape(10.dp),
        ) { Text("取消", color = RoomColors.Muted) }
    }
}

@Composable private fun RoomTripCreateDialog(
    cities:List<MusicTripCity>,busy:Boolean,error:String?,onClose:()->Unit,initialCity:MusicTripCity?=null,
    actionColors:ButtonColors=tripPrimaryButtonColors(),
    onCreate:(String,LocalDate,LocalDate,List<MusicTripCity>)->Unit
) {
    var step by rememberSaveable{mutableIntStateOf(0)}
    var start by rememberSaveable{mutableStateOf(LocalDate.now().toString())}
    var end by rememberSaveable{mutableStateOf(LocalDate.now().plusDays(2).toString())}
    var name by rememberSaveable{mutableStateOf("")}
    var selection by rememberSaveable{mutableStateOf(listOfNotNull(initialCity?.asPickerDestination()).destinationFields())}
    val selected=selection.tripDestinations()
    val from=LocalDate.parse(start)
    val to=LocalDate.parse(end)
    val count=to.toEpochDay()-from.toEpochDay()+1
    val valid=count in 1..365
    TripPanel(if(step==0)"选择出行日期" else "行程名称与目的地",busy,onClose) {
        if(step==0) {
            DateChoice("出发",from){start=it.toString()}
            DateChoice("结束",to){end=it.toString()}
            Box(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (valid) RoomColors.Red.copy(alpha = 0.06f) else RoomColors.Red.copy(alpha = 0.09f))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                Text(
                    if (valid) "共 ${count} 天" else "结束日期不能早于出发日期，最长365天。",
                    fontSize = 13.sp,
                    fontWeight = if (valid) FontWeight.Medium else FontWeight.Normal,
                    color = if (valid) RoomColors.Red else MaterialTheme.colorScheme.error,
                )
            }
        } else {
            TripDestinationFields(name,{name=it},cities,selected,{selection=it.destinationFields()},busy,Modifier.weight(1f,fill=false))
        }
        TripError(error)
        if(step==0) {
            SwissButton(
                onClick = { step = 1 },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                enabled = valid && !busy,
                shape = TripPrimaryButtonShape,
                colors = actionColors,
                elevation = tripPrimaryButtonElevation(),
            ) { Text("下一步", fontSize = 15.sp, fontWeight = FontWeight.Medium) }
        } else {
            SwissButton(
                onClick = { onCreate(name, from, to, selected) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("trip-create-save"),
                enabled = !busy && name.isNotBlank() && selected.isNotEmpty(),
                shape = TripPrimaryButtonShape,
                colors = actionColors,
                elevation = tripPrimaryButtonElevation(),
            ) { Text(if (busy) "正在保存…" else "创建行程", fontSize = 15.sp, fontWeight = FontWeight.Medium) }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = if (step == 1) Arrangement.SpaceBetween else Arrangement.End,
        ) {
            if(step==1) SwissTextButton(
                onClick = { step = 0 },
                enabled = !busy,
                shape = RoundedCornerShape(10.dp),
            ) { Text("返回日期", color = RoomColors.Ink) }
            SwissTextButton(
                onClick = onClose,
                enabled = !busy,
                shape = RoundedCornerShape(10.dp),
            ) { Text("取消", color = RoomColors.Muted) }
        }
    }
}

@Composable private fun TripDetailsDialog(
    trip:MusicTrip,cities:List<MusicTripCity>,state:MusicTripState,owner:String,vm:MusicTripViewModel,onClose:()->Unit
) {
    val beforeName by rememberSaveable{mutableStateOf(trip.name)}
    val beforeDisplayName = tripDisplayName(trip.id, beforeName)
    val beforeFields by rememberSaveable{mutableStateOf(trip.cities.destinationFields())}
    var name by rememberSaveable{mutableStateOf(beforeDisplayName)}
    var selection by rememberSaveable{mutableStateOf(beforeFields)}
    val selected=selection.tripDestinations()
    TripPanel("名称与目的地",state.busy,onClose) {
        TripDestinationFields(name,{name=it},cities,selected,{selection=it.destinationFields()},state.busy,Modifier.weight(1f,fill=false))
        TripError(state.error)
        SwissButton(
            onClick = {
                val original = beforeFields.tripDestinations()
                val selectedKeys = selected.mapTo(mutableSetOf()) { it.pickerKey }
                val originalKeys = original.mapTo(mutableSetOf()) { it.pickerKey }
                // A reselected city still owns its previously saved activities.
                val retained = original.filter { it.pickerKey in selectedKeys }
                val added = selected.filterNot { it.pickerKey in originalKeys }
                vm.details(owner, trip.id, if (name.trim() == beforeDisplayName) beforeName else name,
                    retained + added, beforeName, original.map { it.key }, onClose)
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            enabled = !state.busy && name.isNotBlank() && selected.isNotEmpty(),
            shape = TripPrimaryButtonShape,
            colors = tripPrimaryButtonColors(),
            elevation = tripPrimaryButtonElevation(),
        ) { Text("保存", fontSize = 15.sp, fontWeight = FontWeight.Medium) }
        SwissTextButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.End),
            enabled = !state.busy,
            shape = RoundedCornerShape(10.dp),
        ) { Text("取消", color = RoomColors.Muted) }
    }
}
