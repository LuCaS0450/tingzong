package cn.musicpilgrimage.app.data

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

internal data class MusicTripCity(val name:String,val country:String) {
    val key get()="$country::$name"
    val isCountry get()=name==countryName(country)
    val region get()=unifiedDestinationRegion(country)
    private val canonicalCountry get()=canonicalRegionName(country)
    val pickerKey get()=region?.let{"region:$it"}
        ?: "$canonicalCountry::${if(isCountry) countryName(canonicalCountry) else label}"
    val label get()=region?:if(isCountry) canonicalCountry else canonicalTripCityName(name,canonicalCountry)

    fun asPickerDestination(): MusicTripCity =
        if (region != null || isCountry) country(canonicalCountry) else MusicTripCity(label, canonicalCountry)

    companion object {
        // Keep country destinations in the existing name/country sync format.
        private fun countryName(country:String)="$country（全境）"
        fun country(country:String)=MusicTripCity(countryName(country),country)

        fun fromStored(name: String, region: String): MusicTripCity {
            val canonical = canonicalRegionName(region)
            return if (name == countryName(region)) country(canonical) else MusicTripCity(name, canonical)
        }
    }
}
internal data class MusicTripStop(val id:String,val placeId:String?,val name:String,val city:String,val country:String,val day:Int?,val note:String="")

internal fun tripDisplayName(id: String, name: String): String {
    val suffix = "（同步副本）"
    return if (name.endsWith(suffix) && runCatching { UUID.fromString(id).version() == 3 }.getOrDefault(false)) {
        name.removeSuffix(suffix) + "（副本）"
    } else name
}

internal data class MusicTrip(val id:String,val name:String,val start:LocalDate,val end:LocalDate,val cities:List<MusicTripCity>,val stops:List<MusicTripStop> = emptyList()) {
    companion object { const val MAX_DESTINATIONS=64 }

    val days get()=ChronoUnit.DAYS.between(start,end).toInt()+1
    val displayName get() = tripDisplayName(id, name)
    init {requireUserAction(name.isNotBlank()&&name.length<=60){"请填写1至60字的行程名称"};requireUserAction(days in 1..365){"请选择有效的出行日期，最长365天"};requireUserAction(cities.isNotEmpty()){ "请至少选择一个目的地" };require(stops.map{it.id}.distinct().size==stops.size);require(stops.all{it.day==null||it.day in 1..days})}
    fun acceptsNewPlacesOn(today:LocalDate)=!end.isBefore(today)

    fun stopsBeyond(dayCount:Int)=stops.filter { it.day!=null && it.day>dayCount }

    fun changeDates(from:LocalDate,to:LocalDate):MusicTrip {
        val count=ChronoUnit.DAYS.between(from,to).toInt()+1
        requireUserAction(count in 1..365){"请选择有效的出行日期，最长365天"}
        requireUserAction(stopsBeyond(count).isEmpty()){ "请先移动超出新日期的活动" }
        return copy(start=from,end=to)
    }
    fun editDates(from:LocalDate,to:LocalDate,beforeStart:LocalDate,beforeEnd:LocalDate):MusicTrip {
        if(from==beforeStart&&to==beforeEnd)return this
        requireUserAction((start==beforeStart&&end==beforeEnd)||(start==from&&end==to)){"出行日期已更新，请关闭后重新打开编辑"}
        return changeDates(from,to)
    }
    fun editDetails(nextName:String,nextCities:List<MusicTripCity>,beforeName:String,beforeCities:List<String>):MusicTrip {
        val cleanName=nextName.trim();val cleanCities=nextCities.distinctBy{it.key}
        val nameChanged=cleanName!=beforeName;val citiesChanged=cleanCities.map{it.key}!=beforeCities
        requireUserAction(!citiesChanged||cleanCities.size<=MAX_DESTINATIONS){"每个行程最多 $MAX_DESTINATIONS 个目的地"}
        requireUserAction(!nameChanged||name==beforeName||name==cleanName){"行程名称已更新，请关闭后重新打开编辑"}
        requireUserAction(!citiesChanged||cities.map{it.key}==beforeCities||cities==cleanCities){"行程城市已更新，请关闭后重新打开编辑"}
        val mergedCities=if(citiesChanged)cleanCities else cities
        requireUserAction(stops.all{stop->mergedCities.any{it.name==stop.city&&it.country==stop.country}}){"请先移出该目的地的活动，再取消这个目的地"}
        return copy(name=if(nameChanged)cleanName else name,cities=mergedCities)
    }
    fun add(place:Place,day:Int):MusicTrip {
        requireUserAction(day in 1..days){"请输入1至${days}之间的天数"}
        requireUserAction(stops.none{it.placeId==place.id&&it.day==day}){"这个地点已经安排在第${day}天，可查看原安排或选择另一天"}
        val placeCity=MusicTripCity(place.city,place.country)
        val destination=cities.firstOrNull{it.key==placeCity.key}
            ?:cities.firstOrNull{it.pickerKey==placeCity.pickerKey}
            ?:placeCity.asPickerDestination()
        val destinations=if(cities.any{it.key==destination.key}) cities else cities+destination
        requireUserAction(destinations===cities||destinations.size<=MAX_DESTINATIONS){"每个行程最多 $MAX_DESTINATIONS 个目的地"}
        val stop=MusicTripStop(UUID.randomUUID().toString(),place.id,place.name,destination.name,destination.country,day)
        val index=stops.indexOfLast{it.day==day}.takeIf{it>=0}?.plus(1) ?: stops.indexOfFirst{it.day==null||(it.day?:0)>day}.takeIf{it>=0} ?: stops.size
        return copy(cities=destinations,stops=stops.toMutableList().apply{add(index,stop)})
    }
    fun move(id:String,day:Int,index:Int):MusicTrip {
        requireUserAction(day in 1..days){"请输入有效的天数"}
        val stop=stops.find{it.id==id} ?: return this
        requireUserAction(stop.placeId==null||stops.none{it.id!=id&&it.placeId==stop.placeId&&it.day==day}){"这个地点已经安排在第${day}天，请选择另一天"}
        val remaining=stops.filterNot{it.id==id}.toMutableList()
        val group=remaining.filter{it.day==day}
        val slot=index.coerceIn(0,group.size)
        val absolute=if(slot<group.size)remaining.indexOfFirst{it.id==group[slot].id} else if(group.isNotEmpty())remaining.indexOfLast{it.day==day}+1 else remaining.indexOfFirst{it.day==null||it.day>day}.takeIf{it>=0} ?: remaining.size
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
        val before=JSONObject().put("trips",JSONArray(preferences.getString("$owner:trips","[]")))
        val array=MusicTripCodec.encode(values, before.getJSONArray("trips")).getJSONArray("trips")
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
        MusicTripCodec.decode(merged.data)
        check(preferences.edit().putString("$owner:trips",merged.data.getJSONArray("trips").toString()).libraryRemote(owner,remote,merged.dirty).commit()){"同步行程尚未保存，请重试"}
        onChanged(owner,false)
        return merged
    }
    @Synchronized fun clear(owner:String) {
        check(preferences.edit().remove("$owner:trips").remove("$owner:sync-base").remove("$owner:sync-version").remove("$owner:sync-dirty").remove("$owner:sync-generation").remove("$owner:sync-client").commit())
        onChanged(owner,false)
    }
}
