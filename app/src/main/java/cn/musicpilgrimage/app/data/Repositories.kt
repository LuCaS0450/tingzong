package cn.musicpilgrimage.app.data

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// Repositories own the wire protocol; screens and ViewModels own presentation state.
class AuthRepository(private val api:AppServices) {
 suspend fun self()=api.call("self") as JSONObject
 suspend fun signIn(email:String,password:String)=api.signIn(email,password)
 suspend fun signUp(email:String,password:String)=api.signUp(email,password)
 suspend fun resend(email:String)=api.resend(email)
 suspend fun reset(email:String)=api.reset(email)
 suspend fun password(value:String)=api.newPassword(value)
 suspend fun callback(uri:Uri)=api.callback(uri)
 suspend fun signOut()=api.signOut()
 suspend fun delete(owner:String)=api.call("delete_account",JSONObject().put("confirmation","删除账号"),owner)
}
class CatalogRepository(private val api:AppServices) {
 suspend fun refresh()=api.refreshCatalog()
 suspend fun search(query:String)=(api.call("search",JSONObject().put("query",query)) as JSONArray).objects()
 suspend fun nearby(fix:LocationFix)=api.call("nearby",fix.json()) as JSONObject
}
class ContentRepository(private val api:AppServices) {
 internal val likes=ContentLikes(api.scope,api.userId) { owner,id,enabled ->
  api.call("like",JSONObject().put("id",id).put("enabled",enabled),owner)
 }
 suspend fun list(location:String="",kind:String="",mine:Boolean=false,offset:Int=0):List<ContentItem> {
  val owner=api.userId.value
  val revision=likes.beginRead()
  val payload=JSONObject().put("mine",mine).put("offset",offset)
  if(location.isNotBlank())payload.put("location_id",location)
  if(kind.isNotBlank())payload.put("kind",kind)
  val rows=(api.call("content",payload,owner) as JSONArray).objects().map(::ContentItem)
  withContext(Dispatchers.Main.immediate){likes.reconcile(owner,revision,rows)}
  return rows
 }
 suspend fun profile(id:String):JSONObject {
  val owner=api.userId.value
  val revision=likes.beginRead()
  val profile=api.call("profile",JSONObject().put("id",id),owner) as JSONObject
  withContext(Dispatchers.Main.immediate) {
   val rows=listOf("visits","recreations").flatMap{profile.optJSONArray(it)?.objects().orEmpty()}.map(::ContentItem)
   likes.reconcile(owner,revision,rows)
  }
  return profile
 }
 suspend fun submissions()=(api.call("submissions") as JSONArray).objects()
 suspend fun upload(path:String,request:String,owner:String)=api.upload(path,request,owner)
 suspend fun discardUpload(id:String,owner:String)=api.call("cancel_upload",JSONObject().put("id",id),owner)
 internal suspend fun setPlaceFavorite(id:String,enabled:Boolean,owner:String) {
  api.call("favorite",JSONObject().put("location_id",id).put("enabled",enabled),owner)
 }
 suspend fun save(action:String,payload:JSONObject,owner:String?=api.userId.value):Any {
  require(action in setOf("favorite","create_message","verify_visit","publish_recreation","update_content","delete_content","make_private","like","report","submit","profile_update"))
  return api.mutate(action,payload,owner)
 }
}
class AdminRepository(private val api:AppServices) {
 suspend fun load()=api.call("admin") as JSONObject
 suspend fun perform(action:String,payload:JSONObject) {
  require(action in setOf("admin_status","admin_config","admin_hide","review_submission","resolve_report"))
  api.mutate(action,payload)
 }
}
class Repositories(api:AppServices) {
 val auth=AuthRepository(api)
 val catalog=CatalogRepository(api)
 val content=ContentRepository(api)
 val admin=AdminRepository(api)
}
