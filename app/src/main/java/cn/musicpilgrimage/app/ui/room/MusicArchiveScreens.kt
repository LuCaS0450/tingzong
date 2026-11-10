package cn.musicpilgrimage.app.ui.room

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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import cn.musicpilgrimage.app.MusicArchiveState
import cn.musicpilgrimage.app.data.*
import cn.musicpilgrimage.app.ui.LocalPhysicalMotion
import cn.musicpilgrimage.app.ui.PrivatePhoto
import kotlinx.coroutines.delay

@Composable internal fun RoomDrawerFrame(modifier:Modifier=Modifier,content:@Composable BoxScope.()->Unit) {
    Box(modifier.padding(horizontal=24.dp,vertical=8.dp),content=content)
}

@Composable private fun ArchiveTabs(selected:String,entries:List<Pair<String,String>>,onSelect:(String)->Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal=24.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        entries.forEach{(id,title)->
            val active=id==selected
            Column(horizontalAlignment=Alignment.CenterHorizontally) {
                SwissTextButton({onSelect(id)},Modifier.heightIn(min=44.dp).testTag("archive-tab-$id").semantics{this.selected=active}) {
                    Text(title,color=if(active)RoomColors.Ink else RoomColors.Muted,fontSize=14.sp)
                }
                Box(Modifier.width(22.dp).height(2.dp).background(if(active)RoomColors.Red else Color.Transparent))
            }
        }
    }
}

@Composable internal fun RoomArchive(
    initialKind:String,music:@Composable (String)->Unit,places:@Composable ()->Unit,footprints:@Composable ()->Unit
) {
    var kind by rememberSaveable(initialKind) {mutableStateOf(initialKind)}
    val tabState=rememberSaveableStateHolder()
    Column(Modifier.fillMaxSize().testTag("room-archive")) {
        ArchiveTabs(kind,listOf("songs" to "歌曲","releases" to "专辑","places" to "地点","footprints" to "足迹")){kind=it}
        Box(Modifier.fillMaxWidth().weight(1f)) {
            tabState.SaveableStateProvider(kind) {
                when(kind) {
                    "places"->places()
                    "footprints"->footprints()
                    else->music(kind)
                }
            }
        }
    }
}

