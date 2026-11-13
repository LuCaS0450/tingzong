package cn.musicpilgrimage.app.ui.room

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.musicpilgrimage.app.MusicTripState
import cn.musicpilgrimage.app.MusicTripViewModel
import cn.musicpilgrimage.app.data.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.roundToInt

@Composable internal fun RoomTripList(state:MusicTripState,owner:String,cities:List<MusicTripCity>,vm:MusicTripViewModel,onOpen:(String)->Unit) {
    var create by rememberSaveable{mutableStateOf(false)}
    var deleteId by rememberSaveable{mutableStateOf<String?>(null)}
    var revealedId by remember{mutableStateOf<String?>(null)}
    val deleting=state.trips.find{it.id==deleteId}
    val librarySync by vm.sync.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().testTag("room-trips"),contentPadding=PaddingValues(24.dp,12.dp,24.dp,24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item{Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("出发的日子",Modifier.weight(1f),fontSize=23.sp,fontWeight=FontWeight.Medium,color=RoomColors.Ink);SwissTextButton({vm.dismissError();create=true;revealedId=null},enabled=!state.busy){Text("新建行程")}}}
        item{RoomLibrarySyncStatus(librarySync.takeIf{it.owner==owner}?:PersonalLibrarySyncState(),vm::syncLibrary,
            caption="左滑行程可删除".takeIf{state.trips.isNotEmpty()})}
        if(state.busy)item{LinearProgressIndicator(Modifier.fillMaxWidth(),color=RoomColors.Navy)}
        if(state.error!=null&&deleting==null)item{TripError(state.error)}
        if(state.trips.isEmpty()&&!state.busy)item{Text("还没有行程。先选出行日期，再把喜欢的地点安排到每天。",fontSize=14.sp,lineHeight=23.sp,color=RoomColors.Muted)}
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
        TripPanel("删除行程？",state.busy,close,animated=false) {
            Text("「${deleting.name}」及每天的全部安排都会被删除。此操作无法撤销。",
                color=RoomColors.Ink,fontSize=15.sp,lineHeight=24.sp)
            TripError(state.error)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                SwissTextButton(close,enabled=!state.busy){Text("取消")}
                SwissButton({vm.deleteTrip(owner,deleting.id,close)},enabled=!state.busy,
                    colors=ButtonDefaults.buttonColors(containerColor=RoomColors.Red,contentColor=RoomColors.Paper)) {
                    Text(if(state.busy)"正在删除…" else "删除行程")
                }
            }
        }
    }
}

private enum class TripRowPosition { Closed, Delete }

