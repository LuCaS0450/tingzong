package cn.musicpilgrimage.app.ui.room

import android.content.Context
import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.musicpilgrimage.app.data.*
import cn.musicpilgrimage.app.performance.*
import cn.musicpilgrimage.app.ui.LocalCoverCoordinator
import cn.musicpilgrimage.app.ui.LocalPhysicalMotion
import cn.musicpilgrimage.app.ui.LocalRouteEntryId
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.tanh
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.max
import kotlin.math.min

internal val SwissRed=RoomColors.Red
internal val SwissInk=RoomColors.Ink
internal val SwissWallMuted=cn.musicpilgrimage.app.design.AppArt.WallMuted

/** Browsing memory is independent of navigation: cold launches still start at Music. */
@Stable internal class RoomBrowseState(context:Context) {
    private val prefs=context.applicationContext.getSharedPreferences("three-walls-browsing",Context.MODE_PRIVATE)
    var shelfPage by mutableIntStateOf(prefs.getInt("shelf-page",0)); private set
    var hintSeen=prefs.getBoolean("shelf-hint",false); private set
    var street by mutableStateOf(prefs.getBoolean("street",false)); private set
    var selected by mutableStateOf(prefs.getString("place","").orEmpty()); private set
    var streetCamera by mutableStateOf(prefs.getString("street-camera",null)?.split(',')?.mapNotNull(String::toDoubleOrNull)?.takeIf{it.size==3&&it.all(Double::isFinite)}); private set
    var playIndex=prefs.getInt("play-index",0); private set
    var playOffset=prefs.getInt("play-offset",0); private set
    var worldState: WorldCameraSnapshot? = null
        private set
    var worldScene:WorldScene?=null
    var worldSelection by mutableStateOf<WorldSelection?>(null); private set
    var worldFocus by mutableStateOf<WorldFocus?>(null); private set
    private var worldFocusToken=0L

    init {
        if (prefs.contains("world-camera-v1")) prefs.edit().remove("world-camera-v1").apply()
    }

    fun world(value: WorldCameraSnapshot) { worldState = value }
    fun focusWorld(id:String,kind:WorldFocusKind=WorldFocusKind.Street) {
        worldSelection=WorldSelection(id,true)
        worldFocus=WorldFocus(id,kind,++worldFocusToken)
    }
    fun closeWorldSummary(){worldSelection=worldSelection?.copy(summaryOpen=false)}
    fun reopenWorldSummary(id:String){if(worldSelection?.placeId==id)worldSelection=WorldSelection(id,true)}
    fun page(value:Int){shelfPage=value.coerceAtLeast(0);prefs.edit().putInt("shelf-page",shelfPage).apply()}
    fun sawHint(){hintSeen=true;prefs.edit().putBoolean("shelf-hint",true).apply()}
    fun select(id:String){selected=id;prefs.edit().putString("place",id).apply()}
    fun street(value:Boolean){street=value;prefs.edit().putBoolean("street",value).apply()}
    fun camera(value:List<Double>){
        if(value.size!=3||value.any{!it.isFinite()}||value[0] !in -90.0..90.0||value[1] !in -180.0..180.0||value[2] !in 0.0..24.0)return
        streetCamera=value.toList();prefs.edit().putString("street-camera",value.joinToString(",")).apply()
    }
    fun openStreet(place:Place){select(place.id);camera(listOf(place.latitude,place.longitude,15.0));street(true)}
    fun play(index:Int,offset:Int){playIndex=index;playOffset=offset;prefs.edit().putInt("play-index",index).putInt("play-offset",offset).apply()}
}

@Composable internal fun SwissWallHeader(section:Int,onSearch:()->Unit) {
    Row(Modifier.fillMaxWidth().height(112.dp).padding(start=26.dp,end=16.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(9.dp)) {
            Text(listOf("01 / MUSIC","02 / WORLD","03 / PLAY","04 / PERSONAL")[section],color=SwissRed,fontSize=11.sp,letterSpacing=1.8.sp,fontWeight=FontWeight.Medium)
            Text(listOf("唱片架","地图与旅行","演奏","我的空间")[section],fontSize=32.sp,lineHeight=38.sp,fontWeight=FontWeight.Bold,letterSpacing=(-.45).sp,color=SwissInk)
        }
        IconButton(onSearch,Modifier.size(48.dp).testTag("room-search-button")){Icon(Icons.Outlined.Search,"搜索歌曲",Modifier.size(24.dp),tint=RoomColors.Ink)}
    }
}

