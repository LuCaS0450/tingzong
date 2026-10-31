package cn.musicpilgrimage.app.ui.room

import android.content.Context
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.musicpilgrimage.app.data.*
import cn.musicpilgrimage.app.performance.*
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.max
import kotlin.math.min
import org.json.JSONObject

internal val SwissRed=RoomColors.Red
internal val SwissInk=RoomColors.Ink

/** Browsing memory is independent of navigation: cold launches still start at Music. */
@Stable internal class RoomBrowseState(context:Context) {
    private val prefs=context.applicationContext.getSharedPreferences("three-walls-browsing",Context.MODE_PRIVATE)
    var shelfPage by mutableIntStateOf(prefs.getInt("shelf-page",0)); private set
    var hintSeen=prefs.getBoolean("shelf-hint",false); private set
    var globe by mutableStateOf(GlobeCamera(prefs.getFloat("longitude",35f),prefs.getFloat("latitude",24f),prefs.getFloat("globe-zoom",1f)).bounded()); private set
    var street by mutableStateOf(prefs.getBoolean("street",false)); private set
    var selected by mutableStateOf(prefs.getString("place","").orEmpty()); private set
    var streetCamera by mutableStateOf(prefs.getString("street-camera",null)?.split(',')?.mapNotNull(String::toDoubleOrNull)?.takeIf{it.size==3&&it.all(Double::isFinite)}); private set
    var playIndex=prefs.getInt("play-index",0); private set
    var playOffset=prefs.getInt("play-offset",0); private set
    var worldState:WorldCameraSnapshot?=runCatching{WorldCameraSnapshot.parse(JSONObject(prefs.getString("world-camera-v1",null)?:"{}"))}.getOrNull(); private set
    var worldScene:WorldScene?=null
    private var worldFocus:String?=null
    fun world(value:WorldCameraSnapshot){if(worldState==value)return;worldState=value;prefs.edit().putString("world-camera-v1",value.json().toString()).apply()}
    fun focusWorld(id:String){worldFocus=id}
    fun takeWorldFocus():String?=worldFocus.also{worldFocus=null}
    fun page(value:Int){shelfPage=value.coerceAtLeast(0);prefs.edit().putInt("shelf-page",shelfPage).apply()}
    fun sawHint(){hintSeen=true;prefs.edit().putBoolean("shelf-hint",true).apply()}
    fun globe(value:GlobeCamera){globe=value.bounded();prefs.edit().putFloat("longitude",globe.longitude).putFloat("latitude",globe.latitude).putFloat("globe-zoom",globe.zoom).apply()}
    fun select(id:String){selected=id;prefs.edit().putString("place",id).apply()}
    fun street(value:Boolean){street=value;prefs.edit().putBoolean("street",value).apply()}
    fun camera(value:List<Double>){
        if(value.size!=3||value.any{!it.isFinite()}||value[0] !in -90.0..90.0||value[1] !in -180.0..180.0||value[2] !in 0.0..24.0)return
        streetCamera=value.toList();prefs.edit().putString("street-camera",value.joinToString(",")).apply()
    }
    fun openStreet(place:Place){select(place.id);camera(listOf(place.latitude,place.longitude,15.0));street(true)}
    fun play(index:Int,offset:Int){playIndex=index;playOffset=offset;prefs.edit().putInt("play-index",index).putInt("play-offset",offset).apply()}
}

@Composable internal fun SwissWallHeader(section:Int,onSearch:()->Unit,onSpace:()->Unit,onOrigin:(Offset)->Unit) {
    Row(Modifier.fillMaxWidth().height(112.dp).padding(start=26.dp,end=16.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(9.dp)) {
            Text(listOf("01 / MUSIC","02 / WORLD","03 / PLAY","04 / PERSONAL")[section],color=SwissRed,fontSize=11.sp,letterSpacing=1.8.sp,fontWeight=FontWeight.Medium)
            Text(listOf("唱片架","地图与旅行","演奏","我的空间")[section],fontSize=32.sp,lineHeight=38.sp,fontWeight=FontWeight.Bold,color=SwissInk)
        }
        IconButton(onSearch,Modifier.size(48.dp).testTag("room-search-button")){Icon(Icons.Outlined.Search,"搜索歌曲",Modifier.size(24.dp),tint=RoomColors.Ink)}
    }
}