@Composable internal fun RoomMusicFavorites(catalog:MusicCatalogue,favorites:MusicFavorites,kind:String,onWork:(MusicWork)->Unit,onRelease:(MusicRelease)->Unit,onRemove:(String,Boolean)->Unit,onDiscover:()->Unit,sync:PersonalLibrarySyncState,onSync:()->Unit) {
    val release=kind=="releases"
    val ids=if(release)favorites.releases else favorites.songs
    val motion=LocalPhysicalMotion.current
    Column(Modifier.fillMaxSize().testTag("room-music-favorites")) {
        RoomLibrarySyncStatus(sync.takeIf{it.owner==favorites.owner}?:PersonalLibrarySyncState(),onSync,Modifier.padding(horizontal=24.dp))
        RoomDrawerFrame(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize().testTag("archive-music-list"),contentPadding=PaddingValues(7.dp,12.dp,7.dp,88.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
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
    archive:MusicArchiveState,places:List<Place>,onPlace:(String)->Unit,onRemove:(String)->Unit,
    onLogin:()->Unit,onReload:()->Unit
) {
    RoomDrawerFrame(Modifier.fillMaxSize().testTag("room-place-favorites")) {
        LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(7.dp,12.dp,7.dp,88.dp),
            verticalArrangement=Arrangement.spacedBy(11.dp)) {
            if(archive.owner==null)item {ArchiveLogin("地点收藏",onLogin)}
            else {
                item {Text("${archive.places.size} 个收藏地点",fontSize=12.sp,color=RoomColors.Muted)}
                archiveStatus(archive,onReload)
                if(archive.places.isEmpty()&&!archive.loading&&archive.error==null)item {
                    Text("还没有收藏地点，在作品或地图中发现喜欢的地方。",fontSize=14.sp,lineHeight=23.sp,color=RoomColors.Muted)
                }
                items(archive.places,key={it}) {id->
                    val place=places.find{it.id==id}
                    RoomPaper(Modifier.fillMaxWidth().animateItem().testTag("archive-place-$id")) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Row(Modifier.weight(1f).clickable(role=Role.Button){onPlace(id)}
                                .padding(start=11.dp,top=12.dp,bottom=12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),
                                verticalAlignment=Alignment.CenterVertically) {
                                ArchivePlaceImage(place,Modifier.width(90.dp).height(66.dp))
                                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                    Text(place?.name?:"地点暂未收录",maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=16.sp,color=RoomColors.Ink)
                                    Text(listOfNotNull(place?.city,place?.country).joinToString(" · "),fontSize=12.sp,
                                        color=RoomColors.Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
                                }
                            }
                            IconButton({onRemove(id)},Modifier.testTag("archive-remove-place-$id")) {
                                Icon(Icons.Outlined.Favorite,"取消地点收藏",tint=RoomColors.Red,modifier=Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable internal fun RoomFootprints(
    archive:MusicArchiveState,places:List<Place>,initialTab:String,onPlace:(String)->Unit,onLogin:()->Unit,
    onReload:()->Unit,onEdit:(ContentItem)->Unit,onDelete:(ContentItem)->Unit,services:AppServices,
    records:@Composable (String)->Unit
) {
    var tab by rememberSaveable(initialTab) {mutableStateOf(initialTab)}
    val visitScroll=rememberLazyListState()
    val tabState=rememberSaveableStateHolder()
    Column(Modifier.fillMaxSize().testTag("room-footprints")) {
        ArchiveTabs(tab,listOf("visits" to "到访","recreations" to "复刻","messages" to "留言")){tab=it}
        if(archive.owner==null)Box(Modifier.padding(horizontal=24.dp)) {
            ArchiveLogin("足迹",onLogin)
        }
        else tabState.SaveableStateProvider(tab) {
            if(tab!="visits")records(if(tab=="messages")"message" else "recreation")
            else RoomDrawerFrame(Modifier.weight(1f)) {
                val grouped=archive.visits.groupBy{it.location}.entries.sortedByDescending{(_,rows)->rows.maxOf{visitDate(it)}}
                LazyColumn(Modifier.fillMaxSize(),state=visitScroll,contentPadding=PaddingValues(7.dp,12.dp,7.dp,88.dp),
                    verticalArrangement=Arrangement.spacedBy(11.dp)) {
                    item {Text("${grouped.size} 个到访地点",fontSize=12.sp,color=RoomColors.Muted)}
                    archiveStatus(archive,onReload)
                    if(grouped.isEmpty()&&!archive.loading&&archive.error==null)item {
                        Text("还没有到访记录，到访地点后可以留下记录。",fontSize=14.sp,lineHeight=23.sp,color=RoomColors.Muted)
                    }
                    items(grouped,key={it.key}) {(id,records)->
                        FootprintCard(places.find{it.id==id},records,{onPlace(id)},onEdit,onDelete,services)
                    }
                }
            }
        }
    }
}

private fun LazyListScope.archiveStatus(archive:MusicArchiveState,onReload:()->Unit) {
    if(archive.loading)item {LinearProgressIndicator(Modifier.fillMaxWidth(),color=RoomColors.Navy)}
    if(archive.error!=null)item {
        Column {
            Text(archive.error,color=MaterialTheme.colorScheme.error,fontSize=13.sp)
            SwissTextButton(onReload){Text("重试")}
        }
    }
}

@Composable private fun ArchiveLogin(section:String,onLogin:()->Unit) {
    Column(Modifier.padding(vertical=18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("登录查看$section",color=RoomColors.Ink)
        Text("已有收藏和足迹会保留在你的账号中。",fontSize=14.sp,color=RoomColors.Muted)
        SwissButton(onLogin,shape=androidx.compose.ui.graphics.RectangleShape){Text("登录")}
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
    LaunchedEffect(token){delay(3500);onDismiss()}
    Surface(modifier.testTag("archive-undo-notice").semantics{liveRegion=LiveRegionMode.Polite},color=RoomColors.Navy,shape=androidx.compose.ui.graphics.RectangleShape,shadowElevation=6.dp) {
        Row(Modifier.padding(start=16.dp,end=8.dp,top=7.dp,bottom=7.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            Text(message,Modifier.weight(1f),color=RoomColors.Paper,fontSize=14.sp)
            SwissTonalButton(onUndo,Modifier.testTag("archive-undo-button"),shape=androidx.compose.ui.graphics.RectangleShape,colors=ButtonDefaults.filledTonalButtonColors(containerColor=RoomColors.Paper,contentColor=RoomColors.Navy)){Text("撤销")}
        }
    }
}
