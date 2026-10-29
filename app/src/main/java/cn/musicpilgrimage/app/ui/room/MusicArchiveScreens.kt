package cn.musicpilgrimage.app.ui.room

import android.graphics.BitmapFactory
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import cn.musicpilgrimage.app.MusicArchiveState
import cn.musicpilgrimage.app.data.*
import cn.musicpilgrimage.app.ui.LocalPhysicalMotion
import cn.musicpilgrimage.app.ui.PrivatePhoto
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

@Composable internal fun RoomArchiveHome(
    catalog:MusicCatalogue,favorites:MusicFavorites,archive:MusicArchiveState,places:List<Place>,
    onDrawer:(String)->Unit,onWork:(MusicWork)->Unit,onRelease:(MusicRelease)->Unit,onPlace:(String)->Unit,onMore:()->Unit,onTrips:()->Unit=onMore
) {
    val motion=LocalPhysicalMotion.current
    val scope=rememberCoroutineScope()
    var selected by rememberSaveable {mutableStateOf<String?>(null)}
    val pull=remember {Animatable(if(selected==null)0f else 1f)}
    LaunchedEffect(Unit){if(selected!=null){pull.animateTo(0f,tween(if(motion)120 else 0));selected=null}}
    LazyColumn(Modifier.fillMaxSize().testTag("room-archive"),state=rememberLazyListState(),contentPadding=PaddingValues(bottom=28.dp)) {
        item {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal=12.dp)) {
                val scale=maxWidth/1155f
                Box(Modifier.fillMaxWidth().height(scale*1362f)) {
                    AsyncImage(roomAsset("cabinet-empty"),null,Modifier.matchParentSize(),contentScale=ContentScale.FillBounds)
                    val tops=listOf(310f,564f,778f,990f)
                    val heights=listOf(252f,212f,210f,211f)
                    listOf("songs" to "歌曲","releases" to "专辑","places" to "地点","footprints" to "足迹").forEachIndexed { index,(kind,label)->
                        val active=selected==kind
                        val amount=if(active)pull.value else 0f
                        Box(Modifier.offset(x=scale*68f,y=scale*tops[index]).width(scale*1020f).height(scale*heights[index])
                            .graphicsLayer{translationY=8.dp.toPx()*amount;scaleX=1f+.025f*amount;scaleY=1f+.025f*amount}
                            .testTag("room-drawer-$kind")
                            .clickable(role=Role.Button,onClickLabel="打开$label 抽屉") {
                                if(!pull.isRunning)scope.launch{selected=kind;pull.animateTo(1f,tween(if(motion)160 else 0));onDrawer(kind)}
                            }) {
                            if(active&&amount>0f) {
                                CabinetDrawerSlice(tops[index],heights[index],Modifier.matchParentSize())
                            }
                            Text(label,Modifier.align(Alignment.CenterStart).padding(start=30.dp),color=RoomColors.Ink,fontSize=20.sp,fontWeight=FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
        item {RoomPaper(Modifier.padding(horizontal=20.dp).fillMaxWidth().clickable(role=Role.Button,onClick=onTrips)){Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Outlined.Route,null,tint=RoomColors.Navy);Text("我的行程",Modifier.weight(1f).padding(start=12.dp),fontSize=16.sp,color=RoomColors.Ink);Icon(Icons.Outlined.ChevronRight,null,tint=RoomColors.Muted)}}}
        item {Text("最近添加",Modifier.padding(start=25.dp,top=22.dp,bottom=12.dp),fontSize=20.sp,fontWeight=FontWeight.SemiBold,color=RoomColors.Ink)}
        val recent=buildList {
            favorites.songs.forEach{id->catalog.works.find{it.id==id}?.let{add(RecentArchiveItem("song:$id",it.title,"歌曲 · ${it.artist}",favorites.time(id,false),work=it))}}
            favorites.releases.forEach{id->catalog.releases.find{it.id==id}?.let{add(RecentArchiveItem("release:$id",it.title,"专辑 · ${it.artist}",favorites.time(id,true),release=it))}}
            archive.places.forEach{id->places.find{it.id==id}?.let{add(RecentArchiveItem("place:$id",it.name,"地点 · ${it.city}",0,place=it))}}
            archive.visits.forEach{record->places.find{it.id==record.location}?.let{place->
                val time=runCatching{Instant.parse(record.raw.optString("created_at")).toEpochMilli()}.getOrDefault(0L)
                add(RecentArchiveItem("visit:${record.id}",place.name,"足迹 · ${visitDate(record)}",time,place=place))
            }}
        }.sortedByDescending{it.time}.take(3)
        if(recent.isEmpty())item {Text("打开抽屉整理收藏，或去唱片架发现喜欢的作品。",Modifier.padding(horizontal=25.dp),fontSize=14.sp,lineHeight=23.sp,color=RoomColors.Muted)}
        items(recent,key={it.key}) { item->
            Row(Modifier.fillMaxWidth().clickable(role=Role.Button){when{item.work!=null->onWork(item.work);item.release!=null->onRelease(item.release);item.place!=null->onPlace(item.place.id)}}.padding(horizontal=25.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                val art=item.work?.artwork?:item.release?.artwork
                if(art!=null)RoomCover(art,item.title,Modifier.size(52.dp)) else ArchivePlaceImage(item.place,Modifier.size(52.dp))
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)){Text(item.title,fontSize=16.sp,maxLines=1,overflow=TextOverflow.Ellipsis,color=RoomColors.Ink);Text(item.subtitle,fontSize=12.sp,color=RoomColors.Muted)}
                Icon(Icons.Outlined.ChevronRight,null,tint=RoomColors.Muted)
            }
        }
        item {SwissTextButton(onMore,Modifier.padding(start=15.dp,top=16.dp).testTag("archive-more-records")){Text("更多记录");Icon(Icons.Outlined.ChevronRight,null)}}
    }
}

private data class RecentArchiveItem(val key:String,val title:String,val subtitle:String,val time:Long,val work:MusicWork?=null,val release:MusicRelease?=null,val place:Place?=null)

/** The furniture sprite is clipped during drawing, while labels and touch targets remain native. */
@Composable private fun CabinetDrawerSlice(top:Float,height:Float,modifier:Modifier) {
    val context=LocalContext.current
    val bitmap=remember{context.assets.open("design/music-space/cabinet-empty.webp").use{BitmapFactory.decodeStream(it)}.asImageBitmap()}
    Canvas(modifier.clip(androidx.compose.ui.graphics.RectangleShape)) {
        drawImage(bitmap,srcOffset=IntOffset(68,top.toInt()),srcSize=IntSize(1020,height.toInt()),dstSize=IntSize(size.width.toInt(),size.height.toInt()))
    }
}

@Composable internal fun RoomDrawerFrame(modifier:Modifier=Modifier,content:@Composable BoxScope.()->Unit) {
    Box(modifier.padding(horizontal=24.dp,vertical=8.dp),content=content)
}

@Composable private fun ArchiveTabs(selected:String,entries:List<Pair<String,String>>,onSelect:(String)->Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal=24.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        entries.forEach{(id,title)->Column(horizontalAlignment=Alignment.CenterHorizontally) {
            SwissTextButton({onSelect(id)},Modifier.heightIn(min=44.dp).testTag("archive-tab-$id")){Text(title,color=if(selected==id)RoomColors.Ink else RoomColors.Muted,fontSize=14.sp)}
            Box(Modifier.width(22.dp).height(2.dp).background(if(selected==id)RoomColors.Red else Color.Transparent))
        }}
    }
}

