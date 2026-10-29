package cn.musicpilgrimage.app.ui.room

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.musicpilgrimage.app.BuildConfig
import cn.musicpilgrimage.app.data.*
import cn.musicpilgrimage.app.ui.MapPanel
import cn.musicpilgrimage.app.ui.MapPanelAppearance
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun SwissWorldWall(catalog:Catalog,browse:RoomBrowseState,favorites:Set<String>,favoriteBusy:Boolean,
    onPlace:(String)->Unit,onFavorite:(String)->Unit,onTrips:()->Unit,onFootprints:()->Unit,onAdd:(Place)->Unit) {
    val cities=remember(catalog){musicMapCities(catalog,null)}
    val places=remember(cities){cities.flatMap{it.places}.distinctBy{it.id}}
    val selected=places.find{it.id==browse.selected}
    var camera by remember{mutableStateOf(browse.globe)}
    val latestCamera by rememberUpdatedState(camera)
    LaunchedEffect(camera){delay(180);browse.globe(camera)}
    DisposableEffect(browse){onDispose{browse.globe(latestCamera)}}
    var chooser by remember{mutableStateOf<List<Place>?>(null)}
    var zoom by remember{mutableIntStateOf(0)}
    var mapReady by remember{mutableStateOf(false)}
    BackHandler(browse.street){browse.street(false)}
    Column(Modifier.fillMaxSize().testTag("world-wall")) {
        Row(Modifier.fillMaxWidth().padding(horizontal=20.dp).height(44.dp),verticalAlignment=Alignment.CenterVertically) {
            if(browse.street)SwissTextButton({browse.street(false)},Modifier.testTag("world-return-globe"),contentPadding=PaddingValues(horizontal=4.dp)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack,null,Modifier.size(17.dp),tint=SwissInk);Spacer(Modifier.width(6.dp));Text("返回地球",color=SwissInk,fontSize=13.sp)
            }else Text("${cities.size} 座城市 / ${places.size} 个地点",Modifier.padding(start=6.dp),color=RoomColors.Muted,fontSize=12.sp)
            Spacer(Modifier.weight(1f))
            SwissTextButton({chooser=places},Modifier.testTag("world-place-list"),contentPadding=PaddingValues(horizontal=6.dp)) {
                Icon(Icons.Outlined.FormatListBulleted,null,Modifier.size(17.dp),tint=SwissInk);Spacer(Modifier.width(5.dp));Text("地点",color=SwissInk,fontSize=13.sp)
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if(browse.street&&selected!=null) {
                val nearby=places.filter{it.city==selected.city&&it.country==selected.country}
                key(selected.city,selected.country) {
                    MapPanel(nearby,BuildConfig.MAPTILER_KEY,{browse.select(it.id)},Modifier.fillMaxSize().padding(horizontal=16.dp).testTag("world-street-map"),
                        mediaShape=RectangleShape,appearance=MapPanelAppearance(RoomColors.Paper,RoomColors.Ink,RoomColors.Muted,SwissRed),
                        savedCamera=browse.streetCamera,onCameraChanged=browse::camera,roomPins=true,selectedPlaceId=selected.id,gesturesEnabled=true,
                        zoomRequest=zoom,onReady={mapReady=it})
                }
                Surface(Modifier.align(Alignment.BottomEnd).padding(24.dp),color=RoomColors.Paper,shadowElevation=2.dp) {
                    Column {IconButton({zoom++},enabled=mapReady){Icon(Icons.Outlined.Add,"放大街道地图")};IconButton({zoom--},enabled=mapReady){Icon(Icons.Outlined.Remove,"缩小街道地图")}}
                }
            }else {
                RoomInteractiveGlobe(places,camera,browse.selected,{camera=it},{candidates->
                    if(candidates.size==1)browse.select(candidates.single().id)else chooser=candidates
                },Modifier.fillMaxSize())
                Column(Modifier.align(Alignment.BottomEnd).padding(end=20.dp,bottom=10.dp).background(RoomColors.Paper)) {
                    IconButton({camera=camera.copy(zoom=camera.zoom*1.2f).bounded()},Modifier.testTag("globe-zoom-in")){Icon(Icons.Outlined.Add,"放大地球",tint=SwissInk)}
                    IconButton({camera=camera.copy(zoom=camera.zoom/1.2f).bounded()},Modifier.testTag("globe-zoom-out")){Icon(Icons.Outlined.Remove,"缩小地球",tint=SwissInk)}
                }
                if(places.isEmpty())Text("正在读取音乐地点…",Modifier.align(Alignment.Center),color=RoomColors.Muted)
            }
        }
        if(selected!=null) {
            val scene=selected.mapScene()
            val heading=selected.heading()
            Column(Modifier.fillMaxWidth().padding(horizontal=26.dp).padding(top=8.dp).testTag("world-place-summary")) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(heading.title,Modifier.clearAndSetSemantics{contentDescription=selected.name},fontSize=20.sp,fontWeight=FontWeight.SemiBold,color=SwissInk,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text("${selected.city} · ${selected.country}",fontSize=11.sp,color=RoomColors.Muted,modifier=Modifier.padding(top=4.dp))
                        if(heading.qualifier.isNotBlank())Text(heading.qualifier,fontSize=11.sp,color=RoomColors.Muted,modifier=Modifier.padding(top=3.dp))
                    }
                    IconButton({onFavorite(selected.id)},Modifier.testTag("world-place-favorite"),enabled=!favoriteBusy) {
                        Icon(if(selected.id in favorites)Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,if(selected.id in favorites)"取消收藏地点"else "收藏地点",tint=if(selected.id in favorites)SwissRed else SwissInk,modifier=Modifier.size(22.dp))
                    }
                    if(!browse.street)IconButton({browse.select("")},Modifier.testTag("world-clear-selection")){Icon(Icons.Outlined.Close,"收起地点摘要",tint=RoomColors.Muted,modifier=Modifier.size(19.dp))}
                }
                if(scene!=null)Row(Modifier.fillMaxWidth().padding(top=12.dp).clickable{onPlace(selected.id)},verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                    RoomReference(scene,"${selected.name} · ${scene.work}",Modifier.width(100.dp).height(57.dp))
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                        Text("《${scene.work}》",fontSize=14.sp,color=SwissInk,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text("${scene.artistName} · ${scene.kindLabel}",fontSize=11.sp,color=RoomColors.Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top=5.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                    if(!browse.street)SwissTextButton({browse.openStreet(selected)},Modifier.testTag("world-open-street"),contentPadding=PaddingValues(0.dp)){Text("查看周边",color=SwissRed,fontSize=12.sp)}
                    SwissTextButton({onPlace(selected.id)},Modifier.testTag("world-place-details"),contentPadding=PaddingValues(0.dp)){Text("查看详情",color=SwissInk,fontSize=12.sp)}
                    SwissTextButton({onAdd(selected)},Modifier.testTag("world-place-add-trip"),contentPadding=PaddingValues(0.dp)){Text("加入行程",color=SwissInk,fontSize=12.sp)}
                }
            }
        }else Column(Modifier.fillMaxWidth().height(110.dp).padding(horizontal=26.dp),verticalArrangement=Arrangement.Center) {
            Text("循着音乐，抵达世界。",color=SwissInk,fontSize=20.sp,fontWeight=FontWeight.Medium)
            Text("拖动地球 · 双指缩放 · 点选音乐地点",Modifier.padding(top=10.dp),color=RoomColors.Muted,fontSize=11.sp,letterSpacing=.3.sp)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=21.dp).height(42.dp),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
            SwissTextButton(onTrips,Modifier.testTag("world-trips"),contentPadding=PaddingValues(horizontal=5.dp)){Text("我的行程",color=RoomColors.Muted,fontSize=11.sp)}
            SwissTextButton(onFootprints,Modifier.testTag("world-footprints"),contentPadding=PaddingValues(horizontal=5.dp)){Text("我的足迹",color=RoomColors.Muted,fontSize=11.sp)}
        }
    }
    chooser?.let{options->ModalBottomSheet(onDismissRequest={chooser=null},containerColor=RoomColors.Paper) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max=520.dp).testTag("world-place-chooser"),contentPadding=PaddingValues(26.dp,0.dp,26.dp,28.dp)) {
            item{Text("音乐地点",fontSize=23.sp,fontWeight=FontWeight.SemiBold,color=SwissInk,modifier=Modifier.padding(bottom=16.dp))}
            items(options,key={it.id}){place->
                Row(Modifier.fillMaxWidth().clickable{browse.select(place.id);if(browse.street)browse.openStreet(place);chooser=null}.padding(vertical=15.dp),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                        val heading=place.heading()
                        Text(heading.title,Modifier.clearAndSetSemantics{contentDescription=place.name},fontSize=17.sp,color=SwissInk)
                        if(heading.qualifier.isNotBlank())Text(heading.qualifier,fontSize=11.sp,color=RoomColors.Muted)
                        Text("${place.city} · ${place.scenes.firstOrNull()?.work.orEmpty()}",fontSize=12.sp,color=RoomColors.Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                    Icon(Icons.Outlined.LocationOn,null,tint=SwissRed,modifier=Modifier.size(20.dp))
                }
                HorizontalDivider(color=RoomColors.Line,thickness=.5.dp)
            }
        }
    }}
}
