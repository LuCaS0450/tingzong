package cn.musicpilgrimage.app.ui.room

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import cn.musicpilgrimage.app.MusicTripState
import cn.musicpilgrimage.app.MusicTripViewModel
import cn.musicpilgrimage.app.data.MusicTrip
import cn.musicpilgrimage.app.data.MusicTripCity
import cn.musicpilgrimage.app.data.MusicTripStop
import cn.musicpilgrimage.app.data.Place
import cn.musicpilgrimage.app.data.canonicalRegionName
import cn.musicpilgrimage.app.data.locationLabel
import cn.musicpilgrimage.app.ui.AppBottomSheet

@Composable internal fun TripPanel(
    title:String,
    busy:Boolean,
    onClose:()->Unit,
    animated:Boolean=true,
    content:@Composable ColumnScope.()->Unit
) {
    val dismiss={if(!busy)onClose()}
    AppBottomSheet(
        visible=true,
        busy=busy,
        onDismissRequest=dismiss,
        allowExpandDetent=animated,
    ) { _, _ ->
        Column(
            Modifier.fillMaxWidth().padding(start=20.dp,end=20.dp,bottom=18.dp,top=2.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp)
        ) {
            Text(title,fontSize=22.sp,color=RoomColors.Ink)
            content()
        }
    }
}

@Composable internal fun TripError(error:String?) {
    if(error!=null)Text(error,fontSize=13.sp,color=MaterialTheme.colorScheme.error)
}

@Composable internal fun TripDateOptions(
    trip:MusicTrip,
    unavailable:Set<Int>,
    busy:Boolean,
    modifier:Modifier=Modifier,
    onSelect:(Int)->Unit
) {
    LazyColumn(modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        items(trip.days) { index ->
            val day=index+1
            SwissOutlinedButton(
                {onSelect(day)},
                Modifier.fillMaxWidth().testTag("trip-date-$day"),
                enabled=!busy&&day !in unavailable
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Text(trip.start.plusDays(index.toLong()).toString())
                    Text(if(day in unavailable)"第 ${day} 天 · 已安排" else "第 ${day} 天",fontSize=12.sp)
                }
            }
        }
    }
}

internal fun List<MusicTripCity>.destinationFields()=flatMap{listOf(it.name,it.country)}
internal fun List<String>.tripDestinations()=chunked(2).map{MusicTripCity(it[0],it[1])}

private val MusicTripCity.destinationPickerLabel:String
    get()=when {
        region!=null->label
        isCountry->"$label · 国家或地区"
        else->"$name · $country"
    }

internal fun tripDestinationOptions(catalogCities:List<MusicTripCity>):List<MusicTripCity> {
    val countries=catalogCities.map{canonicalRegionName(it.country)}.distinct().map{MusicTripCity.country(it)}
    return (countries+catalogCities).distinctBy{it.pickerKey}
}

internal fun searchTripDestinations(options:List<MusicTripCity>,query:String,catalogCities:List<MusicTripCity> = options):List<MusicTripCity> {
    val term=canonicalRegionName(query.trim())
    if(term.isEmpty())return emptyList()
    val matchedRegions=catalogCities.filter{it.region!=null&&it.name.contains(term,true)}.mapTo(mutableSetOf()){it.pickerKey}
    return options.filter {
        it.label.contains(term,true)||it.country.contains(term,true)||it.pickerKey in matchedRegions
    }.sortedBy{!it.label.equals(term,true)}
}

