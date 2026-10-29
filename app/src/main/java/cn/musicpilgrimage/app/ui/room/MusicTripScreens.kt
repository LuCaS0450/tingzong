package cn.musicpilgrimage.app.ui.room

import android.app.DatePickerDialog
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.musicpilgrimage.app.MusicTripState
import cn.musicpilgrimage.app.MusicTripViewModel
import cn.musicpilgrimage.app.data.*
import cn.musicpilgrimage.app.ui.LocalPhysicalMotion
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable internal fun RoomTripList(state:MusicTripState,owner:String,cities:List<MusicTripCity>,vm:MusicTripViewModel,onOpen:(String)->Unit) {
    var create by rememberSaveable{mutableStateOf(false)}
    val librarySync by vm.sync.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().testTag("room-trips"),contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item{Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("出发的日子",Modifier.weight(1f),fontSize=21.sp,color=RoomColors.Ink);SwissTextButton({vm.dismissError();create=true},enabled=!state.busy){Text("新建行程")}}}
        item{RoomLibrarySyncStatus(librarySync.takeIf{it.owner==owner}?:PersonalLibrarySyncState(),vm::syncLibrary)}
        if(state.busy)item{LinearProgressIndicator(Modifier.fillMaxWidth(),color=RoomColors.Navy)}
        if(state.error!=null)item{Text(state.error,fontSize=13.sp,color=MaterialTheme.colorScheme.error)}
        if(state.trips.isEmpty()&&!state.busy)item{Text("还没有行程。先选出行日期，再把喜欢的地点安排到每天。",fontSize=14.sp,lineHeight=23.sp,color=RoomColors.Muted)}
        items(state.trips,key={it.id}){trip->RoomPaper(Modifier.fillMaxWidth().clickable(role=Role.Button){onOpen(trip.id)},woodEdge=true){Column(Modifier.padding(17.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(trip.name,fontSize=19.sp,fontWeight=FontWeight.Medium,color=RoomColors.Ink)
            Text("${trip.start} — ${trip.end} · ${trip.days}天",fontSize=13.sp,color=RoomColors.Muted)
            Text(trip.cities.joinToString(" · "){it.name},fontSize=13.sp,color=RoomColors.Ink)
        }}}
    }
    if(create)RoomTripCreateDialog(cities,state.busy,state.error,{if(!state.busy)create=false}){name,start,end,selected->vm.create(owner,name,start,end,selected){id->create=false;onOpen(id)}}
}

