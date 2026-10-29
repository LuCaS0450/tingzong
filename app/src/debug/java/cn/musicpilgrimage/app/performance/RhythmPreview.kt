package cn.musicpilgrimage.app.performance

import android.content.Context
import org.json.JSONObject

/** Explicitly unverified full-song content, available only to the private debug preview. */
internal object RhythmPreview {
    fun song(context:Context):SongPackage {
        val json=JSONObject(context.assets.open("rhythm-comparison/chart.json").bufferedReader().use{it.readText()})
        check(!json.getBoolean("fullSongVerified"))
        fun targets(name:String)=json.getJSONObject("charts").getJSONArray(name).let{a->List(a.length()){i->a.getJSONObject(i).let {
            Target(i,emptyList(),it.getInt("lane"),it.getLong("onsetUs"),it.getLong("durationUs"),it.getBoolean("hold"))
        }}}
        val variants=json.getJSONArray("audioVariants").let{a->List(a.length()){i->a.getJSONObject(i).let{it.getDouble("speed") to it.getString("file")}}.toMap()}
        return SongPackage("greatest-works",json.getInt("version"),json.getString("title"),json.getString("artist"),
            json.getString("referenceEdition"),json.getLong("durationUs"),emptyList(),targets("easy"),targets("standard"),
            listOf(SongSection("像素大厅",0,json.getLong("durationUs"),json.getString("scene"),json.getDouble("bpm"))),
            emptyList(),JSONObject().put("originalUrl",json.getString("originalUrl")),RecordedMusic(json.getInt("sampleRate"),variants))
    }
    fun entry(context:Context,onImport:()->Unit)=PerformanceEntry(song(context),preview=true,audioDirectory=ComparisonBundle.current(context),onImport=onImport)
}