@Composable internal fun TripDestinationFields(
    name:String,
    onName:(String)->Unit,
    catalogCities:List<MusicTripCity>,
    selected:List<MusicTripCity>,
    onSelection:(List<MusicTripCity>)->Unit,
    busy:Boolean,
    modifier:Modifier=Modifier
) {
    var query by rememberSaveable{mutableStateOf("")}
    var manual by rememberSaveable{mutableStateOf(false)}
    var countryOnly by rememberSaveable{mutableStateOf(false)}
    var cityName by rememberSaveable{mutableStateOf("")}
    var countryName by rememberSaveable{mutableStateOf("")}
    val options=remember(catalogCities){tripDestinationOptions(catalogCities)}
    val term=query.trim()
    val matches=remember(options,term,catalogCities){searchTripDestinations(options,term,catalogCities)}
    val selectedKeys=selected.mapTo(mutableSetOf()){it.pickerKey}
    val visibleSelection=selected.distinctBy{it.pickerKey}
    val listState=rememberLazyListState()
    val manualItemIndex=2+(if(term.isNotEmpty()) matches.size.coerceAtLeast(1) else 0)
    LaunchedEffect(manual) {
        if(manual)listState.animateScrollToItem(manualItemIndex)
    }
    LazyColumn(modifier.fillMaxWidth(),state=listState,verticalArrangement=Arrangement.spacedBy(8.dp)) {
        item {
            OutlinedTextField(name,{onName(it.take(60))},label={Text("行程名称")},singleLine=true,
                enabled=!busy,modifier=Modifier.fillMaxWidth().testTag("trip-name-input"))
        }
        item { TripSearch(query,{query=it},"搜索城市、国家或地区","trip-destination-search",busy) }
        if(term.isNotEmpty()) {
            if(matches.isEmpty()) item { Text("没有找到匹配的目的地，可以手动填写。",color=RoomColors.Muted,fontSize=13.sp) }
            items(matches,key={"result-${it.key}"}) { destination ->
                val checked=destination.pickerKey in selectedKeys
                val enabled=!busy&&(checked||selected.size<MusicTrip.MAX_DESTINATIONS)
                Row(
                    Modifier.fillMaxWidth().toggleable(checked,enabled=enabled,role=Role.Checkbox) {
                        onSelection(if(it) selected+destination else selected.filterNot{row->row.pickerKey==destination.pickerKey})
                    },
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    Checkbox(checked,onCheckedChange=null,enabled=enabled,modifier=Modifier.size(48.dp))
                    Text(destination.destinationPickerLabel,
                        fontSize=14.sp,color=RoomColors.Ink)
                }
            }
        }
        item(key="manual-entry") {
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                SwissTextButton({manual=!manual},enabled=!busy&&(manual||selected.size<MusicTrip.MAX_DESTINATIONS)) {
                    Text(if(manual)"收起手动填写" else "手动填写目的地")
                }
                if(manual) {
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        FilterChip(!countryOnly,{countryOnly=false},{Text("城市")},enabled=!busy)
                        FilterChip(countryOnly,{countryOnly=true},{Text("国家或地区")},enabled=!busy)
                    }
                    if(!countryOnly) OutlinedTextField(cityName,{cityName=it.take(60)},label={Text("城市名称")},
                        singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(countryName,{countryName=it.take(60)},label={Text("国家或地区名称")},
                        singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
                    val country=canonicalRegionName(countryName.trim())
                    val destination=MusicTripCity(cityName.trim(),country).let {
                        if(countryOnly||it.region!=null)MusicTripCity.country(it.region?:country) else it
                    }
                    SwissOutlinedButton({
                        onSelection(selected+destination)
                        manual=false
                        query=""
                        cityName=""
                        countryName=""
                    },enabled=!busy&&selected.size<MusicTrip.MAX_DESTINATIONS&&country.isNotEmpty()&&
                        (countryOnly||destination.region!=null||cityName.isNotBlank()&&!destination.isCountry)&&destination.pickerKey !in selectedKeys) {
                        Text("加入目的地")
                    }
                }
            }
        }
        if(selected.isNotEmpty()) {
            item { Text("已选目的地 · ${visibleSelection.size}",fontSize=13.sp,color=RoomColors.Muted) }
            items(visibleSelection,key={"selected-${it.pickerKey}"}) { destination ->
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text(if(destination.isCountry||destination.region!=null)destination.label else "${destination.label} · ${destination.country}",
                        Modifier.weight(1f),fontSize=14.sp,color=RoomColors.Ink)
                    SwissTextButton({onSelection(selected.filterNot{it.pickerKey==destination.pickerKey})},enabled=!busy) {Text("移除")}
                }
            }
        }
    }
}

@Composable private fun TripSearch(
    query:String,onQuery:(String)->Unit,label:String,tag:String,busy:Boolean
) {
    val keyboard=LocalSoftwareKeyboardController.current
    OutlinedTextField(query,{onQuery(it.take(100))},label={Text(label)},singleLine=true,
        leadingIcon={Icon(Icons.Outlined.Search,contentDescription=null)},enabled=!busy,
        keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),
        keyboardActions=KeyboardActions(onSearch={keyboard?.hide()}),
        modifier=Modifier.fillMaxWidth().testTag(tag))
}

