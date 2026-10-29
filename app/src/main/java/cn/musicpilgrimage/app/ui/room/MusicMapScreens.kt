package cn.musicpilgrimage.app.ui.room

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.musicpilgrimage.app.BuildConfig
import cn.musicpilgrimage.app.MusicMapViewModel
import cn.musicpilgrimage.app.data.*
import cn.musicpilgrimage.app.ui.LocalPhysicalMotion
import cn.musicpilgrimage.app.ui.MapPanel
import cn.musicpilgrimage.app.ui.MapPanelAppearance
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

@Composable internal fun RoomMapRoute(vm:MusicMapViewModel,catalog:Catalog,work:MusicWork?,owner:String,favorites:Set<String>,favoriteBusy:Boolean,
    onBack:()->Unit,onPlace:(String)->Unit,onFavorite:(String)->Unit,onTrips:()->Unit,onAdd:(Place)->Unit) {
    val all by vm.all.collectAsStateWithLifecycle()
    val cityKey by vm.city.collectAsStateWithLifecycle()
    val lastCity by vm.lastCity.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val full by vm.full.collectAsStateWithLifecycle()
    val camera by vm.camera.collectAsStateWithLifecycle()
    val sourceNotice by vm.notice.collectAsStateWithLifecycle()
    val scope=owner+":"+(if(all)"*"else work?.id.orEmpty())
    val cities=remember(catalog,work,all){musicMapCities(catalog,work.takeUnless{all})}
    val city=cities.find{it.key==cityKey}
    val holder=rememberSaveableStateHolder()
    LaunchedEffect(work?.id,owner){vm.source("$owner:${work?.id.orEmpty()}")}
    LaunchedEffect(scope){vm.scope(scope)}
    LaunchedEffect(Unit){vm.refresh()}
    val back:()->Unit={if(!vm.back())onBack()}
    BackHandler(cityKey.isNotEmpty(),back)
    Column(Modifier.fillMaxSize().testTag("room-map-route")) {
        RoomHeader(if(city==null)"地图与旅行"else city.name,onBack=back,
            onSpace=LocalRoomSpaceAction.current?:onBack,onSpaceOrigin=LocalRoomSpaceOrigin.current)
        if(city==null)holder.SaveableStateProvider("cities:$scope") {
            LazyColumn(Modifier.fillMaxSize().testTag("room-city-list"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                item("scope"){Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(if(all||work==null)"音乐中的城市"else work.title,fontSize=24.sp,fontWeight=FontWeight.SemiBold,color=RoomColors.Ink)
                    if(!all&&work!=null)Text(work.artist,color=RoomColors.Muted,fontSize=15.sp)
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Text("选择城市",Modifier.weight(1f),fontSize=18.sp,color=RoomColors.Ink)
                        if(work!=null)SwissTextButton({vm.all(!all)},Modifier.testTag("room-map-scope")){Text(if(all)"作品相关"else "全部城市",color=RoomColors.Ink)}
                    }
                }}
                items(cities,key={it.key}) {row->RoomPaper(Modifier.fillMaxWidth().testTag("room-city-${row.name}").semantics{this.selected=row.key==lastCity}.clickable{vm.chooseCity(row.key)}) {
                    Row(Modifier.fillMaxWidth().heightIn(min=88.dp).padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.width(2.dp).height(32.dp).background(if(row.key==lastCity)RoomColors.Navy else androidx.compose.ui.graphics.Color.Transparent))
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text(row.name,fontSize=20.sp,fontWeight=FontWeight.Medium,color=RoomColors.Ink)
                            Text(row.country,fontSize=14.sp,color=RoomColors.Muted)
                        }
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight,null,tint=RoomColors.Ink)
                    }
                }}
                if(cities.isEmpty())item{Text("这部作品暂未收录可展示的地点，可查看全部城市。",color=RoomColors.Muted,lineHeight=23.sp)}
                item("trips"){SwissTextButton(onTrips,Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("room-map-trips")){Icon(Icons.Outlined.Route,null);Spacer(Modifier.width(10.dp));Text("我的行程")}}
                if(sourceNotice!=null)item{Text(sourceNotice!!,color=RoomColors.Muted,fontSize=12.sp)}
            }
        } else key(scope,city.key) {
            RoomCityMap(city,work.takeUnless{all},full,selected,camera,favorites,favoriteBusy,
                {vm.full(!full)},{place->vm.select(city.key,place.id)},
                {vm.camera(scope,city.key,it)},onPlace,onFavorite,onTrips,onAdd)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable private fun RoomCityMap(city:MusicMapCity,work:MusicWork?,full:Boolean,selected:String,camera:List<Double>?,favorites:Set<String>,favoriteBusy:Boolean,
    onExpand:()->Unit,onSelect:(Place)->Unit,onCamera:(List<Double>)->Unit,onPlace:(String)->Unit,onFavorite:(String)->Unit,onTrips:()->Unit,onAdd:(Place)->Unit) {
    val motion=LocalPhysicalMotion.current
    var ready by remember{mutableStateOf(false)}
    var zoom by remember{mutableIntStateOf(0)}
    var listOpen by remember{mutableStateOf(false)}
    var emphasis by remember{mutableIntStateOf(0)}
    val place=city.places.find{it.id==selected}
    val cardHeight by animateDpAsState(if(place==null)58.dp else 190.dp,tween(if(motion)180 else 0),label="place-card-height")
    Column(Modifier.fillMaxSize().padding(horizontal=16.dp).padding(top=8.dp,bottom=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min=44.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(work?.title?:city.name,Modifier.weight(1f).padding(start=3.dp),color=RoomColors.Ink,fontSize=18.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis)
            SwissTextButton({listOpen=true},Modifier.testTag("room-map-place-list"),contentPadding=PaddingValues(horizontal=8.dp)) {
                Icon(Icons.Outlined.FormatListBulleted,null,Modifier.size(18.dp),tint=RoomColors.Ink);Spacer(Modifier.width(6.dp));Text("地点清单",fontSize=13.sp,color=RoomColors.Ink)
            }
        }
        RoomMapFrame(full,Modifier.fillMaxWidth().weight(1f).heightIn(min=180.dp).testTag(if(full)"room-map-full"else "room-map-wall")) {
                MapPanel(city.places,BuildConfig.MAPTILER_KEY,{chosen->if(chosen.id==selected)emphasis++ else onSelect(chosen)},Modifier.fillMaxSize(),
                    mediaShape=androidx.compose.ui.graphics.RectangleShape,appearance=MapPanelAppearance(RoomColors.Paper,RoomColors.Ink,RoomColors.Muted,RoomColors.Navy),
                    savedCamera=camera,onCameraChanged=onCamera,
                    roomPins=true,selectedPlaceId=selected,gesturesEnabled=full,zoomRequest=zoom,onReady={ready=it})
                if(full)Surface(Modifier.align(Alignment.BottomEnd).padding(12.dp),shape=androidx.compose.ui.graphics.RectangleShape,color=RoomColors.Paper,shadowElevation=3.dp,border=BorderStroke(.5.dp,RoomColors.Line)) {Column {
                    IconButton({zoom++},Modifier.size(44.dp).testTag("room-map-zoom-in"),enabled=ready){Icon(Icons.Outlined.Add,"放大地图",Modifier.size(22.dp),tint=RoomColors.Ink)}
                    HorizontalDivider(Modifier.padding(horizontal=10.dp).width(24.dp),color=RoomColors.Line,thickness=.5.dp)
                    IconButton({zoom--},Modifier.size(44.dp).testTag("room-map-zoom-out"),enabled=ready){Icon(Icons.Outlined.Remove,"缩小地图",Modifier.size(22.dp),tint=RoomColors.Ink)}
                }} else RoomMapSlimAction("展开地图",Icons.Outlined.OpenInFull,onExpand,"room-map-expand",Modifier.align(Alignment.BottomEnd).padding(end=10.dp,bottom=5.dp))
                if(ready)Spacer(Modifier.size(1.dp).testTag("room-map-ready"))
        }
        Box(Modifier.fillMaxWidth().height(cardHeight).clipToBounds().padding(horizontal=2.dp,vertical=3.dp)) {
            if(place==null)Text("点击地图针查看地点，也可打开地点清单。",color=RoomColors.Muted,fontSize=14.sp,modifier=Modifier.padding(8.dp,10.dp))
            else RoomMapPlaceCard(place,work,place.id in favorites,favoriteBusy,emphasis,{onPlace(place.id)},{onFavorite(place.id)},{onAdd(place)})
        }
        AnimatedVisibility(!full,enter=fadeIn()+expandVertically(tween(if(motion)260 else 0)),exit=fadeOut()+shrinkVertically(tween(if(motion)260 else 0))) {
            RoomPaper(Modifier.fillMaxWidth().height(48.dp).clickable(onClick=onTrips).testTag("room-map-trips")) {
                Row(Modifier.fillMaxSize().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Route,null,tint=RoomColors.Ink);Text("我的行程",Modifier.weight(1f),color=RoomColors.Ink);Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight,null,tint=RoomColors.Ink)
                }
            }
        }
    }
    if(listOpen)ModalBottomSheet(onDismissRequest={listOpen=false},containerColor=RoomColors.Paper) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max=480.dp),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            item{Text(city.name+"的地点",fontSize=22.sp,color=RoomColors.Ink)}
            items(city.places,key={it.id}) {entry->SwissTextButton({listOpen=false;if(entry.id==selected)emphasis++ else onSelect(entry)},Modifier.fillMaxWidth().heightIn(min=52.dp).testTag("room-map-place-${entry.id}")) {
                Text(entry.name,Modifier.weight(1f),color=RoomColors.Ink);if(entry.id==selected)Icon(Icons.Outlined.Check,"已选中",tint=RoomColors.Navy)
            }}
        }
    }
}

