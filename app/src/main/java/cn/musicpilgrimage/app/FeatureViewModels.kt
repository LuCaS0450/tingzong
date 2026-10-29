package cn.musicpilgrimage.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.musicpilgrimage.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject

open class FeatureViewModel(app:Application):AndroidViewModel(app) {
 val services=(app as PilgrimageApplication).services
 val repositories=services.repositories
 val busy=MutableStateFlow(false)
 val error=MutableStateFlow<String?>(null)
 val notice=MutableStateFlow<String?>(null)
 protected fun work(block:suspend ()->Unit):Job = viewModelScope.launch {
  if(busy.value) return@launch
  val owner=services.userId.value
  busy.value=true;error.value=null
  try { block() } catch(e:CancellationException) { throw e }
  catch(e:Exception) { if(services.userId.value==owner)error.value=friendlyError(e) }
  finally { busy.value=false }
 }
 fun clearError() { error.value=null }
 fun mutation(action:String,p:JSONObject=JSONObject(),done:()->Unit={}) = work { repositories.content.save(action,p);done() }
 companion object {
  fun friendlyError(e:Exception):String = when {
   e is ApiException || e is IllegalArgumentException || e is IllegalStateException -> e.message ?: "操作未完成，请重试"
   e is java.io.IOException -> "网络连接失败，请检查网络后手动重试"
   e.message.orEmpty().contains("Email not confirmed",true) -> "邮箱尚未验证，请打开验证邮件后登录"
   e.message.orEmpty().contains("Invalid login credentials",true) -> "邮箱或密码不正确"
   e.message.orEmpty().contains("rate",true) -> "请求较频繁，请稍后重试"
   else -> "操作未完成，请检查网络、账号状态后重试"
  }
 }
}

class SessionViewModel(app:Application):FeatureViewModel(app) {
 val profile=MutableStateFlow<JSONObject?>(null)
 val signedIn=services.userId
 val recovery=services.recovery
 val openAuth=MutableStateFlow(false)
 init { viewModelScope.launch {
  combine(services.userId,services.changed) { id,_ -> id }.collectLatest { id ->
   if(profile.value?.optString("id")!=id){profile.value=null;error.value=null;notice.value=null}
   if(id!=null) try { val result=repositories.auth.self();if(services.userId.value==id) profile.value=result }
   catch(e:CancellationException){throw e} catch(e:Exception){error.value=friendlyError(e)}
  }
 } }
 fun authenticate(email:String,password:String,register:Boolean) = work {
  if(register) { repositories.auth.signUp(email,password);notice.value="验证邮件已发送，请在本手机打开邮件中的链接，再返回登录" }
  else repositories.auth.signIn(email,password)
 }
 fun reloadProfile()=work {
  val owner=services.userId.value ?: return@work
  val refreshed=repositories.auth.self()
  if(services.userId.value==owner)profile.value=refreshed
 }
 fun resend(email:String) = work { repositories.auth.resend(email);notice.value="已请求重发验证邮件，请同时检查垃圾邮件" }
 fun reset(email:String) = work { repositories.auth.reset(email);notice.value="如果邮箱已注册，你将收到重置密码邮件" }
 fun password(value:String,done:()->Unit) = work { repositories.auth.password(value);done() }
 fun updateProfile(value:JSONObject,done:()->Unit)=work {
  val expected=services.userId.value ?: return@work
  repositories.content.save("profile_update",value)
  val refreshed=repositories.auth.self()
  if(services.userId.value==expected){profile.value=refreshed;done()}
 }
 fun callback(uri:Uri) = work { openAuth.value=true;repositories.auth.callback(uri);notice.value="邮箱验证已完成" }
 fun signOut(done:()->Unit) = work { repositories.auth.signOut();profile.value=null;done() }
 fun deleteAccount(done:()->Unit) = work {
  val owner=services.userId.value ?: return@work
  repositories.auth.delete(owner)
  services.library.clearAccount(owner)
  services.drafts.clearOwner(owner)
  repositories.auth.signOut();profile.value=null;done()
 }
}