@Composable internal fun TripDayEditor(
    trip:MusicTrip,day:Int,places:List<Place>,state:MusicTripState,owner:String,vm:MusicTripViewModel,
    onMove:(MusicTripStop)->Unit,onRemove:(MusicTripStop)->Unit,onCustom:()->Unit,onClose:()->Unit
) {
    var query by rememberSaveable{mutableStateOf("")}
    val term=query.trim()
    val context=LocalContext.current
    val titles=remember(places,context){places.associate{it.id to PlaceEditorialCopy.name(context,it)}}
    val matches=remember(places,titles,term) {
        if(term.isEmpty()) emptyList()
        else places.filter{it.name.contains(term,true)||titles[it.id].orEmpty().contains(term,true)||
            it.city.contains(term,true)||it.country.contains(term,true)}
            .sortedBy{!it.name.equals(term,true)&&!titles[it.id].equals(term,true)}
    }
    val stops=trip.stops.filter{it.day==day}
    TripPanel("第 ${day} 天 · ${trip.start.plusDays(day-1L)}",state.busy,onClose) {
        TripSearch(query,{query=it},"搜索听踪地点","trip-day-search",state.busy)
        LazyColumn(Modifier.weight(1f,fill=false).fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if(term.isNotEmpty()) {
                if(matches.isEmpty()) item { Text("没有找到匹配的地点，也可以手动添加活动。",fontSize=13.sp,color=RoomColors.Muted) }
                items(matches,key={it.id}) { place ->
                    val added=stops.any{it.placeId==place.id}
                    SwissOutlinedButton(
                        {vm.add(owner,trip.id,place,trip.start.plusDays(day-1L)){query=""}},
                        Modifier.fillMaxWidth().testTag("trip-day-place-${place.id}"),
                        enabled=!state.busy&&!added
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(titles[place.id]?:place.name)
                            Text(place.locationLabel()+if(added)" · 已安排" else "",fontSize=12.sp)
                        }
                    }
                }
            } else {
                if(stops.isEmpty()) item { Text("这一天还没有活动。",fontSize=14.sp,color=RoomColors.Muted) }
                itemsIndexed(stops,key={_,stop->stop.id}) { index,stop ->
                    Column(Modifier.fillMaxWidth().padding(vertical=4.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text("${index+1}. ${titles[stop.placeId]?:stop.name}",fontSize=16.sp,color=RoomColors.Ink)
                        Text(stop.note.ifBlank{trip.cities.find{it.name==stop.city&&it.country==stop.country}?.label?:stop.city},
                            fontSize=13.sp,color=RoomColors.Muted)
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            SwissTextButton({vm.move(owner,trip.id,stop.id,day,index-1)},enabled=!state.busy&&index>0){Text("上移")}
                            SwissTextButton({vm.move(owner,trip.id,stop.id,day,index+1)},enabled=!state.busy&&index<stops.lastIndex){Text("下移")}
                            SwissTextButton({onMove(stop)},enabled=!state.busy&&trip.days>1){Text("换一天")}
                            SwissTextButton({onRemove(stop)},enabled=!state.busy){Text("移除")}
                        }
                        HorizontalDivider(color=RoomColors.Line)
                    }
                }
            }
        }
        TripError(state.error)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            SwissTextButton(onCustom,enabled=!state.busy){Text("手动添加活动")}
            SwissTextButton(onClose,enabled=!state.busy){Text("完成")}
        }
    }
}

@Composable internal fun TripCustomDialog(
    trip:MusicTrip,day:Int,state:MusicTripState,owner:String,vm:MusicTripViewModel,onClose:()->Unit
) {
    var name by rememberSaveable{mutableStateOf("")}
    var note by rememberSaveable{mutableStateOf("")}
    var city by rememberSaveable{mutableStateOf(trip.cities.first().key)}
    var choosingDestination by remember{mutableStateOf(false)}
    val selected=trip.cities.find{it.key==city}
    TripPanel("手动添加活动 · 第 ${day} 天",state.busy,onClose) {
        LazyColumn(Modifier.weight(1f,fill=false).fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            item {
                OutlinedTextField(name,{name=it.take(100)},label={Text("活动名称")},singleLine=true,
                    enabled=!state.busy,modifier=Modifier.fillMaxWidth().testTag("trip-custom-name"))
            }
            item {
                Box {
                    SwissOutlinedButton({choosingDestination=true},Modifier.fillMaxWidth(),enabled=!state.busy) {
                        Text("目的地 · ${selected?.label?:"请选择"}")
                    }
                    DropdownMenu(expanded=choosingDestination,onDismissRequest={choosingDestination=false}) {
                        trip.cities.distinctBy{it.pickerKey}.forEach { destination ->
                            DropdownMenuItem(text={Text(destination.label)},onClick={
                                city=destination.key
                                choosingDestination=false
                            })
                        }
                    }
                }
            }
            item {
                OutlinedTextField(note,{note=it.take(300)},label={Text("位置或备注（选填）")},
                    enabled=!state.busy,modifier=Modifier.fillMaxWidth())
            }
        }
        TripError(state.error)
        SwissButton({selected?.let{vm.custom(owner,trip.id,name,it,day,note,onClose)}},Modifier.fillMaxWidth(),
            enabled=!state.busy&&name.isNotBlank()&&selected!=null){Text("添加活动")}
        SwissTextButton(onClose,Modifier.align(Alignment.End),enabled=!state.busy){Text("取消")}
    }
}