@Composable private fun RoomMapPlaceCard(place:Place,work:MusicWork?,favorite:Boolean,busy:Boolean,emphasis:Int,onOpen:()->Unit,onFavorite:()->Unit,onAdd:()->Unit) {
    val motion=LocalPhysicalMotion.current
    var highlighted by remember{mutableStateOf(false)}
    LaunchedEffect(emphasis){if(emphasis>0){highlighted=true;delay(220);highlighted=false}}
    val shadow by animateDpAsState(if(highlighted)10.dp else 2.dp,tween(if(motion)110 else 0),label="place-card-shadow")
    // The paper remains in the same slot; only its contents cross-fade between places.
    RoomPaper(Modifier.fillMaxSize().shadow(shadow,androidx.compose.ui.graphics.RectangleShape).testTag("room-place-card")) {
        Crossfade(targetState=place,animationSpec=tween(if(motion)100 else 0),label="map-place-content") {p->
            val scene=p.mapScene(work)
            Column(Modifier.fillMaxSize().padding(start=14.dp,end=14.dp,top=8.dp,bottom=4.dp)) {
                Row(Modifier.fillMaxWidth().height(40.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text(p.name,Modifier.weight(1f).clickable(onClick=onOpen),fontSize=19.sp,lineHeight=25.sp,fontWeight=FontWeight.SemiBold,color=RoomColors.Ink,maxLines=1,overflow=TextOverflow.Ellipsis)
                    IconButton(onFavorite,Modifier.size(40.dp).testTag("room-place-favorite"),enabled=!busy){Icon(if(favorite)Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,if(favorite)"取消收藏地点"else "收藏地点",Modifier.size(22.dp),tint=RoomColors.Navy)}
                }
                Spacer(Modifier.height(5.dp))
                Row(Modifier.fillMaxWidth().clickable(onClick=onOpen),horizontalArrangement=Arrangement.spacedBy(13.dp),verticalAlignment=Alignment.CenterVertically) {
                    if(scene!=null)RoomReference(scene,"${p.name} · ${scene.work}原图",Modifier.width(112.dp).height(64.dp))
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                        Text(scene?.let{"《${it.work}》"}.orEmpty(),fontSize=13.sp,lineHeight=17.sp,fontWeight=FontWeight.Medium,color=RoomColors.Ink,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(scene?.let{"${it.artistName} · ${it.kindLabel}"}.orEmpty(),fontSize=12.sp,lineHeight=16.sp,color=RoomColors.Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text("${p.city} · ${p.country}",fontSize=11.sp,lineHeight=15.sp,color=RoomColors.Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.weight(1f))
                HorizontalDivider(color=RoomColors.Line.copy(alpha=.65f),thickness=.5.dp)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                    SwissTextButton(onOpen,Modifier.height(44.dp).testTag("room-place-open"),contentPadding=PaddingValues(horizontal=0.dp)){Text("查看地点",fontSize=13.sp,lineHeight=18.sp,color=RoomColors.Navy);Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight,null,Modifier.size(18.dp),tint=RoomColors.Navy)}
                    SwissTextButton(onAdd,Modifier.height(44.dp).testTag("room-place-add-trip"),contentPadding=PaddingValues(horizontal=0.dp)){Icon(Icons.Outlined.Add,null,Modifier.size(17.dp),tint=RoomColors.Navy);Spacer(Modifier.width(4.dp));Text("加入行程",fontSize=13.sp,lineHeight=18.sp,color=RoomColors.Navy)}
                }
            }
        }
    }
}

/** A thin visible paper plate with an independent 44dp touch target. */
@Composable private fun RoomMapSlimAction(label:String,icon:ImageVector,onClick:()->Unit,tag:String,modifier:Modifier=Modifier) {
    Box(modifier.height(44.dp).testTag(tag).clickable(role=Role.Button,onClick=onClick),contentAlignment=Alignment.Center) {
        Surface(shape=androidx.compose.ui.graphics.RectangleShape,color=RoomColors.Paper,shadowElevation=1.dp,border=BorderStroke(.5.dp,RoomColors.Line.copy(alpha=.6f))) {
            Row(Modifier.height(30.dp).padding(horizontal=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                Icon(icon,null,Modifier.size(16.dp),tint=RoomColors.Navy)
                Text(label,fontSize=13.sp,lineHeight=16.sp,fontWeight=FontWeight.Medium,color=RoomColors.Navy)
            }
        }
    }
}
