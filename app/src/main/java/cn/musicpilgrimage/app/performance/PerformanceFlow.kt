package cn.musicpilgrimage.app.performance

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.musicpilgrimage.app.data.ReleaseArtwork
import cn.musicpilgrimage.app.ui.room.*
import java.io.File

/** Preview entries are supplied only by a debug activity, never admitted by the release catalogue. */
internal data class PerformanceEntry(val song:SongPackage,val preview:Boolean=false,val audioDirectory:File?=null,val onImport:(()->Unit)?=null) {
    val audioReady get()=if(preview||song.recording?.local==true)audioDirectory!=null else true
}
internal val LocalPerformancePreviews=staticCompositionLocalOf<List<PerformanceEntry>>{emptyList()}

/** One native engine per navigation entry; preserve it when visiting a related song/place page. */
internal class PerformanceSessionViewModel:ViewModel() {
    private var identity:String?=null
    private var store=ViewModelStore()
    fun controller(app:Application,entry:PerformanceEntry,owner:String,options:PlayOptions):PerformanceController {
        val next="$owner:${entry.song.id}:${entry.song.version}:$options:${entry.audioDirectory?.absolutePath}"
        if(identity!=next){store.clear();store=ViewModelStore();identity=next}
        return ViewModelProvider(store,object:ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass:Class<T>):T=
                PerformanceController(app,entry.song,owner,options,entry.audioDirectory,respectMute=true) as T
        })[PerformanceController::class.java]
    }
    fun reset(){store.clear();store=ViewModelStore();identity=null}
    override fun onCleared(){store.clear()}
}

@Composable internal fun PerformanceFlow(entry:PerformanceEntry,owner:String,onExit:()->Unit,
    artwork:ReleaseArtwork?=null,onExplore:(()->Unit)?=null,onFullscreen:(Boolean)->Unit={},onController:(PerformanceController?)->Unit={},
    importing:Boolean=false,importProgress:Float=0f,importMessage:String?=null,directStart:Boolean=false) {
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val song=entry.song
    val difficulty=2
    var entered by rememberSaveable(owner,song.id){mutableStateOf(directStart&&entry.audioReady)}
    var startRequested by rememberSaveable(owner,song.id){mutableStateOf(directStart&&entry.audioReady)}
    val session:PerformanceSessionViewModel=viewModel(key="music-performance:$owner:${song.id}")
    val onSpace=LocalRoomSpaceAction.current ?: onExit
    val origin=LocalRoomSpaceOrigin.current
    val latestFullscreen by rememberUpdatedState(onFullscreen)
    DisposableEffect(Unit){onDispose{latestFullscreen(false)}}
    if(entered) {
        val options=remember(difficulty,entry.preview){PlayOptions(difficulty,if(entry.preview)PlayMode.COMPARE else PlayMode.PLAY)}
        val controller=remember(session,options,entry.audioDirectory){session.controller(context.applicationContext as Application,entry,owner,options)}
        val ui by controller.ui.collectAsStateWithLifecycle()
        DisposableEffect(controller,lifecycle) {
            val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_PAUSE)startRequested=false}
            lifecycle.addObserver(observer)
            onDispose{lifecycle.removeObserver(observer)}
        }
        DisposableEffect(controller){onController(controller);onDispose{onController(null)}}
        LaunchedEffect(ui.state){
            onFullscreen(ui.state!=PlayState.FINISHED)
            if(ui.state==PlayState.RECOVERING)startRequested=false
        }
        LaunchedEffect(startRequested,ui.state,lifecycleState) {
            if(startRequested&&lifecycleState==Lifecycle.State.RESUMED&&ui.state in listOf(PlayState.READY,PlayState.PAUSED,PlayState.AUDIO_FAILED,PlayState.FINISHED)) {
                startRequested=false
                if(ui.state==PlayState.FINISHED)controller.restart()
                controller.start()
            }
        }
        PerformanceScreen(controller,{controller.pause();if(controller.ui.value.state==PlayState.FAILED)session.reset();entered=false;startRequested=false;if(directStart)onExit()},artwork,onExplore,onSpace,origin)
    } else {
        LaunchedEffect(Unit){onFullscreen(false)}
        Column(Modifier.fillMaxSize()) {
            RoomHeader("演奏准备",onBack=onExit,onSpace=onSpace,onSpaceOrigin=origin)
            RoomPerformancePreparation(entry,artwork,{entered=true;startRequested=true},entry.onImport,importing,importProgress,importMessage)
        }
    }
}
