package cn.musicpilgrimage.app.performance

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.musicpilgrimage.app.data.ReleaseArtwork
import cn.musicpilgrimage.app.ui.LocalPhysicalMotion
import cn.musicpilgrimage.app.ui.room.*
import coil.compose.AsyncImage
import kotlinx.coroutines.isActive

/** Existing frame-clock gameplay, with the approved flat controls and room result page. */
@Composable internal fun PerformanceScreen(controller:PerformanceController,onPrepare:()->Unit,
    artwork:ReleaseArtwork?=null,onExplore:(()->Unit)?=null,onSpace:()->Unit=onPrepare,onSpaceOrigin:(Offset)->Unit={}) {
    val ui by controller.ui.collectAsStateWithLifecycle()
    val song=controller.song
    val lifecycle=LocalLifecycleOwner.current
    val motion=LocalPhysicalMotion.current
    val view=LocalView.current
    var exitConfirm by rememberSaveable(controller.song.id){mutableStateOf(false)}
    val live=ui.state!=PlayState.FINISHED
    val reveal=remember(controller){Animatable(0f)}
    LaunchedEffect(controller,motion){if(motion)reveal.animateTo(1f,tween(180))else reveal.snapTo(1f)}
    DisposableEffect(view,live) {
        val bars=(view.context as? Activity)?.window?.let{WindowCompat.getInsetsController(it,view)}
        val oldStatus=bars?.isAppearanceLightStatusBars
        val oldNav=bars?.isAppearanceLightNavigationBars
        bars?.isAppearanceLightStatusBars=!live
        bars?.isAppearanceLightNavigationBars=!live
        onDispose{if(oldStatus!=null)bars?.isAppearanceLightStatusBars=oldStatus;if(oldNav!=null)bars?.isAppearanceLightNavigationBars=oldNav}
    }
    val requestBack:()->Unit={
        if(ui.state in listOf(PlayState.PLAYING,PlayState.STARTING,PlayState.PAUSED,PlayState.RECOVERING,PlayState.AUDIO_FAILED)){controller.pause();exitConfirm=true}
        else onPrepare()
    }
    BackHandler(onBack=requestBack)
    DisposableEffect(controller,lifecycle) {
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_PAUSE)controller.pause()}
        lifecycle.lifecycle.addObserver(observer)
        onDispose{controller.pause();lifecycle.lifecycle.removeObserver(observer)}
    }
    LaunchedEffect(controller){while(isActive){withFrameNanos{controller.frame()}}}
    if(ui.state==PlayState.FINISHED) {
        RoomWall(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {Column {
            RoomHeader("本次演奏",onBack=onPrepare,onSpace=onSpace,onSpaceOrigin=onSpaceOrigin)
            RoomPerformanceResult(song,ui,artwork,controller.options.mode!=PlayMode.PLAY,
                {controller.restart();controller.start()},onExplore,controller::retrySave)
        }}
        return
    }
    val drawLanes=ui.state in listOf(PlayState.PLAYING,PlayState.PAUSED,PlayState.STARTING,PlayState.RECOVERING,PlayState.AUDIO_FAILED)
    BoxWithConstraints(Modifier.fillMaxSize().background(RoomColors.Wall).testTag("performance-screen").graphicsLayer{alpha=reveal.value}) {
        SwissPerformanceStage(song,ui,motion,Modifier.matchParentSize())
        val laneArea=Modifier.align(Alignment.TopCenter).offset(y=maxHeight*.52f).fillMaxWidth().padding(horizontal=27.dp).height(maxHeight*.41f)
        if(drawLanes) {
            MusicLaneBackdrop(laneArea)
            MusicLanes(controller,ui,motion,laneArea)
        }
        if(ui.state==PlayState.PLAYING&&ui.countIn==0&&controller.options.mode!=PlayMode.LISTEN) {
            Box(Modifier.align(Alignment.TopCenter).offset(y=maxHeight*.455f).fillMaxWidth().padding(horizontal=37.dp).height(48.dp)){PerformanceFeedback(ui)}
        }
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(start=26.dp,end=22.dp,top=24.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                SwissEyebrow("03 / PLAY",Modifier.weight(1f))
                IconButton({controller.pause()},Modifier.size(44.dp).testTag("performance-pause"),enabled=ui.state in listOf(PlayState.PLAYING,PlayState.STARTING)) {
                    Icon(Icons.Outlined.Pause,"暂停",Modifier.size(19.dp),tint=RoomColors.Ink)
                }
            }
            Text(song.title,fontSize=28.sp,fontWeight=FontWeight.SemiBold,color=RoomColors.Ink,maxLines=2)
            Row(Modifier.fillMaxWidth().padding(top=7.dp,bottom=18.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                Text(song.artist,fontSize=12.sp,color=RoomColors.Muted)
                Text("原速 · ${if(controller.options.mode==PlayMode.COMPARE)"体验演奏"else if(controller.options.difficulty==1)"轻松演奏"else "标准演奏"}",fontSize=12.sp,color=RoomColors.Muted)
            }
            LinearProgressIndicator(progress={(ui.positionUs.toDouble()/song.durationUs.coerceAtLeast(1)).toFloat().coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().height(2.dp),color=RoomColors.Red,trackColor=RoomColors.Line)
            Row(Modifier.fillMaxWidth().padding(top=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                Text("${musicTime(ui.positionUs)} / ${musicTime(song.durationUs)}",fontSize=12.sp,color=RoomColors.Muted)
                Text("%,d".format(ui.score),fontSize=22.sp,fontWeight=FontWeight.Normal,color=RoomColors.Ink,modifier=Modifier.testTag("performance-score"))
            }
        }
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start=26.dp,end=22.dp,bottom=15.dp),horizontalArrangement=Arrangement.SpaceBetween){Text("听踪 / TINGZONG",fontSize=10.sp,color=RoomColors.Muted);Text("原速 · 四轨",fontSize=10.sp,color=RoomColors.Muted)}
        if(exitConfirm) {
            PerformancePanel(Modifier.align(Alignment.Center)) {
                SwissEyebrow("03 / PAUSED")
                Text("返回曲目？",fontSize=32.sp,fontWeight=FontWeight.Medium,color=RoomColors.Ink)
                Text("音乐已暂停。",fontSize=14.sp,color=RoomColors.Muted)
                PerformancePanelButton("继续演奏","performance-stay"){exitConfirm=false;controller.start()}
                TextButton({exitConfirm=false;onPrepare()},Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("performance-confirm-exit")){Text("返回曲目")}
            }
        } else if(ui.state==PlayState.STARTING||ui.state==PlayState.PLAYING&&ui.countIn>0) {
            Surface(Modifier.align(Alignment.Center).size(118.dp,122.dp).testTag("performance-countdown"),
                shape=androidx.compose.ui.graphics.RectangleShape,color=RoomColors.Paper,shadowElevation=0.dp) {
                Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                    Text("准备",fontSize=14.sp,color=RoomColors.Muted)
                    Crossfade(if(ui.state==PlayState.STARTING)3 else ui.countIn,animationSpec=tween(if(motion)100 else 0),label="count-in") {count->
                        Box(Modifier.size(64.dp,65.dp),contentAlignment=Alignment.Center){Text(count.toString(),fontSize=46.sp,fontWeight=FontWeight.Medium,color=RoomColors.Navy)}
                    }
                }
            }
        } else if(ui.state in listOf(PlayState.READY,PlayState.PAUSED,PlayState.RECOVERING,PlayState.AUDIO_FAILED,PlayState.FAILED)) {
            PerformancePanel(Modifier.align(Alignment.Center).testTag("performance-pause-panel")) {
                SwissEyebrow(if(ui.state==PlayState.PAUSED)"03 / PAUSED"else "03 / PLAY")
                Text(when(ui.state){PlayState.PAUSED->"稍作停留。";PlayState.READY->"准备演奏";PlayState.RECOVERING->"正在恢复声音";else->"暂时无法开始"},fontSize=32.sp,fontWeight=FontWeight.Medium,color=RoomColors.Ink)
                Spacer(Modifier.height(20.dp))
                if(ui.message!=null)Text(requireNotNull(ui.message),fontSize=14.sp,color=RoomColors.Muted)
                if(ui.state==PlayState.RECOVERING)CircularProgressIndicator(Modifier.size(24.dp),color=RoomColors.Navy,strokeWidth=2.dp)
                if(ui.state !in listOf(PlayState.FAILED,PlayState.RECOVERING)) {
                    PerformancePanelButton(when(ui.state){PlayState.PAUSED->"继续演奏";PlayState.AUDIO_FAILED->"重试恢复声音";else->"开始演奏"},"performance-start",controller::start)
                    if(ui.state==PlayState.PAUSED)TextButton({controller.restart();controller.start()},Modifier.fillMaxWidth().heightIn(min=44.dp).testTag("performance-restart")){Text("从头开始")}
                }
                TextButton(onPrepare,Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("performance-back")){Text("返回曲目")}
            }
        } else if(ui.state==PlayState.LOADING)PerformancePanel(Modifier.align(Alignment.Center)) {
            Text("准备音乐与场景…",color=RoomColors.Ink)
        }
    }
}