data class SearchHit(val place:Place,val matched:String="")
class ExploreViewModel(app:Application):FeatureViewModel(app) {
 val query=MutableStateFlow("")
 val hits=MutableStateFlow<List<SearchHit>>(emptyList())
 val offline=MutableStateFlow(true)
 val fix=MutableStateFlow<LocationFix?>(null)
 init {
  viewModelScope.launch { services.userId.drop(1).collect { error.value=null } }
  viewModelScope.launch { combine(query,services.catalog){q,c->q to c}.collectLatest { (q,c) ->
   hits.value=CatalogSearch.filter(c.places,c.artists,q).map { SearchHit(it) }
   delay(350)
   if(q.isNotBlank()) try {
    val remote=repositories.catalog.search(q)
    hits.value=remote.mapNotNull { r->c.places.find { it.id==r.optString("id") }?.let { SearchHit(it,r.optString("matched")) } }
    offline.value=false
   } catch(e:CancellationException){throw e} catch(_:Exception){offline.value=true}
  } }
  refresh()
 }
 fun refresh()=work { repositories.catalog.refresh();offline.value=false }
 fun nearby()=work {
  val location=services.location.locate();fix.value=location
  hits.value=hits.value.sortedBy { ArrivalRules.distanceMeters(location.latitude,location.longitude,it.place.latitude,it.place.longitude) }
 }
}

class PlaceViewModel(app:Application):FeatureViewModel(app) {
 val items=MutableStateFlow<List<ContentItem>>(emptyList())
 val ownItems=MutableStateFlow<List<ContentItem>>(emptyList())
 val loaded=MutableStateFlow(false)
 private var locationId=""
 init { viewModelScope.launch { services.userId.drop(1).collect { items.value=emptyList();ownItems.value=emptyList();loaded.value=false;error.value=null } } }
 fun load(id:String,more:Boolean=false)=work {
  if(locationId!=id) {items.value=emptyList();ownItems.value=emptyList();loaded.value=false};locationId=id
  val owner=services.userId.value
  if(owner==null){loaded.value=true;return@work}
  val next=repositories.content.list(location=id,offset=if(more)items.value.size else 0)
  val own=if(more)ownItems.value else repositories.content.list(location=id,mine=true)
  if(owner==services.userId.value) {items.value=(if(more)items.value else emptyList())+next;ownItems.value=own;loaded.value=true}
 }
}

class PersonalViewModel(app:Application):FeatureViewModel(app) {
 private var contentKind=""
 private var profileId=""
 val items=MutableStateFlow<List<ContentItem>>(emptyList())
 val submissions=MutableStateFlow<List<JSONObject>>(emptyList())
 val publicProfile=MutableStateFlow<JSONObject?>(null)
 init { viewModelScope.launch { services.userId.drop(1).collect { items.value=emptyList();submissions.value=emptyList();publicProfile.value=null;error.value=null } } }
 fun load(kind:String,more:Boolean=false)=work {
  if(contentKind!=kind){items.value=emptyList();contentKind=kind}
  val owner=services.userId.value ?: return@work
  val rows=repositories.content.list(kind=kind,mine=true,offset=if(more)items.value.size else 0)
  if(owner==services.userId.value)items.value=(if(more)items.value else emptyList())+rows
 }
 fun submissions()=work { submissions.value=emptyList();submissions.value=repositories.content.submissions() }
 fun profile(id:String)=work {
  val owner=services.userId.value
  if(profileId!=id){publicProfile.value=null;profileId=id}
  val next=repositories.content.profile(id)
  if(owner==services.userId.value&&profileId==id)publicProfile.value=next
 }
}

class AdminViewModel(app:Application):FeatureViewModel(app) {
 val data=MutableStateFlow<JSONObject?>(null)
 init { viewModelScope.launch { services.userId.drop(1).collect { data.value=null;error.value=null } } }
 fun load()=work { data.value=null;data.value=repositories.admin.load() }
 fun act(action:String,p:JSONObject)=work {repositories.admin.perform(action,p);data.value=repositories.admin.load()}
}