@Composable internal fun RoomAddToTripDialog(place:Place,state:MusicTripState,owner:String,cities:List<MusicTripCity>,vm:MusicTripViewModel,onClose:()->Unit,onView:(String)->Unit) {
    var creating by rememberSaveable(place.id){mutableStateOf(false)}
    var selected by rememberSaveable(place.id){mutableStateOf<String?>(null)}
    var day by rememberSaveable(place.id){mutableStateOf("1")}
    val choices=state.trips.filter{trip->trip.cities.any{it.key=="${place.country}::${place.city}"}}
    val trip=choices.find{it.id==selected}
    if(creating) {
        RoomTripCreateDialog(cities,state.busy,state.error,{if(!state.busy)creating=false},initialCity=MusicTripCity(place.city,place.country)){name,start,end,rows->
            vm.create(owner,name,start,end,rows){id->selected=id;creating=false;day="1"}
        }
        return
    }
    Dialog(onDismissRequest={if(!state.busy)onClose()}){RoomPaper {Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("加入行程",fontSize=22.sp,color=RoomColors.Ink)
        Text(place.name,fontSize=14.sp,color=RoomColors.Muted)
        if(trip==null) {
            Text("包含${place.city}的行程",fontSize=13.sp,color=RoomColors.Muted)
            LazyColumn(Modifier.heightIn(max=260.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                items(choices,key={it.id}){row->SwissOutlinedButton({selected=row.id;day="1";vm.dismissError()},Modifier.fillMaxWidth().testTag("trip-choice-${row.id}"),enabled=!state.busy){Column(Modifier.fillMaxWidth()){Text(row.name);Text("${row.start} · ${row.days}天",fontSize=12.sp)}}}
                if(choices.isEmpty())item{Text("还没有包含这座城市的行程。",fontSize=13.sp,color=RoomColors.Muted)}
            }
            SwissTextButton({creating=true;vm.dismissError()},enabled=!state.busy){Text("新建行程")}
        } else {
            Text(trip.name,fontSize=17.sp,color=RoomColors.Ink)
            val existing=trip.stops.filter{it.placeId==place.id}
            if(existing.isNotEmpty()) {
                Text("已加入：${existing.joinToString("、"){it.day?.let{d->"第${d}天"}?:"待安排"}}。可以查看原安排，或加入另一天。",fontSize=13.sp,lineHeight=21.sp,color=RoomColors.Muted)
                SwissTextButton({onClose();onView(trip.id)}){Text("查看原安排")}
            }
            TripDayInput(day,{day=it},trip.days)
            SwissButton({day.toIntOrNull()?.let{vm.add(owner,trip.id,place,it,onClose)}},Modifier.fillMaxWidth().testTag("trip-add-confirm"),enabled=!state.busy&&day.toIntOrNull() in 1..trip.days){Text("加入第${day.ifBlank{"…"}}天")}
            SwissTextButton({selected=null;vm.dismissError()},enabled=!state.busy){Text("选择其他行程")}
        }
        if(state.error!=null)Text(state.error,fontSize=13.sp,color=MaterialTheme.colorScheme.error)
        SwissTextButton(onClose,Modifier.align(Alignment.End),enabled=!state.busy){Text("取消")}
    }}}
}

@Composable internal fun RoomTripDetail(trip:MusicTrip,state:MusicTripState,owner:String,vm:MusicTripViewModel,onPlace:(String)->Unit) { RoomTripDetailWeb(trip,state,owner,vm,onPlace) }

