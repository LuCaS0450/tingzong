package cn.musicpilgrimage.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.viewModels
import android.content.Intent
import cn.musicpilgrimage.app.ui.PilgrimageApp
import cn.musicpilgrimage.app.design.AppArt
import androidx.compose.ui.graphics.toArgb

class MainActivity : ComponentActivity() {
    private val session:SessionViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.light(AppArt.Base.toArgb(),AppArt.Base.toArgb()))
        setContent { PilgrimageApp(session) }
        if(savedInstanceState==null) intent.data?.let(session::callback)
    }
    override fun onNewIntent(intent:Intent) {super.onNewIntent(intent);setIntent(intent);intent.data?.let(session::callback)}
}