internal val SwissWallTabsHeight=69.dp

@Composable internal fun SwissWallTabs(section:Int,onChoose:(String)->Unit,enabled:Boolean=true,includeTestTags:Boolean=true) {
    Column(Modifier.fillMaxWidth().height(SwissWallTabsHeight).padding(horizontal=24.dp).padding(bottom=8.dp).then(if(includeTestTags)Modifier.testTag("three-walls-tabs")else Modifier)) {
        Box(Modifier.fillMaxWidth().height(3.dp)) {
            HorizontalDivider(Modifier.align(Alignment.Center),color=Color(0xFFACB5A8),thickness=.5.dp)
            Row(Modifier.fillMaxSize()) {repeat(4){index->Box(Modifier.weight(1f).fillMaxHeight(),contentAlignment=Alignment.Center){if(index==section)Box(Modifier.width(16.dp).height(3.dp).background(SwissRed))}}}
        }
        Row(Modifier.fillMaxWidth()) {listOf("音乐","世界","演奏","我的").forEachIndexed{index,label->
            Box(Modifier.weight(1f).height(58.dp).semantics{selected=index==section}.then(if(includeTestTags)Modifier.testTag("wall-tab-$index")else Modifier)
                .clickable(enabled=enabled,role=Role.Tab){onChoose(listOf("room","explore","performance","me")[index])},contentAlignment=Alignment.Center) {
                Text(label,color=if(index==section)SwissInk else SwissWallMuted,fontSize=16.sp,fontWeight=if(index==section)FontWeight.Bold else FontWeight.Normal)
            }
        }}
    }
}

