package cn.musicpilgrimage.app.ui

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import cn.musicpilgrimage.app.design.AppPreferences
import cn.musicpilgrimage.app.design.AppPreferencesState
import androidx.lifecycle.compose.collectAsStateWithLifecycle

internal val LocalPhysicalMotion = staticCompositionLocalOf { true }

@Composable internal fun PhysicalMotionProvider(content: @Composable () -> Unit) {
    val context=LocalContext.current
    val preferences=remember(context) { AppPreferences(context) }
    val pref by preferences.changes.collectAsStateWithLifecycle(AppPreferencesState())
    fun systemEnabled()=runCatching {
        Settings.Global.getFloat(context.contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)>0f
    }.getOrDefault(true)
    var enabled by remember { mutableStateOf(systemEnabled()) }
    DisposableEffect(context) {
        val observer=object:ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange:Boolean) { enabled=systemEnabled() }
        }
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),false,observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    CompositionLocalProvider(LocalPhysicalMotion provides (enabled&&!pref.reducedMotion),content=content)
}
