package cn.musicpilgrimage.app.performance

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.musicpilgrimage.app.MusicSpacePreviewActivity
import cn.musicpilgrimage.app.PilgrimageApplication
import cn.musicpilgrimage.app.data.MusicCatalogue
import cn.musicpilgrimage.app.ui.PilgrimageTheme
import cn.musicpilgrimage.app.ui.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Private short sample and local import; shares the native preparation/game/result UI. */
class RhythmComparisonActivity:ComponentActivity() {
    internal var activeController:PerformanceController?=null
        private set
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setContent{PilgrimageTheme{Comparison()}}
    }
    @Composable private fun Comparison() {
        val song=remember{RhythmPreview.song(this)}
        var audioDirectory by remember{mutableStateOf(ComparisonBundle.current(this))}
        var busy by remember{mutableStateOf(false)}
        var message by remember{mutableStateOf<String?>(null)}
        var immersive by remember{mutableStateOf(false)}
        val scope=rememberCoroutineScope()
        val source by (application as PilgrimageApplication).services.catalog.collectAsStateWithLifecycle()
        val artwork=remember(source){MusicCatalogue.from(source).works.firstOrNull{it.title==song.title&&it.artist==song.artist}?.artwork}
        val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
            if(uri!=null&&!busy)scope.launch {
                busy=true;message=null
                try {audioDirectory=withContext(Dispatchers.IO){contentResolver.openInputStream(uri)!!.use{ComparisonBundle.import(this@RhythmComparisonActivity,it)}}}
                catch(error:Exception){message=error.message ?: "试听包导入失败，请重新选择"}
                finally {busy=false}
            }
        }
        val entry=PerformanceEntry(song,true,audioDirectory){if(!busy)importer.launch(arrayOf("application/zip","application/octet-stream"))}
        val openSpace:()->Unit={startActivity(Intent(this,MusicSpacePreviewActivity::class.java))}
        RoomWall(Modifier.fillMaxSize().then(if(immersive)Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing))) {
            CompositionLocalProvider(LocalRoomSpaceAction provides openSpace) {
                PerformanceFlow(entry,"preview:rhythm-comparison",{finish()},artwork,onFullscreen={immersive=it},onController={activeController=it})
            }
            if(busy)CircularProgressIndicator(Modifier.align(Alignment.BottomCenter).padding(24.dp),color=RoomColors.Navy)
            if(message!=null)Snackbar(Modifier.align(Alignment.BottomCenter).padding(16.dp),action={TextButton({message=null}){Text("关闭")}}){Text(requireNotNull(message))}
        }
    }
}
