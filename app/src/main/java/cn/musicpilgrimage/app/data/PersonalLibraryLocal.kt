package cn.musicpilgrimage.app.data

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

internal fun librarySnapshot(prefs:SharedPreferences,owner:String,kind:PersonalLibraryKind,current:JSONObject):LibraryLocalDocument {
    val base=prefs.getString("$owner:sync-base",null)?.let(::JSONObject)?:emptyLibraryDocument(kind)
    val persisted=JSONObject(prefs.getString("$owner:sync-dirty","{}")!!)
    val dirty=libraryChangedKeys(kind,base,current).associateWith{0L}+persisted.keys().asSequence().associateWith{persisted.getLong(it)}
    val client=prefs.getString("$owner:sync-client",null)?:UUID.randomUUID().toString().also{check(prefs.edit().putString("$owner:sync-client",it).commit()){"同步标记尚未保存，请重试"}}
    return LibraryLocalDocument(prefs.getLong("$owner:sync-version",0),base,current,dirty,client)
}
internal fun SharedPreferences.Editor.libraryRemote(owner:String,remote:LibraryCloudDocument,dirty:Map<String,Long>)=
    putLong("$owner:sync-version",remote.revision).putString("$owner:sync-base",remote.data.toString()).putString("$owner:sync-dirty",JSONObject(dirty).toString())
internal fun SharedPreferences.Editor.libraryLocalEdit(prefs:SharedPreferences,owner:String,kind:PersonalLibraryKind,before:JSONObject,after:JSONObject):SharedPreferences.Editor {
    val dirty=librarySnapshot(prefs,owner,kind,before).dirty.toMutableMap()
    val generation=prefs.getLong("$owner:sync-generation",0)+1
    libraryChangedKeys(kind,before,after).forEach{dirty[it]=generation}
    return putLong("$owner:sync-generation",generation).putString("$owner:sync-dirty",JSONObject(dirty).toString())
}

internal object MusicFavoriteCodec {
    fun encode(value:MusicFavorites)=JSONObject().apply {
        for(kind in listOf("songs","releases"))put(kind,JSONArray().apply{
            (if(kind=="songs")value.songs else value.releases).forEach{id->put(JSONObject().put("id",id).put("added_at",value.time(id,kind=="releases")))}
        })
    }
    fun decode(doc:JSONObject,owner:String):MusicFavorites {
        val dates=mutableMapOf<String,Long>()
        fun ids(kind:String)=doc.getJSONArray(kind).objects().map{row->
            val id=row.getString("id");val time=row.getLong("added_at")
            require(id.isNotBlank()&&id.length<=512&&time>=0){"音乐收藏数据无效，本机数据已保留"}
            dates["$kind:$id"]=time;id
        }.also{require(it.distinct().size==it.size){"音乐收藏重复，本机数据已保留"}}
        val songs=ids("songs");val releases=ids("releases")
        return MusicFavorites(songs,releases,dates,owner)
    }
}

internal object MusicTripCodec {
    fun encode(values:List<MusicTrip>)=JSONObject().put("trips",JSONArray().apply {
        values.sortedWith(compareBy<MusicTrip>{it.start}.thenBy{it.id}).forEach {trip->put(JSONObject().put("id",trip.id).put("name",trip.name).put("start",trip.start.toString()).put("end",trip.end.toString())
            .put("cities",JSONArray().apply{trip.cities.forEach{put(JSONObject().put("name",it.name).put("country",it.country))}})
            .put("stops",JSONArray().apply{trip.stops.forEach{put(JSONObject().put("id",it.id).put("place",it.placeId?:JSONObject.NULL).put("name",it.name).put("city",it.city).put("country",it.country).put("day",it.day?:JSONObject.NULL).put("note",it.note))}}))}
    })
    fun decode(doc:JSONObject):List<MusicTrip> = doc.getJSONArray("trips").objects().map(::trip).also{require(it.map{row->row.id}.distinct().size==it.size){"行程重复，本机数据已保留"}}.sortedBy{it.start}
    fun trip(raw:JSONObject)=MusicTrip(raw.getString("id"),raw.getString("name"),LocalDate.parse(raw.getString("start")),LocalDate.parse(raw.getString("end")),
        raw.getJSONArray("cities").objects().map{MusicTripCity(it.getString("name"),it.getString("country"))},
        raw.getJSONArray("stops").objects().map{MusicTripStop(it.getString("id"),it.stringOrNull("place"),it.getString("name"),it.getString("city"),it.getString("country"),if(it.isNull("day"))null else it.getInt("day"),it.optString("note"))})
}