@Composable internal fun SwissRecordWall(catalog:MusicCatalogue,browse:RoomBrowseState,onRelease:(MusicRelease)->Unit) {
    val count=max(1,(catalog.releases.size+5)/6)
    val pager=rememberPagerState(initialPage=browse.shelfPage.coerceIn(0,count-1)){count}
    val coordinator=LocalCoverCoordinator.current
    val routeEntryId=LocalRouteEntryId.current
    val orientation=LocalConfiguration.current.orientation
    val motion=LocalPhysicalMotion.current
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val maxStretchPx=with(LocalDensity.current){24.dp.toPx()}
    val rawOverscroll=remember{mutableFloatStateOf(0f)}
    val springOverscroll=remember{Animatable(0f)}
    var pagerLayoutVersion by remember{mutableLongStateOf(0L)}
    val overscrollConnection=remember(pager,motion,maxStretchPx) {
        object:NestedScrollConnection {
            override fun onPostScroll(consumed:Offset,available:Offset,source:NestedScrollSource):Offset {
                if(!motion||source!=NestedScrollSource.UserInput||available.x==0f)return Offset.Zero
                val atStart=!pager.canScrollBackward&&available.x>0f
                val atEnd=!pager.canScrollForward&&available.x<0f
                if(atStart||atEnd||rawOverscroll.floatValue!=0f) {
                    val next=(rawOverscroll.floatValue+available.x*0.35f).coerceIn(-maxStretchPx*3f,maxStretchPx*3f)
                    rawOverscroll.floatValue=next
                    val damped=maxStretchPx*tanh((next/maxStretchPx).toDouble()).toFloat()
                    scope.launch{springOverscroll.snapTo(damped)}
                    return Offset(available.x,0f)
                }
                return Offset.Zero
            }
            override suspend fun onPostFling(consumed:Velocity,available:Velocity):Velocity {
                rawOverscroll.floatValue=0f
                if(springOverscroll.value!=0f)springOverscroll.animateTo(0f,spring(dampingRatio=0.78f,stiffness=420f))
                return Velocity.Zero
            }
        }
    }
    var showHint by remember{mutableStateOf(!browse.hintSeen)}
    LaunchedEffect(Unit){browse.sawHint()}
    LaunchedEffect(pager){snapshotFlow{pager.settledPage}.distinctUntilChanged().collect{page->if(page!=browse.shelfPage)showHint=false;browse.page(page)}}
    LaunchedEffect(coordinator,routeEntryId,pager.settledPage,pagerLayoutVersion) {
        coordinator?.updateShelfState(routeEntryId,pager.settledPage,pagerLayoutVersion)
    }
    Column(Modifier.fillMaxSize().testTag("room-catalogue")) {
        HorizontalPager(pager,Modifier.fillMaxWidth().weight(1f).nestedScroll(overscrollConnection)
            .onSizeChanged { size ->
                pagerLayoutVersion=(orientation.toLong() shl 48) or (size.width.toLong() shl 24) or size.height.toLong()
            }
            .graphicsLayer{
            translationX=if(motion)springOverscroll.value else 0f
        }.testTag("shelf-pager"),key={it},beyondViewportPageCount=1) {page->
            val pageFraction by remember(page,pager) {
                derivedStateOf { abs((pager.currentPage-page)+pager.currentPageOffsetFraction).coerceIn(0f,1f) }
            }
            // Reuse a rendered page during horizontal motion; preserve the rack/cover movement together.
            BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer {
                compositingStrategy=CompositingStrategy.Offscreen
                alpha=if(motion)1f-0.14f*pageFraction else 1f
            }) {
                val u=min(maxWidth.value/360f,(maxHeight.value-8f)/542f).dp
                Box(Modifier.width(u*360f).height(u*539f).align(Alignment.Center)) {
                    RoomRecordRack(Modifier.offset(x=u*11f).width(u*338f).height(u*539f),listOf(151f,336f,521f))
                    catalog.releases.drop(page*6).take(6).forEachIndexed{index,release->
                        val coverKey="release:${release.id}"
                        val coverModel=remember(release.artwork){releaseCoverModel(context,release.artwork)}
                        val hidden=coordinator?.isSourceHidden(coverKey,ownerEntryId=routeEntryId,page=page)==true
                        var imageState by remember(coverKey,coverModel){mutableStateOf<Pair<Boolean,String?>>(false to null)}
                        var coverBitmap by remember(coverKey,coverModel){mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)}
                        var cellBounds by remember(coverKey,page){mutableStateOf<Rect?>(null)}
                        val cellVisible=(page==pager.settledPage)&&abs(pager.currentPageOffsetFraction)<0.03f&&abs(springOverscroll.value)<2f
                        LaunchedEffect(coordinator,coverKey,coverModel,imageState,coverBitmap,cellBounds,cellVisible,page,pagerLayoutVersion,orientation,routeEntryId) {
                            val bounds=cellBounds?:return@LaunchedEffect
                            coordinator?.updateShelfState(routeEntryId,pager.settledPage,pagerLayoutVersion)
                            coordinator?.updateSource(
                                key=coverKey,
                                model=coverModel,
                                aspectRatio=release.artwork?.aspectRatio?:1f,
                                bounds=bounds,
                                sleeve=true,
                                visible=cellVisible,
                                ownerEntryId=routeEntryId,
                                page=page,
                                layoutVersion=pagerLayoutVersion,
                                orientation=orientation,
                                memoryCacheKey=imageState.second,
                                imageLoaded=imageState.first,
                                coverBitmap=coverBitmap,
                            )
                        }
                        Box(Modifier.offset(x=u*(31f+157f*(index%2)),y=u*(10f+185f*(index/2))).size(u*141f)
                            .onGloballyPositioned { coords ->
                                val bounds=coords.boundsInRoot()
                                cellBounds=bounds
                                coordinator?.updateShelfState(routeEntryId,pager.settledPage,pagerLayoutVersion)
                                coordinator?.updateSource(
                                    key=coverKey,
                                    model=coverModel,
                                    aspectRatio=release.artwork?.aspectRatio?:1f,
                                    bounds=bounds,
                                    sleeve=true,
                                    visible=cellVisible,
                                    ownerEntryId=routeEntryId,
                                    page=page,
                                    layoutVersion=pagerLayoutVersion,
                                    orientation=orientation,
                                    memoryCacheKey=imageState.second,
                                    imageLoaded=imageState.first,
                                    coverBitmap=coverBitmap,
                                )
                            }
                            .graphicsLayer{alpha=if(hidden)0f else 1f}
                            .testTag("room-release-${release.id}").clickable(role=Role.Button,onClickLabel="打开${release.title}专辑"){onRelease(release)}) {
                            RoomCover(release.artwork,"${release.title} · ${release.artist}",Modifier.fillMaxSize(),sleeve=true,archiveResolution=true) { loaded, memKey, bitmap ->
                                coverBitmap=bitmap
                                imageState=(loaded&&bitmap!=null) to memKey
                            }
                        }
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().height(39.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
            if(showHint&&count>1)Text("左右滑动查看更多",fontSize=11.sp,color=SwissWallMuted,letterSpacing=1.sp,modifier=Modifier.padding(bottom=9.dp).testTag("shelf-swipe-hint"))
            Canvas(Modifier.width(66.dp).height(2.dp).testTag("shelf-progress")) {
                drawLine(RoomColors.Line,Offset.Zero,Offset(size.width,0f),size.height)
                val segment=size.width/count
                val position=(pager.currentPage+pager.currentPageOffsetFraction).coerceIn(0f,(count-1).toFloat())
                drawLine(SwissRed,Offset(segment*position,0f),Offset(segment*(position+1),0f),size.height)
            }
        }
    }
}

@Composable internal fun SwissPlayWall(entries:List<PerformanceEntry>,artwork:(SongPackage)->ReleaseArtwork?,loaded:Boolean,browse:RoomBrowseState,
    downloads:Map<String,AudioDownloadState>,onSong:(String)->Unit,onRetry:()->Unit) {
    val list=rememberLazyListState(browse.playIndex.coerceAtLeast(0),browse.playOffset.coerceAtLeast(0))
    LaunchedEffect(list){snapshotFlow{list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset}.distinctUntilChanged().collect{(index,offset)->browse.play(index,offset)}}
    LazyColumn(Modifier.fillMaxSize().testTag("performance-list"),state=list,contentPadding=PaddingValues(26.dp,0.dp,26.dp,24.dp)) {
        item("piano") {
            AsyncImage(roomAsset("piano-front","png"),"完整正视炭黑缎面立式钢琴",Modifier.fillMaxWidth().aspectRatio(1.5f).padding(top=4.dp,bottom=14.dp),contentScale=ContentScale.Fit)
        }
        item("title") {Row(Modifier.fillMaxWidth().padding(top=10.dp,bottom=18.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("可演奏曲目",Modifier.weight(1f),fontSize=19.sp,fontWeight=FontWeight.Medium,color=SwissInk)
            Text("${entries.size.toString().padStart(2,'0')} TRACKS",fontSize=10.sp,letterSpacing=1.sp,color=SwissRed)
        }}
        if(!loaded)item("loading"){CircularProgressIndicator(Modifier.size(24.dp),color=SwissInk)}
        items(entries,key={it.song.id}){entry->
            val status=downloads[entry.song.id]
            Column(Modifier.fillMaxWidth().testTag("performance-song-${entry.song.id}").clickable(role=Role.Button){onSong(entry.song.id)}) {
                Row(Modifier.fillMaxWidth().padding(vertical=14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                    RoomCover(artwork(entry.song),entry.song.title,Modifier.size(62.dp))
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                        Text(entry.song.title,color=SwissInk,fontSize=17.sp,lineHeight=21.sp,fontWeight=FontWeight.SemiBold,
                            letterSpacing=(-.2).sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                        Text(entry.song.artist,color=SwissWallMuted,fontSize=12.sp)
                        val duration="${entry.song.durationUs/60_000_000}:${(entry.song.durationUs/1_000_000%60).toString().padStart(2,'0')}"
                        Text(if(entry.audioReady)"$duration · 离线就绪" else status?.label?:"$duration · 等待自动准备",color=if(status?.failed==true)SwissRed else SwissWallMuted,fontSize=11.sp,letterSpacing=.15.sp,maxLines=1)
                    }
                    Icon(Icons.Outlined.PlayArrow,"演奏${entry.song.title}",Modifier.size(22.dp),tint=SwissInk)
                }
                if(!entry.audioReady&&status?.progress!=null)LinearProgressIndicator(progress={status.progress},Modifier.fillMaxWidth().height(1.dp),color=SwissRed,trackColor=RoomColors.Line)
                HorizontalDivider(color=RoomColors.Line.copy(alpha=.7f),thickness=.5.dp)
            }
        }
        if(downloads.values.any{it.failed})item("retry"){SwissTextButton(onRetry,Modifier.padding(top=12.dp)){Text("重试未完成曲目",color=SwissRed)}}
    }
}