@Composable private fun PerformancePanel(modifier:Modifier,content:@Composable ColumnScope.()->Unit) {
    val motion=LocalPhysicalMotion.current
    var visible by remember{mutableStateOf(false)}
    LaunchedEffect(Unit){visible=true}
    val reveal by animateFloatAsState(if(visible||!motion)1f else 0f,tween(if(motion)110 else 0),label="pause-panel")
    Surface(modifier.fillMaxSize().graphicsLayer{alpha=reveal},shape=androidx.compose.ui.graphics.RectangleShape,color=RoomColors.Paper.copy(alpha=.97f),shadowElevation=0.dp) {
        Column(Modifier.fillMaxSize().padding(34.dp),verticalArrangement=Arrangement.spacedBy(10.dp,Alignment.CenterVertically),content=content)
    }
}
@Composable private fun PerformancePanelButton(label:String,tag:String,onClick:()->Unit) {
    HorizontalDivider(color=RoomColors.Line)
    TextButton(onClick,Modifier.fillMaxWidth().heightIn(min=56.dp).testTag(tag),shape=androidx.compose.ui.graphics.RectangleShape) {
        Text(label,Modifier.weight(1f),color=RoomColors.Ink,fontSize=16.sp)
        Text("→",color=RoomColors.Ink,fontSize=18.sp)
    }
}
internal fun musicTime(us:Long):String {val seconds=us.coerceAtLeast(0)/1_000_000;return "%d:%02d".format(seconds/60,seconds%60)}
