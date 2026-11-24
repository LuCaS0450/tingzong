package cn.musicpilgrimage.app.ui.room

import android.content.Context
import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
    var worldState by mutableStateOf<WorldCameraSnapshot?>(null)
        private set
    var worldController: NativeWorldController? = null
    var worldSelection by mutableStateOf<WorldSelection?>(null); private set
    var worldFocus by mutableStateOf<WorldFocus?>(null); private set
    private var worldFocusToken=0L

    init {
        if (prefs.contains("world-camera-v1")) prefs.edit().remove("world-camera-v1").apply()
    }

    fun world(value: WorldCameraSnapshot) { worldState = value }
    fun restoreWorldState(snapshot: WorldCameraSnapshot) {
        worldState = snapshot
        if (snapshot.selected.isNotBlank()) {
            worldSelection = WorldSelection(snapshot.selected, snapshot.summaryOpen)
        }
    }
    fun focusWorld(id:String,kind:WorldFocusKind=WorldFocusKind.Street) {
        worldSelection=WorldSelection(id,true)
        worldFocus=WorldFocus(id,kind,++worldFocusToken)
    }
    fun holdWorldCameraForDetail() {
        worldController?.holdCameraForDetail(worldFocus?.token)
        worldFocus=null
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

internal val SwissWallStartInset = 26.dp
internal val SwissWallEndInset = 16.dp

@Composable internal fun SwissWallHeader(
    section: Int,
    onSearch: () -> Unit,
    searchEnabled: Boolean = true,
    searchTag: String = "room-search-button",
    beforeSearch: @Composable () -> Unit = {},
) {
    Row(Modifier.fillMaxWidth().height(112.dp).padding(start=SwissWallStartInset,end=SwissWallEndInset),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(9.dp)) {
            Text(listOf("01 / MUSIC","02 / WORLD","03 / PLAY","04 / PERSONAL")[section],color=SwissRed,fontSize=11.sp,letterSpacing=1.8.sp,fontWeight=FontWeight.Medium)
            Text(listOf("唱片架","地图与旅行","演奏","我的空间")[section],fontSize=32.sp,lineHeight=38.sp,fontWeight=FontWeight.Bold,letterSpacing=(-.45).sp,color=SwissInk)
        }
        beforeSearch()
        SwissWallSearchButton(onSearch, searchEnabled, searchTag)
    }
}

@Composable
internal fun SwissWallSearchButton(onSearch: () -> Unit, enabled: Boolean, tag: String) {
    IconButton(onSearch, Modifier.size(48.dp).testTag(tag), enabled = enabled) {
        Icon(Icons.Outlined.Search, "搜索歌曲", Modifier.size(24.dp), tint = RoomColors.Ink)
    }
}

internal val SwissWallTabsHeight=69.dp