@Composable internal fun SwissWallTabs(section:Int,onChoose:(String)->Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal=24.dp).padding(bottom=8.dp).testTag("three-walls-tabs")) {
        Box(Modifier.fillMaxWidth().height(3.dp)) {
            HorizontalDivider(Modifier.align(Alignment.Center),color=Color(0xFFACB5A8),thickness=.5.dp)
            Row(Modifier.fillMaxSize()) {repeat(4){index->Box(Modifier.weight(1f).fillMaxHeight(),contentAlignment=Alignment.Center){if(index==section)Box(Modifier.width(16.dp).height(3.dp).background(SwissRed))}}}
        }
        Row(Modifier.fillMaxWidth()) {listOf("音乐","世界","演奏","我的").forEachIndexed{index,label->
            Box(Modifier.weight(1f).height(58.dp).semantics{selected=index==section}.testTag("wall-tab-$index")
                .clickable(role=Role.Tab){if(index!=section)onChoose(listOf("room","explore","performance","me")[index])},contentAlignment=Alignment.Center) {
                Text(label,color=if(index==section)SwissInk else Color(0xFF647266),fontSize=16.sp,fontWeight=if(index==section)FontWeight.Bold else FontWeight.Normal)
            }
        }}
    }
}

@Composable internal fun SwissRecordWall(catalog:MusicCatalogue,browse:RoomBrowseState,onRelease:(MusicRelease)->Unit) {
    val count=max(1,(catalog.releases.size+5)/6)
    val pager=rememberPagerState(initialPage=browse.shelfPage.coerceIn(0,count-1)){count}
    var showHint by remember{mutableStateOf(!browse.hintSeen)}
    LaunchedEffect(Unit){browse.sawHint()}
    LaunchedEffect(pager){snapshotFlow{pager.settledPage}.distinctUntilChanged().collect{page->if(page!=browse.shelfPage)showHint=false;browse.page(page)}}
    Column(Modifier.fillMaxSize().testTag("room-catalogue")) {
        HorizontalPager(pager,Modifier.fillMaxWidth().weight(1f).testTag("shelf-pager"),key={it},beyondViewportPageCount=1) {page->
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val u=min(maxWidth.value/360f,(maxHeight.value-8f)/542f).dp
                Box(Modifier.width(u*360f).height(u*539f).align(Alignment.Center)) {
                    RoomRecordRack(Modifier.offset(x=u*11f).width(u*338f).height(u*539f),listOf(151f,336f,521f))
                    catalog.releases.drop(page*6).take(6).forEachIndexed{index,release->
                        Box(Modifier.offset(x=u*(31f+157f*(index%2)),y=u*(10f+185f*(index/2))).size(u*141f)
                            .testTag("room-release-${release.id}").clickable(role=Role.Button,onClickLabel="打开${release.title}专辑"){onRelease(release)}) {
                            RoomCover(release.artwork,"${release.title} · ${release.artist}",Modifier.fillMaxSize(),sleeve=true)
                        }
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().height(39.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
            if(showHint&&count>1)Text("左右滑动查看更多",fontSize=11.sp,color=RoomColors.Muted,letterSpacing=1.sp,modifier=Modifier.padding(bottom=9.dp).testTag("shelf-swipe-hint"))
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
                        Text(entry.song.title,color=SwissInk,fontSize=17.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(entry.song.artist,color=RoomColors.Muted,fontSize=12.sp)
                        val duration="${entry.song.durationUs/60_000_000}:${(entry.song.durationUs/1_000_000%60).toString().padStart(2,'0')}"
                        Text(if(entry.audioReady)"$duration · 离线就绪" else status?.label?:"$duration · 等待自动准备",color=if(status?.failed==true)SwissRed else RoomColors.Muted,fontSize=11.sp,maxLines=1)
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
