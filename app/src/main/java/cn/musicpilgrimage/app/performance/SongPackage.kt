package cn.musicpilgrimage.app.performance

import org.json.JSONObject

internal data class MusicEvent(val id:Int,val onsetUs:Long,val durationUs:Long,val pitch:Int,val velocity:Int,val mask:Int) {
    val endUs get()=onsetUs+durationUs
}
internal data class Target(val id:Int,val eventIds:List<Int>,val lane:Int,val onsetUs:Long,val durationUs:Long,val hold:Boolean) {
    val endUs get()=onsetUs+durationUs
}
internal data class SongSection(val name:String,val startUs:Long,val endUs:Long,val scene:String,val bpm:Double)
internal data class SongPackage(val id:String,val version:Int,val title:String,val artist:String,val referenceEdition:String,
    val durationUs:Long,val events:List<MusicEvent>,val easy:List<Target>,val standard:List<Target>,
    val sections:List<SongSection>,val placeIds:List<String>,val sources:JSONObject,val recording:RecordedMusic?=null,
    val background:String="background.webp") {
    fun targets(difficulty:Int)=if(difficulty==1)easy else standard
    fun nativeEvents()=DoubleArray(events.size*5).also {array->events.forEachIndexed {i,event->
        array[i*5]=event.onsetUs/1_000_000.0;array[i*5+1]=event.durationUs/1_000_000.0
        array[i*5+2]=event.pitch.toDouble();array[i*5+3]=event.velocity.toDouble();array[i*5+4]=event.mask.toDouble()
    }}
    companion object {
        fun from(json:JSONObject):SongPackage {
            require(json.getBoolean("releaseRhythmVerified")){"曲目仍在校对中"}
            val recording=json.optJSONObject("recording")?.let(RecordedMusic::from)
            val raw=json.optJSONArray("events") ?: org.json.JSONArray()
            val events=List(raw.length()){i->raw.getJSONObject(i).let {MusicEvent(i,it.getLong("onsetUs"),it.getLong("durationUs"),it.getInt("pitch"),it.getInt("velocity"),it.getInt("mask"))}}
            require((events.isNotEmpty() || recording!=null) && events.zipWithNext().all{(a,b)->a.onsetUs<=b.onsetUs})
            require(events.all{it.onsetUs>=0 && it.durationUs>0 && it.pitch in 21..108 && it.velocity in 1..127 && it.mask in 0..3})
            fun chart(name:String,mask:Int):List<Target> {
                val a=json.getJSONObject("charts").getJSONArray(name)
                val result=List(a.length()){i->a.getJSONObject(i).let {n->
                    if(recording!=null) {
                        require(!n.has("eventIds")){"录音互动谱不分配音符所有权"}
                        return@let Target(i,emptyList(),n.getInt("lane"),n.getLong("onsetUs"),n.getLong("durationUs"),n.getBoolean("hold"))
                    }
                    val ids=n.getJSONArray("eventIds").let{v->List(v.length()){v.getInt(it)}}
                    val notes=ids.map{events[it]}
                    require(notes.isNotEmpty() && notes.all{(it.mask and mask)!=0} && notes.all{it.onsetUs==notes[0].onsetUs})
                    Target(i,ids,n.getInt("lane"),notes[0].onsetUs,notes.maxOf{it.durationUs},n.getBoolean("hold"))
                }}
                require(result.isNotEmpty() && result.all{it.lane in 0..3 && it.onsetUs>=0 && it.durationUs>0 && it.endUs<=json.getLong("durationUs")})
                require(result.zipWithNext().all{(a,b)->a.onsetUs<b.onsetUs && (!a.hold || a.endUs<=b.onsetUs)})
                // Retain legacy MIDI provenance validation, independent of playback policy.
                val assigned=result.flatMap{it.eventIds}
                if(recording==null)require(assigned.size==assigned.distinct().size && assigned.toSet()==events.filter{(it.mask and mask)!=0}.map{it.id}.toSet())
                return result
            }
            val sections=json.getJSONArray("sections").let{a->List(a.length()){i->a.getJSONObject(i).let{SongSection(it.getString("name"),it.getLong("startUs"),it.getLong("endUs"),it.getString("scene"),it.getDouble("bpm"))}}}
            val duration=json.getLong("durationUs")
            require(duration>0 && duration>=(events.maxOfOrNull{it.endUs} ?: 0L) && sections.isNotEmpty() && sections.first().startUs==0L)
            require(sections.all{it.startUs>=0 && it.endUs>it.startUs && it.endUs<=duration && it.bpm>0})
            require(sections.zipWithNext().all{(a,b)->a.endUs==b.startUs} && sections.last().endUs==duration)
            val places=json.getJSONArray("placeIds").let{a->List(a.length()){a.getString(it)}}
            require(Regex("[a-z0-9-]+").matches(json.getString("id")) && json.getInt("version")>0)
            require(sections.all{Regex("[a-z0-9-]+").matches(it.scene)})
            val background=json.optString("background","background.webp")
            require(Regex("[a-z0-9-]+\\.webp").matches(background))
            val standard=chart("standard",2)
            // Old packages retain their historical chart for decoding saved checkpoints;
            // new releases contain only standard, which is the sole app entry option.
            val legacyEasy=if(json.getJSONObject("charts").has("easy"))chart("easy",1)else standard
            return SongPackage(json.getString("id"),json.getInt("version"),json.getString("title"),json.getString("artist"),
                json.getString("referenceEdition"),duration,events,legacyEasy,standard,sections,places,json.getJSONObject("sources"),recording,background)
        }
    }
}
