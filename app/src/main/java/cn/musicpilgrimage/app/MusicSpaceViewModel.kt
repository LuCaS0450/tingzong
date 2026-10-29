package cn.musicpilgrimage.app

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.musicpilgrimage.app.data.MusicCatalogue
import cn.musicpilgrimage.app.data.MusicFavorites
import cn.musicpilgrimage.app.data.MusicFavoriteStore
import cn.musicpilgrimage.app.data.RemovedMusicFavorite
import cn.musicpilgrimage.app.performance.LibraryDatabase
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
data class MusicFavoriteUndo(val token: Long, val label: String)

class MusicSpaceViewModel(private val app: Application) : AndroidViewModel(app) {
    private val services = (app as PilgrimageApplication).services
    private val store = services.library.favorites
    internal val librarySync=services.library.state
    fun syncLibrary()=services.library.retry()
    private fun ownerKey() = services.userId.value?.let { "user:$it" } ?: LibraryDatabase.guestOwner(app)
    private fun readFavorites() = store.read(ownerKey())
    private val contextPreferences=app.getSharedPreferences("music-space-context",Context.MODE_PRIVATE)
    private val recentMutable=MutableStateFlow(contextPreferences.getString("${ownerKey()}:work",null))
    val recentWork=recentMutable.asStateFlow()
    val catalog = services.catalog.map(MusicCatalogue::from)
        .stateIn(viewModelScope, SharingStarted.Eagerly, MusicCatalogue.from(services.catalog.value))
    private val favoriteState = MutableStateFlow(readFavorites())
    val favorites = favoriteState.asStateFlow()
    private val undoState = MutableStateFlow<MusicFavoriteUndo?>(null)
    val undo = undoState.asStateFlow()
    private var removed: RemovedMusicFavorite? = null
    private var sequence = 0L
    init {
        viewModelScope.launch { services.userId.collect { favoriteState.value = readFavorites();recentMutable.value=contextPreferences.getString("${ownerKey()}:work",null);removed=null;undoState.value=null } }
        viewModelScope.launch {services.library.changes.collect{favoriteState.value=readFavorites()}}
    }
    fun browseWork(id:String) {if(id==recentMutable.value)return;contextPreferences.edit().putString("${ownerKey()}:work",id).apply();recentMutable.value=id}
    /** The local commit completes first; the shared library schedules an owner-scoped worker. */
    fun toggleFavorite(id: String, release: Boolean = false) {
        val owner=ownerKey()
        val current = readFavorites()
        val selected = if (release) current.releases else current.songs
        try {if(id in selected)store.remove(owner,id,release) else store.add(owner,id,release)}catch(e:Exception){services.library.localFailure(e.message?:"收藏尚未保存，请重试");return}
        removed=null;undoState.value=null
        favoriteState.value=readFavorites()
    }
    fun removeFavorite(id:String,release:Boolean) {
        val owner=ownerKey()
        val removal=try{store.remove(owner,id,release)}catch(e:Exception){services.library.localFailure(e.message?:"收藏尚未保存，请重试");return} ?: return
        removed=removal
        favoriteState.value=readFavorites()
        undoState.value=MusicFavoriteUndo(++sequence,if(release)"已取消专辑收藏" else "已取消歌曲收藏")
    }
    fun undoRemoval(token:Long) {
        if(undoState.value?.token!=token)return
        try{removed?.let{store.undo(ownerKey(),it)}}catch(e:Exception){services.library.localFailure(e.message?:"收藏尚未保存，请重试");return}
        favoriteState.value=readFavorites()
        dismissUndo(token)
    }
    fun dismissUndo(token:Long) {
        if(undoState.value?.token==token){undoState.value=null;removed=null}
    }
}
