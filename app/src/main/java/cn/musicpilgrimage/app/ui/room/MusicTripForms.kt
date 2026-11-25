package cn.musicpilgrimage.app.ui.room

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.musicpilgrimage.app.MusicTripState
import cn.musicpilgrimage.app.MusicTripViewModel
import cn.musicpilgrimage.app.data.MusicTrip
import cn.musicpilgrimage.app.data.MusicTripCity
import cn.musicpilgrimage.app.data.MusicTripStop
import cn.musicpilgrimage.app.data.Place
import cn.musicpilgrimage.app.data.canonicalRegionName
import cn.musicpilgrimage.app.data.locationLabel
import cn.musicpilgrimage.app.data.matchesDestinationName
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
            Modifier.fillMaxWidth().padding(start=20.dp,end=20.dp,bottom=20.dp,top=2.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp)
        ) {
            Text(
                title,
                fontSize = 21.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.2).sp,
                color = RoomColors.Ink,
            )
            content()
        }
    }
}

@Composable internal fun TripError(error:String?) {
    if(error!=null) {
        Box(
            Modifier.fillMaxWidth()
                .clip(TripControlShape)
                .background(RoomColors.Red.copy(alpha = 0.08f))
                .border(0.8.dp, RoomColors.Red.copy(alpha = 0.22f), TripControlShape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(error, fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable internal fun TripDateOptions(
    trip:MusicTrip,
    unavailable:Set<Int>,
    busy:Boolean,
    modifier:Modifier=Modifier,
    onSelect:(Int)->Unit
) {
    LazyColumn(modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        items(trip.days) { index ->
            val day=index+1
            val itemEnabled=!busy&&day !in unavailable
            Box(
                Modifier.fillMaxWidth()
                    .then(
                        if (itemEnabled) Modifier.shadow(
                            elevation = 2.dp,
                            shape = TripControlShape,
                            ambientColor = RoomColors.Ink.copy(alpha = 0.06f),
                            spotColor = RoomColors.Ink.copy(alpha = 0.10f),
                            clip = false,
                        ) else Modifier
                    )
                    .clip(TripControlShape)
                    .background(if (itemEnabled) tripBoardFace else androidx.compose.ui.graphics.SolidColor(RoomColors.Paper))
                    .border(
                        0.8.dp,
                        if (itemEnabled) RoomColors.Line.copy(alpha = 0.82f) else RoomColors.Line.copy(alpha = 0.55f),
                        TripControlShape,
                    )
                    .testTag("trip-date-$day")
                    .clickable(enabled = itemEnabled, role = Role.Button) { onSelect(day) }
                    .padding(horizontal = 15.dp, vertical = 12.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            trip.start.plusDays(index.toLong()).toString(),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (itemEnabled) RoomColors.Ink else RoomColors.Muted,
                        )
                        Text(
                            if (day in unavailable) "第 ${day} 天 · 已安排" else "第 ${day} 天",
                            fontSize = 12.sp,
                            color = RoomColors.Muted,
                        )
                    }
                    if (day in unavailable) {
                        Box(
                            Modifier.clip(TripDaysBadgeShape)
                                .background(RoomColors.Line.copy(alpha = 0.42f))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text("已安排", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = RoomColors.Muted)
                        }
                    } else {
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = RoomColors.Muted.copy(alpha = 0.6f),
                        )
                    }
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
        else->"$label · $country"
    }

internal fun tripDestinationOptions(catalogCities:List<MusicTripCity>):List<MusicTripCity> {
    val countries=catalogCities.map{canonicalRegionName(it.country)}.distinct().map{MusicTripCity.country(it)}
    return (countries+catalogCities.map{it.asPickerDestination()}).distinctBy{it.pickerKey}
}

internal fun searchTripDestinations(options:List<MusicTripCity>,query:String,catalogCities:List<MusicTripCity> = options):List<MusicTripCity> {
    val term=canonicalRegionName(query.trim())
    if(term.isEmpty())return emptyList()
    val matchedRegions=catalogCities.filter{it.region!=null&&it.name.contains(term,true)}.mapTo(mutableSetOf()){it.pickerKey}
    return options.filter {
        it.matchesDestinationName(term)||it.country.contains(term,true)||it.pickerKey in matchedRegions
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
    LazyColumn(modifier.fillMaxWidth(),state=listState,verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item {
            OutlinedTextField(
                value = name,
                onValueChange = { onName(it.take(60)) },
                label = { Text("行程名称") },
                singleLine = true,
                enabled = !busy,
                shape = TripControlShape,
                colors = tripTextFieldColors(),
                modifier = Modifier.fillMaxWidth().testTag("trip-name-input"),
            )
        }
        item { TripSearch(query,{query=it},"搜索城市、国家或地区","trip-destination-search",busy) }
        if(term.isNotEmpty()) {
            if(matches.isEmpty()) item { Text("没有找到匹配的目的地，可以手动填写。",color=RoomColors.Muted,fontSize=13.sp) }
            items(matches,key={"result-${it.key}"}) { destination ->
                val checked=destination.pickerKey in selectedKeys
                val enabled=!busy&&(checked||selected.size<MusicTrip.MAX_DESTINATIONS)
                Row(
                    Modifier.fillMaxWidth()
                        .clip(TripControlShape)
                        .background(
                            if (checked) RoomColors.Red.copy(alpha = 0.08f)
                            else lerp(RoomColors.Paper, Color.White, 0.72f)
                        )
                        .border(
                            0.8.dp,
                            if (checked) RoomColors.Red.copy(alpha = 0.34f) else RoomColors.Line.copy(alpha = 0.78f),
                            TripControlShape,
                        )
                        .toggleable(checked,enabled=enabled,role=Role.Checkbox) {
                            onSelection(if(it) selected+destination else selected.filterNot{row->row.pickerKey==destination.pickerKey})
                        }
                        .padding(end = 14.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = null,
                        enabled = enabled,
                        colors = CheckboxDefaults.colors(
                            checkedColor = RoomColors.Red,
                            checkmarkColor = RoomColors.Paper,
                        ),
                        modifier = Modifier.size(48.dp),
                    )
                    Text(
                        destination.destinationPickerLabel,
                        fontSize = 14.sp,
                        fontWeight = if (checked) FontWeight.Medium else FontWeight.Normal,
                        color = RoomColors.Ink,
                    )
                }
            }
        }
        item(key="manual-entry") {
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                SwissTextButton(
                    onClick = { manual = !manual },
                    enabled = !busy && (manual || selected.size < MusicTrip.MAX_DESTINATIONS),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = RoomColors.Red),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    if (!manual) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(if(manual)"收起手动填写" else "手动填写目的地", fontWeight = FontWeight.Medium)
                }
                if(manual) {
                    Column(
                        Modifier.fillMaxWidth()
                            .clip(TripControlShape)
                            .background(lerp(RoomColors.Paper, Color.White, 0.56f))
                            .border(0.8.dp, RoomColors.Line.copy(alpha = 0.78f), TripControlShape)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = !countryOnly,
                                onClick = { countryOnly = false },
                                label = { Text("城市") },
                                enabled = !busy,
                                shape = RoundedCornerShape(10.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = lerp(RoomColors.Paper, Color.White, 0.72f),
                                    selectedContainerColor = RoomColors.Red.copy(alpha = 0.11f),
                                    selectedLabelColor = RoomColors.Red,
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = !busy,
                                    selected = !countryOnly,
                                    borderColor = RoomColors.Line,
                                    selectedBorderColor = RoomColors.Red.copy(alpha = 0.42f),
                                ),
                            )
                            FilterChip(
                                selected = countryOnly,
                                onClick = { countryOnly = true },
                                label = { Text("国家或地区") },
                                enabled = !busy,
                                shape = RoundedCornerShape(10.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = lerp(RoomColors.Paper, Color.White, 0.72f),
                                    selectedContainerColor = RoomColors.Red.copy(alpha = 0.11f),
                                    selectedLabelColor = RoomColors.Red,
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = !busy,
                                    selected = countryOnly,
                                    borderColor = RoomColors.Line,
                                    selectedBorderColor = RoomColors.Red.copy(alpha = 0.42f),
                                ),
                            )
                        }
                        if(!countryOnly) OutlinedTextField(
                            value = cityName,
                            onValueChange = { cityName = it.take(60) },
                            label = { Text("城市名称") },
                            singleLine = true,
                            enabled = !busy,
                            shape = TripControlShape,
                            colors = tripTextFieldColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = countryName,
                            onValueChange = { countryName = it.take(60) },
                            label = { Text("国家或地区名称") },
                            singleLine = true,
                            enabled = !busy,
                            shape = TripControlShape,
                            colors = tripTextFieldColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        val country=canonicalRegionName(countryName.trim())
                        val city=MusicTripCity(cityName.trim(),country)
                        val destination=if(countryOnly)MusicTripCity.country(country) else city.asPickerDestination()
                        val canAdd = !busy&&selected.size<MusicTrip.MAX_DESTINATIONS&&country.isNotEmpty()&&
                            (countryOnly||destination.region!=null||cityName.isNotBlank()&&!destination.isCountry)&&destination.pickerKey !in selectedKeys
                        SwissOutlinedButton(
                            onClick = {
                                onSelection(selected+destination)
                                manual=false
                                query=""
                                cityName=""
                                countryName=""
                            },
                            enabled = canAdd,
                            shape = TripControlShape,
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = lerp(RoomColors.Paper, Color.White, 0.8f),
                                contentColor = RoomColors.Red,
                            ),
                            border = BorderStroke(
                                0.8.dp,
                                if (canAdd) RoomColors.Red.copy(alpha = 0.45f) else RoomColors.Line.copy(alpha = 0.7f),
                            ),
                        ) {
                            Text("加入目的地", fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
        if(selected.isNotEmpty()) {
            item { Text("已选目的地 · ${visibleSelection.size}",fontSize=13.sp,fontWeight=FontWeight.Medium,color=RoomColors.Muted) }
            items(visibleSelection,key={"selected-${it.pickerKey}"}) { destination ->
                Row(
                    Modifier.fillMaxWidth()
                        .clip(TripControlShape)
                        .background(lerp(RoomColors.Paper, Color.White, 0.75f))
                        .border(0.8.dp, RoomColors.Line.copy(alpha = 0.8f), TripControlShape)
                        .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment=Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Outlined.Place,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = RoomColors.Red.copy(alpha = 0.78f),
                    )
                    Text(
                        if(destination.isCountry||destination.region!=null)destination.label else "${destination.label} · ${destination.country}",
                        Modifier.weight(1f),
                        fontSize=14.sp,
                        color=RoomColors.Ink,
                    )
                    SwissTextButton(
                        onClick = { onSelection(selected.filterNot { it.pickerKey == destination.pickerKey }) },
                        enabled = !busy,
                        shape = TripDaysBadgeShape,
                        colors = ButtonDefaults.textButtonColors(contentColor = RoomColors.Red),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    ) { Text("移除", fontSize = 13.sp) }
                }
            }
        }
    }
}

@Composable private fun TripSearch(
    query:String,onQuery:(String)->Unit,label:String,tag:String,busy:Boolean
) {
    val keyboard=LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = query,
        onValueChange = { onQuery(it.take(100)) },
        label = { Text(label) },
        singleLine = true,
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = RoomColors.Muted) },
        enabled = !busy,
        shape = TripControlShape,
        colors = tripTextFieldColors(),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
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
        LazyColumn(Modifier.weight(1f,fill=false).fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            if(term.isNotEmpty()) {
                if(matches.isEmpty()) item { Text("没有找到匹配的地点，也可以手动添加活动。",fontSize=13.sp,color=RoomColors.Muted) }
                items(matches,key={it.id}) { place ->
                    val added=stops.any{it.placeId==place.id}
                    val itemEnabled = !state.busy && !added
                    Box(
                        Modifier.fillMaxWidth()
                            .then(
                                if (itemEnabled) Modifier.shadow(
                                    elevation = 2.dp,
                                    shape = TripControlShape,
                                    ambientColor = RoomColors.Ink.copy(alpha = 0.06f),
                                    spotColor = RoomColors.Ink.copy(alpha = 0.10f),
                                    clip = false,
                                ) else Modifier
                            )
                            .clip(TripControlShape)
                            .background(if (itemEnabled) tripBoardFace else androidx.compose.ui.graphics.SolidColor(RoomColors.Paper))
                            .border(0.8.dp, RoomColors.Line.copy(alpha = 0.8f), TripControlShape)
                            .testTag("trip-day-place-${place.id}")
                            .clickable(enabled = itemEnabled, role = Role.Button) {
                                vm.add(owner, trip.id, place, trip.start.plusDays(day - 1L)) { query = "" }
                            }
                            .padding(horizontal = 15.dp, vertical = 12.dp),
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    titles[place.id] ?: place.name,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (itemEnabled) RoomColors.Ink else RoomColors.Muted,
                                )
                                Text(
                                    place.locationLabel() + if (added) " · 已安排" else "",
                                    fontSize = 12.sp,
                                    color = RoomColors.Muted,
                                )
                            }
                            if (added) {
                                Box(
                                    Modifier.clip(TripDaysBadgeShape)
                                        .background(RoomColors.Line.copy(alpha = 0.42f))
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                ) {
                                    Text("已安排", fontSize = 11.sp, color = RoomColors.Muted)
                                }
                            } else {
                                Box(
                                    Modifier.size(28.dp)
                                        .clip(TripDaysBadgeShape)
                                        .background(RoomColors.Red.copy(alpha = 0.09f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = RoomColors.Red)
                                }
                            }
                        }
                    }
                }
            } else {
                if(stops.isEmpty()) item {
                    Box(
                        Modifier.fillMaxWidth()
                            .clip(TripControlShape)
                            .background(lerp(RoomColors.Paper, Color.White, 0.56f))
                            .border(0.8.dp, RoomColors.Line.copy(alpha = 0.72f), TripControlShape)
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                    ) {
                        Text("这一天还没有活动。", fontSize = 14.sp, color = RoomColors.Muted)
                    }
                }
                itemsIndexed(stops,key={_,stop->stop.id}) { index,stop ->
                    Column(
                        Modifier.fillMaxWidth()
                            .shadow(
                                elevation = 2.5.dp,
                                shape = TripStopCardShape,
                                ambientColor = RoomColors.Ink.copy(alpha = 0.07f),
                                spotColor = RoomColors.Ink.copy(alpha = 0.11f),
                                clip = false,
                            )
                            .clip(TripStopCardShape)
                            .background(tripBoardFace)
                            .border(0.8.dp, tripCardBorder, TripStopCardShape)
                            .padding(start = 15.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text(
                            "${index+1}. ${titles[stop.placeId]?:stop.name}",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = RoomColors.Ink,
                        )
                        Text(
                            stop.note.ifBlank{trip.cities.find{it.name==stop.city&&it.country==stop.country}?.label?:stop.city},
                            fontSize = 12.sp,
                            color = RoomColors.Muted,
                        )
                        HorizontalDivider(
                            Modifier.padding(top = 4.dp),
                            thickness = 0.5.dp,
                            color = RoomColors.Line.copy(alpha = 0.55f),
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            SwissTextButton(
                                { vm.move(owner, trip.id, stop.id, day, index - 1) },
                                enabled = !state.busy && index > 0,
                                shape = TripDaysBadgeShape,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            ) { Text("上移", fontSize = 13.sp) }
                            SwissTextButton(
                                { vm.move(owner, trip.id, stop.id, day, index + 1) },
                                enabled = !state.busy && index < stops.lastIndex,
                                shape = TripDaysBadgeShape,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            ) { Text("下移", fontSize = 13.sp) }
                            SwissTextButton(
                                { onMove(stop) },
                                enabled = !state.busy && trip.days > 1,
                                shape = TripDaysBadgeShape,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            ) { Text("换一天", fontSize = 13.sp) }
                            SwissTextButton(
                                { onRemove(stop) },
                                enabled = !state.busy,
                                shape = TripDaysBadgeShape,
                                colors = ButtonDefaults.textButtonColors(contentColor = RoomColors.Red),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            ) { Text("移除", fontSize = 13.sp) }
                        }
                    }
                }
            }
        }
        TripError(state.error)
        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SwissOutlinedButton(
                onClick = onCustom,
                enabled = !state.busy,
                shape = TripControlShape,
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = lerp(RoomColors.Paper, Color.White, 0.78f),
                    contentColor = RoomColors.Ink,
                ),
                border = BorderStroke(0.8.dp, RoomColors.Line.copy(alpha = 0.88f)),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = RoomColors.Red)
                Spacer(Modifier.width(4.dp))
                Text("手动添加活动", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            SwissButton(
                onClick = onClose,
                enabled = !state.busy,
                shape = TripPrimaryButtonShape,
                colors = tripPrimaryButtonColors(),
                elevation = tripPrimaryButtonElevation(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
            ) { Text("完成", fontSize = 14.sp, fontWeight = FontWeight.Medium) }
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
        LazyColumn(Modifier.weight(1f,fill=false).fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(100) },
                    label = { Text("活动名称") },
                    singleLine = true,
                    enabled = !state.busy,
                    shape = TripControlShape,
                    colors = tripTextFieldColors(),
                    modifier = Modifier.fillMaxWidth().testTag("trip-custom-name"),
                )
            }
            item {
                Box {
                    SwissOutlinedButton(
                        onClick = { choosingDestination = true },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                        enabled = !state.busy,
                        shape = TripControlShape,
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = lerp(RoomColors.Paper, Color.White, 0.72f),
                            contentColor = RoomColors.Ink,
                        ),
                        border = BorderStroke(0.8.dp, RoomColors.Line.copy(alpha = 0.88f)),
                        contentPadding = PaddingValues(horizontal = 15.dp, vertical = 12.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Place,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = RoomColors.Red.copy(alpha = 0.78f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "目的地 · ${selected?.label ?: "请选择"}",
                            Modifier.weight(1f),
                            fontSize = 14.sp,
                            color = RoomColors.Ink,
                        )
                        Icon(Icons.Outlined.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp), tint = RoomColors.Muted)
                    }
                    DropdownMenu(
                        expanded = choosingDestination,
                        onDismissRequest = { choosingDestination = false },
                        shape = TripControlShape,
                        containerColor = lerp(RoomColors.Paper, Color.White, 0.78f),
                    ) {
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
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(300) },
                    label = { Text("位置或备注（选填）") },
                    enabled = !state.busy,
                    shape = TripControlShape,
                    colors = tripTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        TripError(state.error)
        SwissButton(
            onClick = { selected?.let { vm.custom(owner, trip.id, name, it, day, note, onClose) } },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            enabled = !state.busy && name.isNotBlank() && selected != null,
            shape = TripPrimaryButtonShape,
            colors = tripPrimaryButtonColors(),
            elevation = tripPrimaryButtonElevation(),
        ) { Text("添加活动", fontSize = 15.sp, fontWeight = FontWeight.Medium) }
        SwissTextButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.End),
            enabled = !state.busy,
            shape = RoundedCornerShape(10.dp),
        ) { Text("取消", color = RoomColors.Muted) }
    }
}
