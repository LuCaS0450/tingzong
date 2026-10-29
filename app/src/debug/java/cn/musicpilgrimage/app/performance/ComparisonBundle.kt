package cn.musicpilgrimage.app.performance

import android.content.Context
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import org.json.JSONObject

/** Whitelisted local audio import. A failed import never replaces the previous usable bundle. */
internal object ComparisonBundle {
    private const val preference="rhythm-comparison-local"
    private val required=setOf("original.pcmz","comparison.json")
    // Older private bundles remain usable; their slower audio is never offered for playback.
    private val names=required+"slower.pcmz"
    fun current(context:Context):File? {
        val name=context.getSharedPreferences(preference,Context.MODE_PRIVATE).getString("directory","rhythm-comparison") ?: return null
        if(!Regex("rhythm-comparison(?:-[a-f0-9-]{36})?").matches(name))return null
        return File(context.filesDir,name).takeIf{directory->required.all{File(directory,it).isFile}}
    }
    fun import(context:Context,input:InputStream):File {
        val directory=File(context.filesDir,"rhythm-comparison-${UUID.randomUUID()}")
        check(directory.mkdir())
        var published=false
        try {
            val seen=mutableSetOf<String>()
            ZipInputStream(input).use {zip->
                while(true) {
                    val entry=zip.nextEntry ?: break
                    require(!entry.isDirectory && entry.name in names && seen.add(entry.name)){"请选择本次提供的试听包"}
                    val maximum=if(entry.name=="comparison.json")8192 else 16_000_000
                    File(directory,entry.name).outputStream().use {out->
                        val buffer=ByteArray(8192);var count=0
                        while(true) {val read=zip.read(buffer);if(read<0)break;count+=read;require(count<=maximum){"试听包大小不正确"};out.write(buffer,0,read)}
                    }
                }
            }
            require(seen.containsAll(required)){"试听包缺少文件"}
            val manifest=JSONObject(File(directory,"comparison.json").readText())
            require(manifest.getDouble("sourceStartSeconds")==45.0 && manifest.getDouble("sourceDurationSeconds")==30.0)
            // Audio identity is independent of chart density and content versions.
            require(manifest.getBoolean("stereo") && manifest.getInt("originalFrames")==1323000)
            for((name,expected) in listOf("original.pcmz" to 1323000*8,"slower.pcmz" to 1470000*8).filter{it.first in seen}) {
                GZIPInputStream(File(directory,name).inputStream()).use {stream->
                    var count=0;val bytes=ByteArray(8192)
                    while(true){val read=stream.read(bytes);if(read<0)break;count+=read;require(count<=expected){"试听音频时长不正确"}}
                    require(count==expected){"试听音频不完整"}
                }
            }
            val previous=current(context)
            check(context.getSharedPreferences(preference,Context.MODE_PRIVATE).edit().putString("directory",directory.name).commit())
            published=true
            previous?.takeIf{it!=directory}?.deleteRecursively()
            return directory
        } finally {if(!published)directory.deleteRecursively()}
    }
}
