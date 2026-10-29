package cn.musicpilgrimage.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class MusicMapViewModel(app:Application,private val saved:SavedStateHandle):AndroidViewModel(app) {
    private val services=(app as PilgrimageApplication).services
    val city=saved.getStateFlow("city","")
    val lastCity=saved.getStateFlow("lastCity","")
    val selected=saved.getStateFlow("selected","")
    val full=saved.getStateFlow("full",false)
    val all=saved.getStateFlow("all",false)
    val camera=saved.getStateFlow<List<Double>?>("camera",null)
    private val noticeMutable=MutableStateFlow<String?>(null)
    val notice=noticeMutable.asStateFlow()
    private var refreshing=false
    fun source(value:String) {if(saved.get<String>("source")!=value){saved["source"]=value;saved["all"]=false}}
    fun scope(work:String) {
        if(saved.get<String>("scope")==work)return
        saved["scope"]=work;saved["city"]="";saved["selected"]="";saved["full"]=false
        saved["lastCity"]=saved.get<String>("lastCity:$work").orEmpty();saved["camera"]=null
    }
    private fun memory(label:String)=label+":"+saved.get<String>("scope").orEmpty()+":"+city.value
    fun chooseCity(key:String) {
        saved["city"]=key;saved["lastCity"]=key;saved["full"]=false
        saved["lastCity:${saved.get<String>("scope").orEmpty()}"]=key
        saved["selected"]=saved.get<String>(memory("place")).orEmpty()
        saved["camera"]=saved.get<List<Double>>(memory("camera"))
    }
    fun select(expectedCity:String,id:String){if(city.value!=expectedCity)return;saved["selected"]=id;saved[memory("place")]=id}
    fun full(value:Boolean){saved["full"]=value}
    fun back():Boolean=when {full.value->{full(false);true};city.value.isNotEmpty()->{saved["city"]="";true};else->false}
    fun all(value:Boolean){saved["all"]=value}
    fun camera(expectedScope:String,expectedCity:String,values:List<Double>) {
        // A disposing old MapView may report its last camera after another city opens.
        if(values.size!=3||values.any{!it.isFinite()}||values[0] !in -90.0..90.0||values[1] !in -180.0..180.0||values[2] !in 0.0..24.0)return
        saved["camera:$expectedScope:$expectedCity"]=ArrayList(values)
        if(saved.get<String>("scope")==expectedScope&&city.value==expectedCity)saved["camera"]=ArrayList(values)
    }
    fun refresh() {
        if(refreshing)return
        refreshing=true
        viewModelScope.launch {
            try{services.repositories.catalog.refresh();noticeMutable.value=null}
            catch(cancel:CancellationException){throw cancel}
            catch(_:Exception){noticeMutable.value="正在使用本机地点目录"}
            finally{refreshing=false}
        }
    }
}