@Composable internal fun SwissWallTabs(section:Int,onChoose:(String)->Unit,enabled:Boolean=true,includeTestTags:Boolean=true) {
    val motion=LocalPhysicalMotion.current
    Column(Modifier.fillMaxWidth().height(SwissWallTabsHeight).padding(horizontal=24.dp).padding(bottom=8.dp).then(if(includeTestTags)Modifier.testTag("three-walls-tabs")else Modifier)) {
        Box(Modifier.fillMaxWidth().height(3.dp)) {
            HorizontalDivider(Modifier.align(Alignment.Center),color=Color(0xFFACB5A8),thickness=.5.dp)
            Row(Modifier.fillMaxSize()) {repeat(4){index->Box(Modifier.weight(1f).fillMaxHeight(),contentAlignment=Alignment.Center){if(index==section)Box(Modifier.width(16.dp).height(3.dp).background(SwissRed))}}}
        }
        Row(Modifier.fillMaxWidth().selectableGroup()) {
            val tabs=listOf("音乐" to "room","世界" to "explore","演奏" to "performance","我的" to "me")
            tabs.forEachIndexed { index, (label, route) ->
                val selected=index==section
                val interaction=remember{MutableInteractionSource()}
                val pressed by interaction.collectIsPressedAsState()
                val focused by interaction.collectIsFocusedAsState()
                val hovered by interaction.collectIsHoveredAsState()
                val alpha by animateFloatAsState(
                    when { pressed->.48f; hovered->.78f; else->1f },
                    if(pressed||!motion)snap()else spring(dampingRatio=.85f,stiffness=680f),
                    label="tab-press",
                )
                val iconTint = if (selected) SwissInk else SwissWallMuted
                Box(
                    Modifier.weight(1f).height(58.dp).then(if(includeTestTags)Modifier.testTag("wall-tab-$index")else Modifier)
                        .semantics { contentDescription = label }
                        .selectable(selected,interactionSource=interaction,indication=null,enabled=enabled,role=Role.Tab){onChoose(route)},
                    contentAlignment=Alignment.Center,
                ) {
                    SwissWallTabIcon(
                        index = index,
                        tint = iconTint,
                        focused = focused,
                        modifier = Modifier
                            .size(24.dp)
                            .graphicsLayer {
                                this.alpha = alpha.coerceIn(0f, 1f)
                                compositingStrategy = CompositingStrategy.ModulateAlpha
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun SwissWallTabIcon(index: Int, tint: Color, focused: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val strokeWidth = 1.8.dp.toPx()
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val u = size.width / 24f
        when (index) {
            // 0: 音乐 - 双八分音符 (Beamed eighth notes)
            0 -> {
                val beamLeft = Offset(9.2f * u, 5.2f * u)
                val beamRight = Offset(19.2f * u, 3.8f * u)
                drawLine(tint, beamLeft, beamRight, strokeWidth = strokeWidth, cap = StrokeCap.Round)
                drawLine(tint, Offset(9.2f * u, 5.2f * u), Offset(9.2f * u, 16.5f * u), strokeWidth = strokeWidth, cap = StrokeCap.Round)
                drawLine(tint, Offset(19.2f * u, 3.8f * u), Offset(19.2f * u, 15.0f * u), strokeWidth = strokeWidth, cap = StrokeCap.Round)
                rotate(degrees = -18f, pivot = Offset(6.8f * u, 17.5f * u)) {
                    drawOval(
                        color = tint,
                        topLeft = Offset(4.1f * u, 15.3f * u),
                        size = Size(5.4f * u, 4.2f * u),
                        style = stroke,
                    )
                }
                rotate(degrees = -18f, pivot = Offset(16.8f * u, 16.0f * u)) {
                    drawOval(
                        color = tint,
                        topLeft = Offset(14.1f * u, 13.8f * u),
                        size = Size(5.4f * u, 4.2f * u),
                        style = stroke,
                    )
                }
            }
            // 1: 世界 - 地图图钉 (Map pin)
            1 -> {
                val pinPath = Path().apply {
                    moveTo(12f * u, 20.6f * u)
                    cubicTo(12f * u, 20.6f * u, 18.2f * u, 14.3f * u, 18.2f * u, 9.6f * u)
                    arcTo(
                        rect = Rect(5.8f * u, 3.4f * u, 18.2f * u, 15.8f * u),
                        startAngleDegrees = 0f,
                        sweepAngleDegrees = -180f,
                        forceMoveTo = false,
                    )
                    cubicTo(5.8f * u, 14.3f * u, 12f * u, 20.6f * u, 12f * u, 20.6f * u)
                    close()
                }
                drawPath(pinPath, color = tint, style = stroke)
                drawCircle(
                    color = tint,
                    radius = 2.4f * u,
                    center = Offset(12f * u, 9.6f * u),
                    style = stroke,
                )
            }
            // 2: 演奏 - 四轨节拍条 (Four rhythm lanes)
            2 -> {
                val laneX = listOf(4.2f * u, 9.4f * u, 14.6f * u, 19.8f * u)
                val barTops = listOf(9.5f * u, 4.5f * u, 12.0f * u, 6.5f * u)
                val barBottoms = listOf(18.5f * u, 14.5f * u, 19.5f * u, 17.0f * u)
                for (i in 0..3) {
                    drawLine(
                        color = tint.copy(alpha = 0.28f),
                        start = Offset(laneX[i], 3.5f * u),
                        end = Offset(laneX[i], 20.5f * u),
                        strokeWidth = 1.0.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = tint,
                        start = Offset(laneX[i], barTops[i]),
                        end = Offset(laneX[i], barBottoms[i]),
                        strokeWidth = 2.1.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }
            // 3: 我的 - 个人头像 (Minimal profile silhouette)
            else -> {
                drawCircle(
                    color = tint,
                    radius = 3.6f * u,
                    center = Offset(12f * u, 7.6f * u),
                    style = stroke,
                )
                val shoulderPath = Path().apply {
                    moveTo(5.2f * u, 19.6f * u)
                    cubicTo(5.2f * u, 15.6f * u, 8.2f * u, 13.8f * u, 12f * u, 13.8f * u)
                    cubicTo(15.8f * u, 13.8f * u, 18.8f * u, 15.6f * u, 18.8f * u, 19.6f * u)
                }
                drawPath(shoulderPath, color = tint, style = stroke)
            }
        }
        if (focused) {
            drawRoundRect(
                color = tint.copy(alpha = 0.45f),
                topLeft = Offset(-2.dp.toPx(), -2.dp.toPx()),
                size = Size(size.width + 4.dp.toPx(), size.height + 4.dp.toPx()),
                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                style = Stroke(width = 1.dp.toPx()),
            )
        }
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
    RoomBauhausPianoWall(
        entries = entries,
        loaded = loaded,
        browse = browse,
        downloads = downloads,
        onSong = onSong,
        onRetry = onRetry,
    )
}
