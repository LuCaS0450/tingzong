package cn.musicpilgrimage.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import cn.musicpilgrimage.app.ui.PilgrimageApp
import cn.musicpilgrimage.app.design.AppArt
import androidx.compose.ui.graphics.toArgb

/** Debug-only public-catalogue preview. Account APIs still enforce the actual authenticated owner. */
class MusicSpacePreviewActivity:ComponentActivity() {
    private val session:SessionViewModel by viewModels()
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle=SystemBarStyle.light(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT),navigationBarStyle=SystemBarStyle.light(AppArt.Base.toArgb(),AppArt.Base.toArgb()))
        setContent{PilgrimageApp(session,requireAccount=false)}
    }
}
