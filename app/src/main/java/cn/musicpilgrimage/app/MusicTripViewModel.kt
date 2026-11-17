package cn.musicpilgrimage.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.musicpilgrimage.app.data.*
import cn.musicpilgrimage.app.performance.LibraryDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.util.UUID

internal data class MusicTripState(val owner:String="",val trips:List<MusicTrip> = emptyList(),val busy:Boolean=false,val error:String?=null)
internal class MusicTripViewModel(app:Application):AndroidViewModel(app) {
    private val services=(app as PilgrimageApplication).services
    private val store=services.library.trips
    private val lock=Mutex()
    private val mutable=MutableStateFlow(MusicTripState())
    val state=mutable.asStateFlow()
    private fun owner()=services.userId.value?.let{"user:$it"} ?: LibraryDatabase.guestOwner(getApplication())
    init {viewModelScope.launch{combine(services.userId,services.library.changes){_,_->owner()}.collectLatest {key->lock.withLock {
        if(mutable.value.owner!=key)mutable.value=MusicTripState(key,busy=true)
        val rows=withContext(Dispatchers.IO){store.read(key)}
        if(owner()==key)mutable.value=MusicTripState(key,rows,error=mutable.value.error)
    }}}}
    private fun change(expected:String,block:(List<MusicTrip>)->List<MusicTrip>,done:()->Unit={},settled:()->Unit={},
        failureMessage:String="这次修改没有保存，请再试一次") {
        if(expected!=owner()||mutable.value.owner!=expected||mutable.value.busy){settled();return}
        mutable.value=mutable.value.copy(busy=true,error=null)
        viewModelScope.launch {lock.withLock {
            try {
                if(owner()!=expected)return@withLock
                val next=withContext(Dispatchers.IO){store.update(expected,block)}
                if(owner()==expected){mutable.value=MusicTripState(expected,next.sortedBy{it.start});done()}
            } catch(cancel:CancellationException){throw cancel}
            catch (e: Exception) {
                if (e !is UserActionException) android.util.Log.w("MusicTrips", "Unable to update the itinerary", e)
                if (owner() == expected) mutable.value = mutable.value.copy(busy = false,
                    error = (e as? UserActionException)?.message ?: failureMessage)
            }
            finally { settled() }
        }}
    }
    fun create(owner:String,name:String,start:LocalDate,end:LocalDate,cities:List<MusicTripCity>,done:(String)->Unit) {
        val id=UUID.randomUUID().toString()
        change(owner,{it+MusicTrip(id,name.trim(),start,end,cities.distinctBy{it.key})},{done(id)})
    }
    fun add(owner:String,trip:String,place:Place,date:LocalDate,requireActive:Boolean=false,done:()->Unit) = change(owner,{ rows ->
        val plan=rows.find{it.id==trip} ?: throw UserActionException("这个行程已经不存在")
        requireUserAction(!requireActive || plan.acceptsNewPlacesOn(LocalDate.now())) { "该行程已结束，请选择其他行程" }
        requireUserAction(date in plan.start..plan.end) { "出行日期已更新，请重新选择" }
        val day=(date.toEpochDay()-plan.start.toEpochDay()).toInt()+1
        val updated=plan.add(place,day)
        rows.map { if(it.id==trip) updated else it }
    },done)
    fun move(owner:String,trip:String,stop:String,day:Int,index:Int,settled:()->Unit={},done:()->Unit={})=
        change(owner,{rows->rows.map{if(it.id==trip)it.move(stop,day,index)else it}},done,settled)
    fun remove(owner:String,trip:String,stop:String,done:()->Unit={})=change(owner,{rows->rows.map{if(it.id==trip)it.copy(stops=it.stops.filterNot{s->s.id==stop})else it}},done)
    fun deleteTrip(owner:String,tripId:String,done:()->Unit)=change(owner,{trips->trips.filterNot{it.id==tripId}},done,
        failureMessage="行程没有删除，请再试一次")
    fun dates(owner:String,trip:String,start:LocalDate,end:LocalDate,beforeStart:LocalDate,beforeEnd:LocalDate,done:()->Unit)=change(owner,{rows->rows.map{if(it.id==trip)it.editDates(start,end,beforeStart,beforeEnd)else it}},done)
    fun details(owner:String,trip:String,name:String,cities:List<MusicTripCity>,beforeName:String,beforeCities:List<String>,done:()->Unit)=change(owner,{rows->rows.map{if(it.id==trip)it.editDetails(name,cities,beforeName,beforeCities)else it}},done)
    fun custom(owner:String,trip:String,name:String,city:MusicTripCity,day:Int,note:String,done:()->Unit)=change(owner,{rows->rows.map{if(it.id==trip){
        requireUserAction(name.isNotBlank()&&name.length<=100){"请填写地点名称，最多100字"}
        requireUserAction(city in it.cities&&day in 1..it.days){"请选择行程内的目的地与有效日期"}
        it.copy(stops=it.stops+MusicTripStop(UUID.randomUUID().toString(),null,name.trim(),city.name,city.country,day,note.trim()))
    }else it}},done)
    fun dismissError(){mutable.value=mutable.value.copy(error=null)}
}
