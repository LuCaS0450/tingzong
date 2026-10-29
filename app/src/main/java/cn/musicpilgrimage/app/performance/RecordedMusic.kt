package cn.musicpilgrimage.app.performance

import android.content.Context
import java.io.File
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import org.json.JSONObject

/** Complete music and interaction targets are independent. Each variant retains the same pitch. */
internal data class RecordingFile(val bytes:Long,val sha256:String)
internal data class RecordedMusic(val sampleRate:Int,val variants:Map<Double,String>,val local:Boolean=false,
    val frames:Long?=null,val files:Map<String,RecordingFile> = emptyMap(),val encoding:String="f32le") {
    init {
        require(sampleRate in 8000..192000 && variants.isNotEmpty())
        require(encoding in listOf("f32le","pcm16-delta"))
        require(variants.keys.all{it in listOf(.5,.75,.9,1.0)})
        require(variants.values.all{Regex("[a-z0-9-]+\\.pcmz").matches(it)})
        if(local) {
            require(variants.keys==setOf(1.0) && frames!=null && frames in 1..MAX_PCM_BYTES/8)
            require(files.keys==variants.values.toSet())
            require(files.values.all{it.bytes in 1..MAX_PCM_BYTES && Regex("[a-f0-9]{64}").matches(it.sha256)})
        }
    }
    fun load(context:Context,song:SongPackage,engine:NativePiano,speed:Double,localDirectory:File?=null) {
        val name=variants[speed] ?: error("这首曲目还没有该速度的音频")
        require(!local || localDirectory!=null){"请先导入这首歌曲的音源"}
        val expectedFrames=if(speed==1.0&&frames!=null)frames else kotlin.math.round(song.durationUs/1e6/speed*sampleRate).toLong()
        require(expectedFrames in 1..MAX_PCM_BYTES/8){"歌曲音频过大"}
        require(kotlin.math.abs(expectedFrames.toDouble()/sampleRate*speed*1e6-song.durationUs)<=20_000){"歌曲音频与谱面时长不一致"}
        val spec=files[name]
        if(spec!=null)require(File(requireNotNull(localDirectory),name).length()==spec.bytes){"音源不完整，请重新导入"}
        val input=if(localDirectory!=null)File(localDirectory,name).inputStream()
            else context.assets.open("performance/${song.id}/audio/$name")
        val (pcm,actualSha256)=decodeSamples(input,expectedFrames)
        if(spec!=null)require(actualSha256==spec.sha256){"音源与曲目不匹配，请重新导入"}
        require(pcm.all{it.isFinite()}){"歌曲音频含有无效采样"}
        check(engine.setBacking(engine.handle,sampleRate,speed,pcm)){"歌曲音频无法读取"}
    }
    internal fun decodeSamples(rawInput:java.io.InputStream,expectedFrames:Long):Pair<FloatArray,String> {
        // One bounded PCM allocation, with a small decode buffer. Never retain a second
        // whole-song byte array; decompression and validation stay off the audio callback.
        val pcm=FloatArray((expectedFrames*2).toInt())
        val digest=MessageDigest.getInstance("SHA-256")
        rawInput.use{raw->DataInputStream(GZIPInputStream(DigestInputStream(raw,digest))).use{stream->
            val buffer=ByteArray(64*1024)
            var offset=0
            if(encoding=="pcm16-delta") {
                var left=0
                var right=0
                while(offset<pcm.size) {
                    val count=minOf(buffer.size/2,pcm.size-offset)
                    stream.readFully(buffer,0,count*2)
                    val shorts=ByteBuffer.wrap(buffer,0,count*2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    var i=0
                    while(i<count) {
                        left=(left+shorts.get(i).toInt()).toShort().toInt()
                        right=(right+shorts.get(i+1).toInt()).toShort().toInt()
                        pcm[offset+i]=left/32768f
                        pcm[offset+i+1]=right/32768f
                        i+=2
                    }
                    offset+=count
                }
            } else {
                while(offset<pcm.size) {
                    val count=minOf(buffer.size/4,pcm.size-offset)
                    stream.readFully(buffer,0,count*4)
                    ByteBuffer.wrap(buffer,0,count*4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(pcm,offset,count)
                    offset+=count
                }
            }
            require(stream.read()==-1){"歌曲音频与谱面时长不一致"}
        }}
        return pcm to digest.digest().joinToString(""){"%02x".format(it)}
    }
    companion object {
        const val MAX_PCM_BYTES=192L*1024*1024
        fun from(json:JSONObject):RecordedMusic {
            val storage=json.optString("storage","bundled")
            require(storage in listOf("local","bundled"))
            val encoding=json.optString("encoding","f32le")
            return RecordedMusic(json.getInt("sampleRate"),json.getJSONArray("variants").let{a->
            List(a.length()){i->a.getJSONObject(i).let{it.getDouble("speed") to it.getString("file")}}.also{
                require(it.map{pair->pair.first}.distinct().size==it.size)
            }.toMap()
            },storage=="local",if(json.has("frames"))json.getLong("frames")else null,
            json.optJSONArray("audioFiles")?.let{a->List(a.length()){i->a.getJSONObject(i).let{
                it.getString("file") to RecordingFile(it.getLong("bytes"),it.getString("sha256"))
            }}.also{require(it.map{pair->pair.first}.distinct().size==it.size)}.toMap()} ?: emptyMap(),encoding)
        }
    }
}
