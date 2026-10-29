package cn.musicpilgrimage.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.musicpilgrimage.app.data.AccountAvatar
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject

data class MusicProfileDraft(
    val owner:String?=null,val nickname:String="",val originalNickname:String="",
    val avatar:String?=null,val originalAvatar:String?=null,val loading:Boolean=false,
    val saving:Boolean=false,val processingPhoto:Boolean=false,val error:String?=null,val active:Boolean=false
) {val dirty get()=nickname!=originalNickname||avatar!=originalAvatar||processingPhoto}

class MusicProfileEditorViewModel(app:Application):AndroidViewModel(app) {
    private val services=(app as PilgrimageApplication).services
    private val mutable=MutableStateFlow(MusicProfileDraft())
    val state=mutable.asStateFlow()
    private var bio=""
    private var photoJob:Job?=null
    private var photoRevision=0
    fun load(force:Boolean=false){
        val owner=services.userId.value ?: return
        if(mutable.value.owner==owner&&(mutable.value.loading||mutable.value.dirty||mutable.value.active&&!force))return
        mutable.value=MusicProfileDraft(owner=owner,loading=true)
        viewModelScope.launch {
            try {
                val profile=services.repositories.auth.self()
                if(services.userId.value==owner){bio=profile.optString("bio");val name=profile.optString("nickname");val avatar=services.accountAvatar(owner);mutable.value=MusicProfileDraft(owner,name,name,avatar,avatar,active=profile.optString("status")=="active")}
            }catch(e:CancellationException){throw e}
            catch(e:Exception){if(services.userId.value==owner)mutable.value=mutable.value.copy(loading=false,error=FeatureViewModel.friendlyError(e))}
        }
    }
    fun nickname(value:String){if(!mutable.value.saving)mutable.value=mutable.value.copy(nickname=value.take(32),error=null)}
    fun choosePhoto(uri:Uri){
        val owner=mutable.value.owner?.takeIf{it==services.userId.value} ?: return
        if(mutable.value.saving)return
        val version=++photoRevision;photoJob?.cancel()
        mutable.value=mutable.value.copy(processingPhoto=true,error=null)
        photoJob=viewModelScope.launch {
            try {
                val encoded=withContext(Dispatchers.IO){AccountAvatar.import(getApplication(),services.photos,uri,owner)}
                if(services.userId.value==owner&&version==photoRevision)mutable.value=mutable.value.copy(avatar=encoded,processingPhoto=false)
            }catch(e:CancellationException){throw e}
            catch(e:Exception){if(services.userId.value==owner&&version==photoRevision)mutable.value=mutable.value.copy(processingPhoto=false,error=FeatureViewModel.friendlyError(e))}
        }
    }
    fun removePhoto(){if(!mutable.value.saving){photoJob?.cancel();photoRevision++;mutable.value=mutable.value.copy(avatar=null,processingPhoto=false)}}
    fun discard(){photoRevision++;photoJob?.cancel();mutable.value=mutable.value.copy(nickname=mutable.value.originalNickname,avatar=mutable.value.originalAvatar,processingPhoto=false,error=null)}
    fun save(done:(JSONObject)->Unit){
        val draft=mutable.value
        val owner=draft.owner?.takeIf{it==services.userId.value} ?: return
        if(draft.saving||draft.processingPhoto||!draft.active)return
        if(draft.nickname.trim().length !in 2..32){mutable.value=draft.copy(error="昵称需要2—32个字");return}
        mutable.value=draft.copy(saving=true,error=null)
        viewModelScope.launch {
            var nicknameSaved=false
            var avatarSaved=false
            try {
                if(draft.nickname.trim()!=draft.originalNickname){
                    services.repositories.content.save("profile_update",JSONObject().put("nickname",draft.nickname.trim()).put("bio",bio),owner)
                    if(services.userId.value!=owner)return@launch
                    nicknameSaved=true
                    mutable.value=mutable.value.copy(nickname=draft.nickname.trim(),originalNickname=draft.nickname.trim())
                }
                if(draft.avatar!=draft.originalAvatar){services.updateAccountAvatar(draft.avatar,owner);avatarSaved=true;mutable.value=mutable.value.copy(originalAvatar=draft.avatar)}
                val profile=services.repositories.auth.self()
                if(services.userId.value==owner){mutable.value=mutable.value.copy(saving=false);services.changed.update{it+1};done(profile)}
            }catch(e:CancellationException){throw e}
            catch(e:Exception){if(services.userId.value==owner)mutable.value=mutable.value.copy(saving=false,error=(if(nicknameSaved&&draft.avatar!=draft.originalAvatar&&!avatarSaved)"昵称已保存；头像更新未完成，请重试。" else if(nicknameSaved||avatarSaved)"资料已保存，暂时无法刷新，请重试。" else FeatureViewModel.friendlyError(e)))}
        }
    }
}