@Composable internal fun RoomTripDetailWeb(trip:MusicTrip,state:MusicTripState,owner:String,vm:MusicTripViewModel,onPlace:(String)->Unit,onBack:()->Unit={},onDiscover:()->Unit={},catalog:Catalog=Catalog()) {
    var editingDates by rememberSaveable(trip.id){mutableStateOf(false)}
    var moving by rememberSaveable(trip.id){mutableStateOf<String?>(null)}
    var movingDay by rememberSaveable(trip.id){mutableStateOf("1")}
    var deleting by rememberSaveable(trip.id){mutableStateOf<String?>(null)}
    var custom by rememberSaveable(trip.id){mutableStateOf(false)}
    var details by rememberSaveable(trip.id){mutableStateOf(false)}
    var editingMenu by rememberSaveable(trip.id){mutableStateOf(false)}
    val context=androidx.compose.ui.platform.LocalContext.current
    val media=remember(context){SwissDetailMedia(context)}
    val stopData=trip.stops.map{stop->org.json.JSONObject().put("id",stop.id).put("place",stop.placeId?:"custom:${stop.id}").put("day",stop.day?.minus(1)?:trip.days)}
    val places=trip.stops.distinctBy{it.placeId?:it.id}.map{stop->
        val real=catalog.places.find{it.id==stop.placeId}
        if(real!=null)media.place(real) else org.json.JSONObject().put("id",stop.placeId?:"custom:${stop.id}").put("name",stop.name).put("city",stop.city).put("work",stop.note).put("image","")
    }
    val row=org.json.JSONObject().put("id",trip.id).put("name",trip.name).put("start",trip.start.toString()).put("end",trip.end.toString())
        .put("days",trip.days).put("stops",org.json.JSONArray(stopData))
    val data=swissDetailData("trip",trip.id).put("trip",row).put("places",org.json.JSONArray(places)).put("busy",state.busy)
    SwissWebDetail(data){action,args->when(action) {
        "back"->onBack();"world-root"->onDiscover();"trip-edit"->if(!state.busy)editingMenu=true
        "place"->trip.stops.firstOrNull{it.placeId==args.optString("id")}?.placeId?.let(onPlace)
        "move-stop"->if(!state.busy)trip.stops.getOrNull(args.optInt("index"))?.let{moving=it.id;movingDay=(it.day?:1).toString();vm.dismissError()}
        "remove-stop"->if(!state.busy)trip.stops.getOrNull(args.optInt("index"))?.let{deleting=it.id}
        "trip-reorder"->if(!state.busy){
            val id=args.optString("id");val day=if(args.isNull("day"))null else args.optInt("day")
            if(trip.stops.any{it.id==id}&&(day==null||day in 1..trip.days))vm.move(owner,trip.id,id,day,args.optInt("index").coerceAtLeast(0))
        }
    }}
    if(editingMenu)AlertDialog(onDismissRequest={editingMenu=false},title={Text("编辑行程")},text={Column {
        SwissTextButton({editingMenu=false;editingDates=true;vm.dismissError()}){Text("修改日期")}
        SwissTextButton({editingMenu=false;details=true;vm.dismissError()}){Text("名称与城市")}
        SwissTextButton({editingMenu=false;custom=true;vm.dismissError()}){Text("添加地点")}
        state.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    }},confirmButton={SwissTextButton({editingMenu=false}){Text("关闭")}})
    if(editingDates)TripDateDialog(trip.start,trip.end,{editingDates=false},state.busy,state.error){from,to,beforeStart,beforeEnd->vm.dates(owner,trip.id,from,to,beforeStart,beforeEnd){editingDates=false}}
    if(moving!=null)AlertDialog(onDismissRequest={if(!state.busy)moving=null},title={Text("移到哪一天？")},text={Column{TripDayInput(movingDay,{movingDay=it},trip.days);state.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}}},confirmButton={SwissTextButton({movingDay.toIntOrNull()?.let{day->vm.move(owner,trip.id,moving!!,day,trip.stops.count{it.day==day});moving=null}},enabled=!state.busy&&movingDay.toIntOrNull() in 1..trip.days){Text("确定")}},dismissButton={SwissTextButton({moving=null},enabled=!state.busy){Text("取消")}})
    if(deleting!=null)AlertDialog(onDismissRequest={deleting=null},title={Text("从行程移出这个地点？")},text={Text("地点收藏和到访认证会保留。")},confirmButton={SwissTextButton({vm.remove(owner,trip.id,deleting!!);deleting=null},enabled=!state.busy){Text("移出")}},dismissButton={SwissTextButton({deleting=null}){Text("取消")}})
    if(custom)TripCustomDialog(trip,state,owner,vm){custom=false}
    if(details)TripDetailsDialog(trip,state,owner,vm){details=false}
}

@Composable private fun TripDayInput(value:String,onChange:(String)->Unit,days:Int) {
    OutlinedTextField(value,{onChange(it.filter(Char::isDigit).take(3))},label={Text("放入第几天（1—${days}）")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth().testTag("trip-day-input"))
}

@Composable private fun DateChoice(label:String,date:LocalDate,onChange:(LocalDate)->Unit) {
    val context=LocalContext.current
    SwissOutlinedButton({DatePickerDialog(context,{_,y,m,d->onChange(LocalDate.of(y,m+1,d))},date.year,date.monthValue-1,date.dayOfMonth).show()},Modifier.fillMaxWidth()){Text("$label    $date")}
}

@Composable private fun TripDateDialog(start:LocalDate,end:LocalDate,onClose:()->Unit,busy:Boolean,error:String?,onSave:(LocalDate,LocalDate,LocalDate,LocalDate)->Unit) {
    val beforeStart by rememberSaveable{mutableStateOf(start.toString())};val beforeEnd by rememberSaveable{mutableStateOf(end.toString())}
    var from by rememberSaveable{mutableStateOf(start.toString())};var to by rememberSaveable{mutableStateOf(end.toString())}
    val first=LocalDate.parse(from);val last=LocalDate.parse(to);val valid=!last.isBefore(first)&&last.toEpochDay()-first.toEpochDay()<365
    AlertDialog(onDismissRequest={if(!busy)onClose()},title={Text("修改出行日期")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){DateChoice("出发",first){from=it.toString()};DateChoice("结束",last){to=it.toString()};Text("原来的第几天安排会保留。超出新天数的地点会移入待安排。",fontSize=13.sp);error?.let{Text(it,color=MaterialTheme.colorScheme.error)}}},confirmButton={SwissTextButton({onSave(first,last,LocalDate.parse(beforeStart),LocalDate.parse(beforeEnd))},enabled=valid&&!busy){Text("保存")}},dismissButton={SwissTextButton(onClose,enabled=!busy){Text("取消")}})
}

