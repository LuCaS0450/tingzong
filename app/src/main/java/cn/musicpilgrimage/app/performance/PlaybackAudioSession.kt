package cn.musicpilgrimage.app.performance

import android.content.*
import android.media.*
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.io.Closeable

/** Focus gain never restarts music automatically; the player explicitly chooses Continue. */
internal class PlaybackAudioSession(context:Context,private val onInterrupt:()->Unit):Closeable {
    private val app=context.applicationContext
    private val audio=app.getSystemService(AudioManager::class.java)
    private val handler=Handler(Looper.getMainLooper())
    private var focused=false
    private var closed=false
    private val request=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setOnAudioFocusChangeListener({change->
            if(change<0 && !closed){focused=false;onInterrupt()}
        },handler).build()
    private val receiver=object:BroadcastReceiver(){override fun onReceive(context:Context?,intent:Intent?){
        if(!closed && intent?.action==AudioManager.ACTION_AUDIO_BECOMING_NOISY)onInterrupt()
    }}
    init {ContextCompat.registerReceiver(app,receiver,IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),ContextCompat.RECEIVER_NOT_EXPORTED)}
    fun acquire():Boolean {
        focused=audio.requestAudioFocus(request)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return focused
    }
    fun abandon(){audio.abandonAudioFocusRequest(request);focused=false}
    override fun close(){if(!closed){closed=true;abandon();app.unregisterReceiver(receiver)}}
}
