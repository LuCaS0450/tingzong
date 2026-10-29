package cn.musicpilgrimage.app.data

import android.net.Uri
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
 suspend fun list(location:String="",kind:String="",mine:Boolean=false,offset:Int=0):List<ContentItem> {
  val payload=JSONObject().put("mine",mine).put("offset",offset)
  if(location.isNotBlank())payload.put("location_id",location)
  if(kind.isNotBlank())payload.put("kind",kind)
  return (api.call("content",payload) as JSONArray).objects().map(::ContentItem)
 }
 suspend fun profile(id:String)=api.call("profile",JSONObject().put("id",id)) as JSONObject
 suspend fun submissions()=(api.call("submissions") as JSONArray).objects()
 suspend fun upload(path:String,request:String,owner:String)=api.upload(path,request,owner)
 suspend fun discardUpload(id:String,owner:String)=api.call("cancel_upload",JSONObject().put("id",id),owner)
 suspend fun save(action:String,payload:JSONObject,owner:String?=api.userId.value):Any {
  require(action in setOf("favorite","create_message","verify_visit","publish_recreation","update_content","delete_content","make_private","like","report","submit","profile_update"))
  return api.mutate(action,payload,owner)
 }
}
class AdminRepository(private val api:AppServices) {
 suspend fun load()=api.call("admin") as JSONObject
 suspend fun perform(action:String,payload:JSONObject) {
  require(action in setOf("admin_status","admin_config","admin_hide","review_submission","resolve_report","revoke_badge"))
  api.mutate(action,payload)
 }
}
class Repositories(api:AppServices) {
 val auth=AuthRepository(api)
 val catalog=CatalogRepository(api)
 val content=ContentRepository(api)
 val admin=AdminRepository(api)
}
