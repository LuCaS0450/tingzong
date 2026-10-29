package cn.musicpilgrimage.app.performance

import android.content.Context
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

/** Invoke during preparation on a serialized worker, before publishing the player to UI. */
internal object PianoSamples {
    fun load(context:Context,engine:NativePiano) {
        val assets=context.assets
        val samples=JSONObject(assets.open("performance/piano/samples.json").bufferedReader().use{it.readText()}).getJSONArray("samples")
        for(i in 0 until samples.length()) {
            val row=samples.getJSONObject(i)
            val name=row.getString("file");require(Regex("[0-9]+\\.pcmz").matches(name))
            val bytes=assets.open("performance/piano/$name").use{input->GZIPInputStream(input).use{it.readBytes()}}
            require(bytes.size%4==0 && bytes.size<=48000*16*4)
            val pcm=FloatArray(bytes.size/4)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(pcm)
            engine.setSample(engine.handle,row.getInt("root"),row.getInt("sampleRate"),pcm)
        }
    }
}
