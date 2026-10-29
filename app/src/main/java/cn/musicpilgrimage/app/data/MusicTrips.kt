package cn.musicpilgrimage.app.data

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

internal data class MusicTripCity(val name:String,val country:String) {val key get()="$country::$name"}
internal data class MusicTripStop(val id:String,val placeId:String?,val name:String,val city:String,val country:String,val day:Int?,val note:String="")
internal data class MusicTrip(val id:String,val name:String,val start:LocalDate,val end:LocalDate,val cities:List<MusicTripCity>,val stops:List<MusicTripStop> = emptyList()) {
    val days get()=ChronoUnit.DAYS.between(start,end).toInt()+1
    init {require(name.isNotBlank()&&name.length<=60){"请填写1至60字的行程名称"};require(days in 1..365){"请选择有效的出行日期，最长365天"};require(cities.isNotEmpty()){ "请至少选择一座城市" };require(stops.map{it.id}.distinct().size==stops.size);require(stops.all{it.day==null||it.day in 1..days})}
    fun changeDates(from:LocalDate,to:LocalDate):MusicTrip {
        val count=ChronoUnit.DAYS.between(from,to).toInt()+1
        require(count in 1..365){"请选择有效的出行日期，最长365天"}
        return copy(start=from,end=to,stops=stops.map{if(it.day!=null&&it.day>count)it.copy(day=null)else it})
    }
    fun editDates(from:LocalDate,to:LocalDate,beforeStart:LocalDate,beforeEnd:LocalDate):MusicTrip {
        if(from==beforeStart&&to==beforeEnd)return this
        require((start==beforeStart&&end==beforeEnd)||(start==from&&end==to)){"出行日期已更新，请关闭后重新打开编辑"}
        return changeDates(from,to)
    }
    fun editDetails(nextName:String,nextCities:List<MusicTripCity>,beforeName:String,beforeCities:List<String>):MusicTrip {
        val cleanName=nextName.trim();val cleanCities=nextCities.distinctBy{it.key}
        val nameChanged=cleanName!=beforeName;val citiesChanged=cleanCities.map{it.key}!=beforeCities
        require(!nameChanged||name==beforeName||name==cleanName){"行程名称已更新，请关闭后重新打开编辑"}
        require(!citiesChanged||cities.map{it.key}==beforeCities||cities==cleanCities){"行程城市已更新，请关闭后重新打开编辑"}
        val mergedCities=if(citiesChanged)cleanCities else cities
        require(stops.all{stop->mergedCities.any{it.name==stop.city&&it.country==stop.country}}){"请先移出该城市的地点，再取消这座城市"}
        return copy(name=if(nameChanged)cleanName else name,cities=mergedCities)
    }
    fun add(place:Place,day:Int):MusicTrip {
        require(day in 1..days){"请输入1至${days}之间的天数"}
        require(cities.any{it.key=="${place.country}::${place.city}"}){"这个地点不在行程已选城市中"}
        require(stops.none{it.placeId==place.id&&it.day==day}){"这个地点已经安排在第${day}天，可查看原安排或选择另一天"}
        val stop=MusicTripStop(UUID.randomUUID().toString(),place.id,place.name,place.city,place.country,day)
        val index=stops.indexOfLast{it.day==day}.takeIf{it>=0}?.plus(1) ?: stops.indexOfFirst{it.day==null||(it.day?:0)>day}.takeIf{it>=0} ?: stops.size
        return copy(stops=stops.toMutableList().apply{add(index,stop)})
    }
    fun move(id:String,day:Int?,index:Int):MusicTrip {
        require(day==null||day in 1..days){"请输入有效的天数"}
        val stop=stops.find{it.id==id} ?: return this
        require(day==null||stop.placeId==null||stops.none{it.id!=id&&it.placeId==stop.placeId&&it.day==day}){"这个地点已经安排在第${day}天，请选择另一天"}
        val remaining=stops.filterNot{it.id==id}.toMutableList()
        val group=remaining.filter{it.day==day}
        val slot=index.coerceIn(0,group.size)
        val absolute=if(slot<group.size)remaining.indexOfFirst{it.id==group[slot].id} else if(group.isNotEmpty())remaining.indexOfLast{it.day==day}+1 else remaining.indexOfFirst{day!=null&&(it.day==null||it.day>day)}.takeIf{it>=0} ?: remaining.size
        remaining.add(absolute,stop.copy(day=day));return copy(stops=remaining)
    }
}

/** Owner-separated local plans and their last confirmed cloud base. */
internal class MusicTripStore(private val preferences:SharedPreferences,private val onChanged:(String,Boolean)->Unit={_,_->}):LibraryDocumentStore {
    override val kind=PersonalLibraryKind.TRIPS
    @Synchronized fun read(owner:String):List<MusicTrip> {
        val array=runCatching{JSONArray(preferences.getString("$owner:trips","[]"))}.getOrDefault(JSONArray())
        return (0 until array.length()).mapNotNull{array.optJSONObject(it)}.mapNotNull{raw->runCatching{MusicTripCodec.trip(raw)}.getOrNull()}.sortedBy{it.start}
    }
    @Synchronized fun write(owner:String,values:List<MusicTrip>) {
        val array=MusicTripCodec.encode(values).getJSONArray("trips")
        val before=JSONObject().put("trips",JSONArray(preferences.getString("$owner:trips","[]")))
        check(preferences.edit().putString("$owner:trips",array.toString()).libraryLocalEdit(preferences,owner,kind,before,JSONObject().put("trips",array)).commit()){"行程尚未保存，请重试"}
        onChanged(owner,true)
    }
    @Synchronized fun update(owner:String,block:(List<MusicTrip>)->List<MusicTrip>)=block(read(owner)).also{write(owner,it)}
    @Synchronized override fun snapshot(owner:String):LibraryLocalDocument {
        val doc=JSONObject().put("trips",JSONArray(preferences.getString("$owner:trips","[]")))
        // A malformed cache must stop synchronization rather than upload a silently truncated plan.
        MusicTripCodec.decode(doc)
        return librarySnapshot(preferences,owner,kind,doc)
    }
    @Synchronized override fun reconcile(owner:String,remote:LibraryCloudDocument,acknowledged:LibraryLocalDocument?):LibraryMerge {
        MusicTripCodec.decode(remote.data)
        val local=snapshot(owner)
        if(remote.revision<local.revision)return LibraryMerge(local.data)
        val merged=reconcileLibrary(kind,local,remote,acknowledged)
        val rows=MusicTripCodec.decode(merged.data)
        check(preferences.edit().putString("$owner:trips",MusicTripCodec.encode(rows).getJSONArray("trips").toString()).libraryRemote(owner,remote,merged.dirty).commit()){"同步行程尚未保存，请重试"}
        onChanged(owner,false)
        return merged
    }
    @Synchronized fun clear(owner:String) {
        check(preferences.edit().remove("$owner:trips").remove("$owner:sync-base").remove("$owner:sync-version").remove("$owner:sync-dirty").remove("$owner:sync-generation").remove("$owner:sync-client").commit())
        onChanged(owner,false)
    }
}