@Composable private fun RoomTripCreateDialog(cities:List<MusicTripCity>,busy:Boolean,error:String?,onClose:()->Unit,initialCity:MusicTripCity?=null,onCreate:(String,LocalDate,LocalDate,List<MusicTripCity>)->Unit) {
    var step by rememberSaveable{mutableIntStateOf(0)}
    var start by rememberSaveable{mutableStateOf(LocalDate.now().toString())};var end by rememberSaveable{mutableStateOf(LocalDate.now().plusDays(2).toString())}
    var name by rememberSaveable{mutableStateOf("")}
    var chosen by rememberSaveable{mutableStateOf(initialCity?.let{listOf(it.key)}?:emptyList())}
    val from=LocalDate.parse(start);val to=LocalDate.parse(end);val valid=!to.isBefore(from)&&to.toEpochDay()-from.toEpochDay()<365
    Dialog(onDismissRequest={if(!busy)onClose()},properties=DialogProperties(decorFitsSystemWindows=false)) {
      Box(Modifier.fillMaxWidth().imePadding().padding(vertical=16.dp)) {
       RoomPaper(Modifier.fillMaxWidth()) {Column(Modifier.heightIn(max=570.dp).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(if(step==0)"选择出行日期" else "给行程取个名字",fontSize=22.sp,color=RoomColors.Ink)
        if(step==0) {
            DateChoice("出发",from){start=it.toString()}
            DateChoice("结束",to){end=it.toString()}
            Text(if(valid)"共${to.toEpochDay()-from.toEpochDay()+1}天" else "结束日期应在出发之后，最长365天",fontSize=13.sp,color=RoomColors.Muted)
        } else {
            OutlinedTextField(name,{name=it.take(60)},label={Text("行程名称")},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth().testTag("trip-name-input"))
            Text("包含的城市",fontSize=15.sp,color=RoomColors.Ink)
            LazyColumn(Modifier.weight(1f,fill=false).fillMaxWidth().testTag("trip-city-list")) {
                items(cities,key={it.key}) {city->
                    Row(Modifier.fillMaxWidth().clickable(enabled=!busy){chosen=if(city.key in chosen)chosen-city.key else chosen+city.key},verticalAlignment=Alignment.CenterVertically) {
                        Checkbox(city.key in chosen,{chosen=if(it)chosen+city.key else chosen-city.key},enabled=!busy)
                        Text("${city.name} · ${city.country}",fontSize=14.sp)
                    }
                }
            }
        }
        if(error!=null)Text(error,fontSize=13.sp,color=MaterialTheme.colorScheme.error)
        HorizontalDivider(color=RoomColors.Line)
        if(step==0)SwissButton({step=1},Modifier.fillMaxWidth(),enabled=valid&&!busy){Text("下一步")}
        else SwissButton({onCreate(name,from,to,cities.filter{it.key in chosen})},Modifier.fillMaxWidth().testTag("trip-create-save"),enabled=!busy&&name.isNotBlank()&&chosen.isNotEmpty()){Text(if(busy)"正在保存…" else "创建行程")}
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            if(step==1)SwissTextButton({step=0},enabled=!busy){Text("返回日期")}
            SwissTextButton(onClose,enabled=!busy){Text("取消")}
        }
       }}
      }
    }
}