@Composable private fun TripSwipeRow(
    trip:MusicTrip,revealed:Boolean,enabled:Boolean,onReveal:(Boolean)->Unit,onOpen:()->Unit,onDelete:()->Unit
) {
    val actionWidth=64.dp
    val rowPadding=16.dp
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
    LaunchedEffect(swipe) {
        snapshotFlow{swipe.settledValue}.collect{reportReveal(it==TripRowPosition.Delete)}
    }
    LaunchedEffect(revealed) {
        if(!revealed&&actionExposed)swipe.animateTo(TripRowPosition.Closed)
    }
    Box(Modifier.fillMaxWidth().clipToBounds().anchoredDraggable(
        swipe,reverseDirection=false,orientation=Orientation.Horizontal,enabled=enabled
    )) {
        Box(Modifier.matchParentSize().padding(bottom=rowPadding).background(RoomColors.Red),contentAlignment=Alignment.CenterEnd) {
            if(actionExposed)IconButton(onDelete,Modifier.width(actionWidth).testTag("trip-delete-${trip.id}"),enabled=enabled) {
                Icon(Icons.Outlined.DeleteOutline,"删除${trip.name}",tint=RoomColors.Paper)
            } else Box(Modifier.width(actionWidth),contentAlignment=Alignment.Center) {
                Icon(Icons.Outlined.DeleteOutline,null,tint=RoomColors.Paper)
            }
        }
        RoomPaper(Modifier.fillMaxWidth().offset{IntOffset(swipe.requireOffset().roundToInt(),0)}
            .testTag("trip-row-${trip.id}")
            .clickable(enabled=enabled,role=Role.Button) {
                if(actionExposed)scope.launch{swipe.animateTo(TripRowPosition.Closed)} else onOpen()
            }.semantics {
                if(enabled)customActions=listOf(CustomAccessibilityAction("删除行程"){onDelete();true})
            }) {
            Column(Modifier.fillMaxWidth().padding(vertical=rowPadding),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(trip.name,fontSize=19.sp,fontWeight=FontWeight.Medium,color=RoomColors.Ink)
                Text("${trip.start} — ${trip.end} · ${trip.days}天",fontSize=13.sp,color=RoomColors.Muted)
                Text(trip.cities.joinToString(" · "){it.label},fontSize=13.sp,color=RoomColors.Ink)
                HorizontalDivider(Modifier.padding(top=8.dp),color=RoomColors.Line)
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
    if(creating) {
        RoomTripCreateDialog(cities,state.busy,state.error,{creating=false},initialCity=MusicTripCity(place.city,place.country)){name,start,end,rows->
            vm.create(owner,name,start,end,rows){id->selected=id;creating=false}
        }
        return
    }

    TripPanel(if(trip==null)"加入行程" else "选择加入日期",state.busy,onClose) {
        Text(place.name,fontSize=15.sp,color=RoomColors.Muted)
        if(trip==null) {
            if(choices.isEmpty()) {
                Text("没有未结束的行程。",fontSize=14.sp,color=RoomColors.Muted)
            } else {
                LazyColumn(Modifier.weight(1f,fill=false),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    items(choices,key={it.id}){row->
                        SwissOutlinedButton({selected=row.id;vm.dismissError()},
                            Modifier.fillMaxWidth().testTag("trip-choice-${row.id}"),enabled=!state.busy) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(row.name)
                                Text("${row.start} — ${row.end}",fontSize=12.sp)
                            }
                        }
                    }
                }
            }
            SwissTextButton({creating=true;vm.dismissError()},enabled=!state.busy){Text("新建行程")}
        } else {
            Text(trip.name,fontSize=17.sp,color=RoomColors.Ink)
            val existing=trip.stops.filter{it.placeId==place.id}.mapNotNull{it.day}.toSet()
            TripDateOptions(trip,existing,state.busy,Modifier.weight(1f,fill=false)){day->
                vm.add(owner,trip.id,place,trip.start.plusDays(day-1L),requireActive=true,done=onAdded?:onClose)
            }
            SwissTextButton({selected=null;vm.dismissError()},enabled=!state.busy){Text("返回选择行程")}
        }
        TripError(state.error)
        SwissTextButton(onClose,Modifier.align(Alignment.End),enabled=!state.busy){Text("取消")}
    }
}

@Composable internal fun RoomTripDetail(trip:MusicTrip,state:MusicTripState,owner:String,vm:MusicTripViewModel,onPlace:(String)->Unit) {
    RoomTripDetailWeb(trip,state,owner,vm,onPlace)
}

@Composable internal fun RoomTripDetailWeb(
    trip:MusicTrip,state:MusicTripState,owner:String,vm:MusicTripViewModel,onPlace:(String)->Unit,
    onBack:()->Unit={},onDiscover:()->Unit={},catalog:Catalog=Catalog()
) {
    var editingDates by rememberSaveable(trip.id){mutableStateOf(false)}
    var editingDay by rememberSaveable(trip.id){mutableIntStateOf(0)}
    var moving by rememberSaveable(trip.id){mutableStateOf<String?>(null)}
    var deleting by rememberSaveable(trip.id){mutableStateOf<String?>(null)}
    var custom by rememberSaveable(trip.id){mutableStateOf(false)}
    var details by rememberSaveable(trip.id){mutableStateOf(false)}
    var editingMenu by rememberSaveable(trip.id){mutableStateOf(false)}
    val context=LocalContext.current
    val media=remember(context){SwissDetailMedia(context)}
    val scheduled=trip.stops.filter{it.day!=null}
    val stopData=scheduled.map{stop->
        JSONObject().put("id",stop.id).put("place",stop.placeId?:"custom:${stop.id}").put("day",stop.day!!-1)
    }
    val places=scheduled.distinctBy{it.placeId?:it.id}.map{stop->
        val real=catalog.places.find{it.id==stop.placeId}
        val destination=trip.cities.find{it.name==stop.city&&it.country==stop.country}
        if(real!=null)media.place(real) else JSONObject().put("id",stop.placeId?:"custom:${stop.id}")
            .put("name",stop.name).put("city",destination?.label?:stop.city).put("work",stop.note).put("image","")
    }
    val row=JSONObject().put("id",trip.id).put("name",trip.name).put("start",trip.start.toString()).put("end",trip.end.toString())
        .put("days",trip.days).put("destinations",JSONArray(trip.cities.map{it.label})).put("stops",JSONArray(stopData))
    val data=swissDetailData("trip",trip.id).put("trip",row).put("places",JSONArray(places)).put("busy",state.busy)
    SwissWebDetail(data){action,args->
        when(action) {
            "back"->onBack()
            "world-root"->onDiscover()
            "place"->scheduled.firstOrNull{it.placeId==args.optString("id")}?.placeId?.let(onPlace)
            "trip-edit"->if(!state.busy)editingMenu=true
            "trip-edit-day"->if(!state.busy) {
                val day=args.optInt("day")
                if(day in 1..trip.days){vm.dismissError();editingDay=day}
            }
            "move-stop"->if(!state.busy)scheduled.getOrNull(args.optInt("index",-1))?.let{vm.dismissError();moving=it.id}
            "remove-stop"->if(!state.busy)scheduled.getOrNull(args.optInt("index",-1))?.let{vm.dismissError();deleting=it.id}
            "trip-reorder"->if(!state.busy){
                val id=args.optString("id")
                val day=args.optInt("day")
                if(scheduled.any{it.id==id}&&day in 1..trip.days)vm.move(owner,trip.id,id,day,args.optInt("index").coerceAtLeast(0))
            }
        }
    }
    if(editingMenu)AlertDialog(onDismissRequest={editingMenu=false},title={Text("行程设置")},text={Column {
        SwissTextButton({editingMenu=false;editingDates=true;vm.dismissError()}){Text("修改日期")}
        SwissTextButton({editingMenu=false;details=true;vm.dismissError()}){Text("名称与目的地")}
        TripError(state.error)
    }},confirmButton={SwissTextButton({editingMenu=false}){Text("关闭")}})

    if(editingDates)TripDateDialog(trip,{editingDates=false;vm.dismissError()},state.busy,state.error){from,to,beforeStart,beforeEnd->
        vm.dates(owner,trip.id,from,to,beforeStart,beforeEnd){editingDates=false}
    }
    if(details) {
        val cities=remember(catalog){musicMapCities(catalog,null).map{MusicTripCity(it.name,it.country)}}
        TripDetailsDialog(trip,cities,state,owner,vm){details=false;vm.dismissError()}
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
            Text(movingStop.name,fontSize=15.sp,color=RoomColors.Ink)
            val unavailable=scheduled.filter{it.id==movingStop.id||movingStop.placeId!=null&&it.placeId==movingStop.placeId}
                .mapNotNull{it.day}.toSet()
            TripDateOptions(trip,unavailable,state.busy,Modifier.weight(1f,fill=false)){day->
                vm.move(owner,trip.id,movingStop.id,day,scheduled.count{it.day==day}){moving=null}
            }
            TripError(state.error)
            SwissTextButton({moving=null;vm.dismissError()},Modifier.align(Alignment.End),enabled=!state.busy){Text("取消")}
        }
    }
    if(deleting!=null)AlertDialog(
        onDismissRequest={if(!state.busy){deleting=null;vm.dismissError()}},
        title={Text("从行程移出这个活动？")},
        text={Column{Text("地点收藏和到访认证会保留。");TripError(state.error)}},
        confirmButton={SwissTextButton({deleting?.let{vm.remove(owner,trip.id,it){deleting=null}}},enabled=!state.busy){Text("移出")}},
        dismissButton={SwissTextButton({deleting=null;vm.dismissError()},enabled=!state.busy){Text("取消")}}
    )
    LaunchedEffect(trip.days,movingStop) {
        if(editingDay !in 1..trip.days){editingDay=0;custom=false}
        if(moving!=null&&movingStop==null)moving=null
    }
}

