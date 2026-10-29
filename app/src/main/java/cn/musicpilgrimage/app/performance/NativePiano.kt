package cn.musicpilgrimage.app.performance

import java.io.Closeable

/** All control calls come from one serialized thread; resource loading happens before start. */
internal class NativePiano:Closeable {
    companion object { init { System.loadLibrary("tingzong_piano") } }
    val handle:Long=create().also{check(it!=0L){"这台设备暂时无法打开钢琴音频"}}
    private var closed=false
    external fun create():Long
    external fun destroy(handle:Long)
    external fun setSample(handle:Long,root:Int,sampleRate:Int,pcm:FloatArray)
    external fun setScore(handle:Long,events:DoubleArray):Boolean
    external fun setBacking(handle:Long,sampleRate:Int,speed:Double,stereoPcm:FloatArray):Boolean
    external fun start(handle:Long,start:Double,end:Double,speed:Double,mask:Int,bpm:Double,beats:Int):Boolean
    external fun pause(handle:Long)
    external fun position(handle:Long):Long
    external fun counting(handle:Long):Int
    external fun isPlaying(handle:Long):Boolean
    external fun failed(handle:Long):Boolean
    external fun sampleRate(handle:Long):Int
    external fun playingVoices(handle:Long):Int
    external fun setMuted(handle:Long,muted:Boolean)
    /** Peak of the last buffer actually handed to the output stream, for audio diagnostics. */
    external fun outputPeak(handle:Long):Float
    external fun hit(handle:Long,eventId:Int,elapsedSeconds:Double):Boolean
    external fun release(handle:Long,eventId:Int):Boolean
    override fun close(){if(!closed){closed=true;destroy(handle)}}
}