@Composable private fun TripCustomDialog(trip:MusicTrip,state:MusicTripState,owner:String,vm:MusicTripViewModel,onClose:()->Unit) {
    var name by rememberSaveable{mutableStateOf("")};var note by rememberSaveable{mutableStateOf("")};var city by rememberSaveable{mutableStateOf(trip.cities.first().key)};var day by rememberSaveable{mutableStateOf("1")}
    val selectedCity=trip.cities.find{it.key==city}
    Dialog(onDismissRequest={if(!state.busy)onClose()}){RoomPaper{Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("手动添加地点",fontSize=22.sp,color=RoomColors.Ink)
        OutlinedTextField(name,{name=it.take(100)},label={Text("地点名称")},singleLine=true,modifier=Modifier.fillMaxWidth())
        Row{trip.cities.forEach{row->FilterChip(city==row.key,{city=row.key},{Text(row.name, maxLines=1)})}}
        if(selectedCity==null)Text("行程城市已更新，请重新选择",fontSize=13.sp,color=RoomColors.Muted)
        TripDayInput(day,{day=it},trip.days)
        OutlinedTextField(note,{note=it.take(300)},label={Text("位置说明（选填）")},modifier=Modifier.fillMaxWidth())
        state.error?.let{Text(it,fontSize=13.sp,color=MaterialTheme.colorScheme.error)}
        SwissButton({selectedCity?.let{vm.custom(owner,trip.id,name,it,day.toInt(),note,onClose)}},Modifier.fillMaxWidth(),enabled=!state.busy&&name.isNotBlank()&&selectedCity!=null&&day.toIntOrNull() in 1..trip.days){Text("添加")}
        SwissTextButton(onClose,Modifier.align(Alignment.End),enabled=!state.busy){Text("取消")}
    }}}
}

@Composable private fun TripDetailsDialog(trip:MusicTrip,state:MusicTripState,owner:String,vm:MusicTripViewModel,onClose:()->Unit) {
    val beforeName by rememberSaveable{mutableStateOf(trip.name)}
    val beforeCityFields by rememberSaveable{mutableStateOf(trip.cities.flatMap{listOf(it.name,it.country)})}
    val beforeCities=beforeCityFields.chunked(2).map{MusicTripCity(it[0],it[1])}
    var name by rememberSaveable{mutableStateOf(trip.name)}
    var chosen by rememberSaveable{mutableStateOf(trip.cities.map{it.key})}
    var city by rememberSaveable{mutableStateOf("")};var country by rememberSaveable{mutableStateOf("")}
    var added by remember{mutableStateOf(emptyList<MusicTripCity>())}
    val rows=(beforeCities+added).distinctBy{it.key}
    Dialog(onDismissRequest={if(!state.busy)onClose()}){RoomPaper{LazyColumn(Modifier.heightIn(max=570.dp).padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item{Text("名称与城市",fontSize=22.sp,color=RoomColors.Ink)}
        item{OutlinedTextField(name,{name=it.take(60)},label={Text("行程名称")},singleLine=true)}
        items(rows,key={it.key}){row->Row(verticalAlignment=Alignment.CenterVertically){Checkbox(row.key in chosen,{chosen=if(it)chosen+row.key else chosen-row.key});Text("${row.name} · ${row.country}",fontSize=14.sp)}}
        item{OutlinedTextField(city,{city=it.take(60)},label={Text("添加城市")},singleLine=true)}
        item{OutlinedTextField(country,{country=it.take(60)},label={Text("国家或地区")},singleLine=true)}
        item{SwissTextButton({val row=MusicTripCity(city.trim(),country.trim());added=added+row;chosen=chosen+row.key;city="";country=""},enabled=city.isNotBlank()&&country.isNotBlank()){Text("加入城市")}}
        if(state.error!=null)item{Text(state.error,fontSize=13.sp,color=MaterialTheme.colorScheme.error)}
        item{SwissButton({vm.details(owner,trip.id,name,rows.filter{it.key in chosen},beforeName,beforeCities.map{it.key},onClose)},enabled=!state.busy&&name.isNotBlank()&&chosen.isNotEmpty()){Text("保存")}}
        item{SwissTextButton(onClose,enabled=!state.busy){Text("取消")}}
    }}}
}