@Composable internal fun RoomMusicFavorites(catalog:MusicCatalogue,favorites:MusicFavorites,initialKind:String,onWork:(MusicWork)->Unit,onRelease:(MusicRelease)->Unit,onRemove:(String,Boolean)->Unit,onDiscover:()->Unit,sync:PersonalLibrarySyncState=PersonalLibrarySyncState(),onSync:()->Unit={},onPlaces:(()->Unit)?=null) {
    var kind by rememberSaveable(initialKind) {mutableStateOf(initialKind)}
    val songScroll=rememberLazyListState();val releaseScroll=rememberLazyListState()
    val release=kind=="releases"
    val ids=if(release)favorites.releases else favorites.songs
    val motion=LocalPhysicalMotion.current
    Column(Modifier.fillMaxSize().testTag("room-music-favorites")) {
        ArchiveTabs(kind,listOf("releases" to "发行","songs" to "作品")+if(onPlaces!=null)listOf("places" to "地点")else emptyList()){if(it=="places")onPlaces?.invoke()else kind=it}
        RoomLibrarySyncStatus(sync.takeIf{it.owner==favorites.owner}?:PersonalLibrarySyncState(),onSync,Modifier.padding(horizontal=24.dp))
        RoomDrawerFrame(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize().testTag("archive-music-list"),state=if(release)releaseScroll else songScroll,contentPadding=PaddingValues(7.dp,12.dp,7.dp,88.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                if(ids.isNotEmpty())item(key="count"){Text("${ids.size} 项收藏",fontSize=12.sp,color=RoomColors.Muted,modifier=Modifier.padding(bottom=3.dp))}
                if(ids.isEmpty())item(key="empty"){Column(Modifier.padding(vertical=18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){Text(if(release)"还没有收藏专辑"else "还没有收藏歌曲",color=RoomColors.Ink);SwissOutlinedButton(onDiscover,shape=androidx.compose.ui.graphics.RectangleShape){Text("浏览唱片架")}}}
                items(ids,key={"$kind:$it"}) { id->
                    val work=if(release)null else catalog.works.find{it.id==id}
                    val album=if(release)catalog.releases.find{it.id==id} else catalog.releases.find{it.id==work?.releaseId}
                    RoomPaper(Modifier.fillMaxWidth().animateItem(fadeInSpec=tween(if(motion)140 else 0),placementSpec=tween(if(motion)180 else 0),fadeOutSpec=tween(if(motion)110 else 0)).testTag("archive-card-$id")) {
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                            Row(Modifier.weight(1f).clickable(role=Role.Button){if(release&&album!=null)onRelease(album)else if(work!=null)onWork(work)}.padding(start=11.dp,top=13.dp,bottom=13.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                                RoomCover(if(release)album?.artwork else work?.artwork,if(release)album?.title.orEmpty() else work?.title.orEmpty(),Modifier.size(62.dp))
                                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                                    Text(if(release)album?.title?:"作品暂未收录" else work?.title?:"作品暂未收录",fontSize=16.sp,fontWeight=FontWeight.Medium,color=RoomColors.Ink,maxLines=1,overflow=TextOverflow.Ellipsis)
                                    Text(if(release)album?.artist.orEmpty() else work?.artist.orEmpty(),fontSize=12.sp,color=RoomColors.Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
                                    if(!release)Text(album?.title.orEmpty(),fontSize=11.sp,color=RoomColors.Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
                                }
                            }
                            IconButton({onRemove(id,release)},Modifier.testTag("archive-remove-$id")){Icon(Icons.Outlined.Favorite,"取消${if(release)"专辑" else "歌曲"}收藏",tint=RoomColors.Red,modifier=Modifier.size(20.dp))}
                        }
                    }
                }
            }
        }
    }
}

@Composable internal fun RoomPlaceFavorites(
    archive:MusicArchiveState,places:List<Place>,initialKind:String,onPlace:(String)->Unit,onRemove:(String)->Unit,
    onLogin:()->Unit,onReload:()->Unit,onEdit:(ContentItem)->Unit,onDelete:(ContentItem)->Unit,services:AppServices,onMusic:((String)->Unit)?=null
) {
    var kind by rememberSaveable{mutableStateOf(initialKind)}
    val placeScroll=rememberLazyListState();val visitScroll=rememberLazyListState()
    val grouped=archive.visits.groupBy{it.location}.entries.sortedByDescending{(_,rows)->rows.maxOf{visitDate(it)}}
    Column(Modifier.fillMaxSize().testTag("room-place-favorites")) {
        ArchiveTabs(kind,if(initialKind=="places"&&onMusic!=null)listOf("releases" to "发行","songs" to "作品","places" to "地点")else listOf("places" to "地点","footprints" to "足迹")){if(it in listOf("songs","releases"))onMusic?.invoke(it)else kind=it}
        RoomDrawerFrame(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(),state=if(kind=="places")placeScroll else visitScroll,contentPadding=PaddingValues(7.dp,12.dp,7.dp,88.dp),verticalArrangement=Arrangement.spacedBy(11.dp)) {
                if(archive.owner==null)item{Column(Modifier.padding(vertical=18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){Text("登录查看${if(kind=="places")"地点收藏" else "认证足迹"}",color=RoomColors.Ink);Text("已有收藏和到访记录会保留在你的账号中。",fontSize=14.sp,color=RoomColors.Muted);SwissButton(onLogin,shape=androidx.compose.ui.graphics.RectangleShape){Text("登录")}}}
                else {
                    item {Text(if(kind=="places")"${archive.places.size} 个收藏地点" else "${grouped.size} 个到访地点",fontSize=12.sp,color=RoomColors.Muted)}
                    if(archive.loading)item{LinearProgressIndicator(Modifier.fillMaxWidth(),color=RoomColors.Navy)}
                    if(archive.error!=null)item{Column{Text(archive.error,color=MaterialTheme.colorScheme.error,fontSize=13.sp);SwissTextButton(onReload){Text("重试")}}}
                    if(kind=="places") {
                        if(archive.places.isEmpty()&&!archive.loading&&archive.error==null)item{Text("还没有收藏地点，在作品或地图中发现喜欢的地方。",fontSize=14.sp,lineHeight=23.sp,color=RoomColors.Muted)}
                        items(archive.places,key={it}) {id->
                            val place=places.find{it.id==id}
                            RoomPaper(Modifier.fillMaxWidth().animateItem().testTag("archive-place-$id")) {
                                Row(verticalAlignment=Alignment.CenterVertically) {
                                    Row(Modifier.weight(1f).clickable(role=Role.Button){onPlace(id)}.padding(start=11.dp,top=12.dp,bottom=12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically){
                                        ArchivePlaceImage(place,Modifier.width(90.dp).height(66.dp))
                                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)){Text(place?.name?:"地点暂未收录",maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=16.sp,color=RoomColors.Ink);Text(listOfNotNull(place?.city,place?.country).joinToString(" · "),fontSize=12.sp,color=RoomColors.Muted,maxLines=1,overflow=TextOverflow.Ellipsis)}
                                    }
                                    IconButton({onRemove(id)},Modifier.testTag("archive-remove-place-$id")){Icon(Icons.Outlined.Favorite,"取消地点收藏",tint=RoomColors.Red,modifier=Modifier.size(20.dp))}
                                }
                            }
                        }
                    } else {
                        if(grouped.isEmpty()&&!archive.loading&&archive.error==null)item{Text("还没有认证足迹，到访地点后可以留下记录。",fontSize=14.sp,lineHeight=23.sp,color=RoomColors.Muted)}
                        items(grouped,key={it.key}) {(id,records)->FootprintCard(places.find{it.id==id},records,{onPlace(id)},onEdit,onDelete,services)}
                    }
                }
            }
        }
    }
}

@Composable private fun ArchivePlaceImage(place:Place?,modifier:Modifier) {
    val scene=place?.scenes?.firstOrNull{it.publiclyVisible&&it.imageApproved}
    if(scene!=null)RoomReference(scene,place.name,modifier)
    else Box(modifier.background(RoomColors.Paper),contentAlignment=Alignment.Center){Icon(Icons.Outlined.Place,null,tint=RoomColors.Muted)}
}

private fun visitDate(item:ContentItem)=item.raw.optString("verified_at").takeIf{it.length>=10&&it!="null"}?.take(10)?:item.date

@Composable private fun FootprintCard(place:Place?,records:List<ContentItem>,onPlace:()->Unit,onEdit:(ContentItem)->Unit,onDelete:(ContentItem)->Unit,services:AppServices) {
    var expanded by rememberSaveable{mutableStateOf(false)}
    val motion=LocalPhysicalMotion.current
    RoomPaper(Modifier.fillMaxWidth().testTag("archive-footprint-${records.first().location}")) {
        Column {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Row(Modifier.weight(1f).clickable(role=Role.Button,onClick=onPlace).padding(start=11.dp,top=13.dp,bottom=13.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically){
                    ArchivePlaceImage(place,Modifier.width(83.dp).height(63.dp))
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)){Text(place?.name?:records.first().placeName,maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=16.sp,color=RoomColors.Ink);Text("最近到访 ${records.maxOf{visitDate(it)}}",fontSize=12.sp,color=RoomColors.Muted)}
                }
                IconButton({expanded=!expanded},Modifier.testTag("archive-expand-${records.first().location}")){Icon(if(expanded)Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,if(expanded)"收起历次到访" else "展开历次到访",tint=RoomColors.Navy)}
            }
            AnimatedVisibility(expanded,enter=expandVertically(tween(if(motion)160 else 0))+fadeIn(),exit=shrinkVertically(tween(if(motion)140 else 0))+fadeOut()) {
                Column(Modifier.padding(start=14.dp,end=12.dp,bottom=12.dp)) {records.sortedByDescending{visitDate(it)}.forEach { record->key(record.id){VisitTimelineRow(record,onEdit,onDelete,services)}}}
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun VisitTimelineRow(record:ContentItem,onEdit:(ContentItem)->Unit,onDelete:(ContentItem)->Unit,services:AppServices) {
    var menu by remember{mutableStateOf(false)};var remove by remember{mutableStateOf(false)};var photo by remember{mutableStateOf(false)}
    var opened by remember{mutableStateOf(false)};var dragging by remember{mutableStateOf(false)};var raw by remember{mutableFloatStateOf(0f)}
    val width=with(LocalDensity.current){102.dp.toPx()}
    val offset by animateFloatAsState(if(dragging)raw else if(opened)-width else 0f,tween(if(dragging)0 else 140),label="visit actions")
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(14.dp).fillMaxHeight()){Box(Modifier.align(Alignment.TopCenter).width(1.dp).fillMaxHeight().background(RoomColors.Line));Box(Modifier.align(Alignment.TopCenter).padding(top=9.dp).size(5.dp).background(RoomColors.Muted,RoundedCornerShape(50)))}
        Box(Modifier.weight(1f).clip(androidx.compose.ui.graphics.RectangleShape).testTag("archive-visit-${record.id}")) {
            Row(Modifier.align(Alignment.TopEnd),verticalAlignment=Alignment.CenterVertically){IconButton({onEdit(record)}){Icon(Icons.Outlined.Edit,"编辑到访记录",tint=RoomColors.Navy)};IconButton({remove=true}){Icon(Icons.Outlined.DeleteOutline,"删除感想与照片",tint=RoomColors.Navy)}}
            Column(Modifier.fillMaxWidth().graphicsLayer{translationX=offset}.background(RoomColors.Paper)
                .pointerInput(record.id){detectHorizontalDragGestures(onDragStart={dragging=true;raw=if(opened)-width else 0f},onHorizontalDrag={change,amount->change.consume();raw=(raw+amount).coerceIn(-width,0f)},onDragEnd={opened=raw < -width/2;dragging=false},onDragCancel={dragging=false})}
                .combinedClickable(onClick={if(opened)opened=false else menu=true},onLongClick={menu=true}).padding(start=7.dp,bottom=13.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically){Text(visitDate(record),Modifier.weight(1f),fontSize=13.sp,color=RoomColors.Ink);Text(if(record.visibility=="public")"公开" else "仅自己",fontSize=11.sp,color=RoomColors.Muted);IconButton({menu=true},Modifier.size(36.dp)){Icon(Icons.Outlined.MoreHoriz,"到访记录操作",Modifier.size(19.dp),tint=RoomColors.Muted)}}
                if(record.body.isNotBlank())Text(record.body,fontSize=13.sp,lineHeight=21.sp,color=RoomColors.Ink)
                record.mediaId?.let{id->PrivatePhoto(id,services,Modifier.size(70.dp).clickable(role=Role.Button,onClickLabel="查看到访照片"){photo=true})}
                DropdownMenu(menu,{menu=false}) {DropdownMenuItem({Text("编辑")},{menu=false;onEdit(record)});DropdownMenuItem({Text("删除感想与照片")},{menu=false;remove=true})}
            }
        }
    }
    if(remove)AlertDialog(onDismissRequest={remove=false},title={Text("删除感想与照片？")},text={Text("到访日期与已经获得的认证会保留。")},confirmButton={SwissTextButton({remove=false;onDelete(record)}){Text("删除")}},dismissButton={SwissTextButton({remove=false}){Text("取消")}})
    if(photo&&record.mediaId!=null)Dialog(onDismissRequest={photo=false}) {RoomPaper{Column(Modifier.padding(12.dp)){PrivatePhoto(record.mediaId!!,services,Modifier.fillMaxWidth().heightIn(min=180.dp,max=470.dp));SwissTextButton({photo=false},Modifier.align(Alignment.End)){Text("关闭")}}}}
}

@Composable internal fun RoomUndoNotice(message:String,token:Long,onUndo:()->Unit,onDismiss:()->Unit,modifier:Modifier=Modifier) {
    LaunchedEffect(token){delay(5500);onDismiss()}
    Surface(modifier.testTag("archive-undo-notice").semantics{liveRegion=LiveRegionMode.Polite},color=RoomColors.Navy,shape=androidx.compose.ui.graphics.RectangleShape,shadowElevation=6.dp) {
        Row(Modifier.padding(start=16.dp,end=8.dp,top=7.dp,bottom=7.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            Text(message,Modifier.weight(1f),color=RoomColors.Paper,fontSize=14.sp)
            SwissTonalButton(onUndo,Modifier.testTag("archive-undo-button"),shape=androidx.compose.ui.graphics.RectangleShape,colors=ButtonDefaults.filledTonalButtonColors(containerColor=RoomColors.Paper,contentColor=RoomColors.Navy)){Text("撤销")}
        }
    }
}