@Composable private fun DateChoice(label:String,date:LocalDate,onChange:(LocalDate)->Unit) {
    val context=LocalContext.current
    SwissOutlinedButton({
        DatePickerDialog(context,{_,y,m,d->onChange(LocalDate.of(y,m+1,d))},date.year,date.monthValue-1,date.dayOfMonth).show()
    },Modifier.fillMaxWidth()){Text("$label    $date")}
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
        Text(if(valid)"共${count}天。原活动保留在对应的第几天。" else "结束日期不能早于出发日期，最长365天。",
            fontSize=13.sp,color=RoomColors.Muted)
        if(overflow.isNotEmpty()) {
            TripError("以下活动超出新日期，请先移到保留的天数内。")
            LazyColumn(Modifier.weight(1f,fill=false),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                items(overflow,key={it.id}){stop->Text("第 ${stop.day} 天 · ${stop.name}",fontSize=14.sp,color=RoomColors.Ink)}
            }
        } else TripError(error)
        SwissButton({
            attempted=true
            if(trip.stopsBeyond(count.toInt()).isEmpty())onSave(first,last,LocalDate.parse(beforeStart),LocalDate.parse(beforeEnd))
        },Modifier.fillMaxWidth(),enabled=valid&&!busy){Text("保存")}
        SwissTextButton(onClose,Modifier.align(Alignment.End),enabled=!busy){Text("取消")}
    }
}

@Composable private fun RoomTripCreateDialog(
    cities:List<MusicTripCity>,busy:Boolean,error:String?,onClose:()->Unit,initialCity:MusicTripCity?=null,
    onCreate:(String,LocalDate,LocalDate,List<MusicTripCity>)->Unit
) {
    var step by rememberSaveable{mutableIntStateOf(0)}
    var start by rememberSaveable{mutableStateOf(LocalDate.now().toString())}
    var end by rememberSaveable{mutableStateOf(LocalDate.now().plusDays(2).toString())}
    var name by rememberSaveable{mutableStateOf("")}
    var selection by rememberSaveable{mutableStateOf(listOfNotNull(initialCity).destinationFields())}
    val selected=selection.tripDestinations()
    val from=LocalDate.parse(start)
    val to=LocalDate.parse(end)
    val count=to.toEpochDay()-from.toEpochDay()+1
    val valid=count in 1..365
    TripPanel(if(step==0)"选择出行日期" else "行程名称与目的地",busy,onClose) {
        if(step==0) {
            DateChoice("出发",from){start=it.toString()}
            DateChoice("结束",to){end=it.toString()}
            Text(if(valid)"共${count}天" else "结束日期不能早于出发日期，最长365天。",
                fontSize=13.sp,color=RoomColors.Muted)
        } else {
            TripDestinationFields(name,{name=it},cities,selected,{selection=it.destinationFields()},busy,Modifier.weight(1f,fill=false))
        }
        TripError(error)
        if(step==0)SwissButton({step=1},Modifier.fillMaxWidth(),enabled=valid&&!busy){Text("下一步")}
        else SwissButton({onCreate(name,from,to,selected)},Modifier.fillMaxWidth().testTag("trip-create-save"),
            enabled=!busy&&name.isNotBlank()&&selected.isNotEmpty()){Text(if(busy)"正在保存…" else "创建行程")}
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            if(step==1)SwissTextButton({step=0},enabled=!busy){Text("返回日期")}
            SwissTextButton(onClose,enabled=!busy){Text("取消")}
        }
    }
}

@Composable private fun TripDetailsDialog(
    trip:MusicTrip,cities:List<MusicTripCity>,state:MusicTripState,owner:String,vm:MusicTripViewModel,onClose:()->Unit
) {
    val beforeName by rememberSaveable{mutableStateOf(trip.name)}
    val beforeFields by rememberSaveable{mutableStateOf(trip.cities.destinationFields())}
    var name by rememberSaveable{mutableStateOf(beforeName)}
    var selection by rememberSaveable{mutableStateOf(beforeFields)}
    val selected=selection.tripDestinations()
    TripPanel("名称与目的地",state.busy,onClose) {
        TripDestinationFields(name,{name=it},cities,selected,{selection=it.destinationFields()},state.busy,Modifier.weight(1f,fill=false))
        TripError(state.error)
        SwissButton({vm.details(owner,trip.id,name,selected,beforeName,beforeFields.tripDestinations().map{it.key},onClose)},
            Modifier.fillMaxWidth(),enabled=!state.busy&&name.isNotBlank()&&selected.isNotEmpty()){Text("保存")}
        SwissTextButton(onClose,Modifier.align(Alignment.End),enabled=!state.busy){Text("取消")}
    }
}
