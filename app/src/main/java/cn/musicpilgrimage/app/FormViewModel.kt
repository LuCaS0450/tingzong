package cn.musicpilgrimage.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.viewModelScope
import cn.musicpilgrimage.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

class FormViewModel(app:Application):FeatureViewModel(app) {
 val fields=MutableStateFlow(JSONObject())
 val initialized=MutableStateFlow(false)
 private var owner=""
 private var key=""
 private var kind=""
 private var target=""
 private var editing:ContentItem?=null
 private var cleanSignature=""
 private var saveJob:Job?=null
 fun initialize(type:String,id:String,item:ContentItem?) {
  if(initialized.value || key.isNotEmpty())return
  owner=services.userId.value.orEmpty();kind=type;target=id;editing=item;key="$type:$id:${item?.id.orEmpty()}"
  work {
   require(type in setOf("message","visit","recreation","new_location","correction")){"此记录类型已下线"}
   require(owner.isNotBlank()){"请先登录"}
   val saved=services.drafts.read(owner,key)
   cleanSignature=RecordDraftRules.signature(type,item?.raw?:JSONObject(),item?.mediaId)
   fields.value=if(saved.length()>0)saved else JSONObject(item?.raw?.toString()?:"{}").apply{
    put("request_id",UUID.randomUUID().toString());put("id",item?.id?:UUID.randomUUID().toString())
    if(type=="correction"&&item==null)services.catalog.value.places.find{it.id==id}?.let{place->
     put("place_name",place.name)
     place.scenes.firstOrNull{it.publiclyVisible}?.let{scene->
      put("title",scene.work);put("artist",scene.artistName);put("association_type",scene.kind)
      put("source_url",scene.sourceUrl);put("association_excerpt",scene.associationExcerpt);put("canonical_work_url",scene.canonicalWorkUrl)
     }
    }
   }
   fields.value=RecordDraftRules.normalize(type,fields.value,item?.mediaId)
   initialized.value=true
  }
 }
 fun set(key:String,value:Any) {
  fields.value=RecordDraftRules.normalize(kind,JSONObject(fields.value.toString()).put(key,value),editing?.mediaId)
  persist()
 }
 private fun persist() {
  if(owner.isBlank()||key.isBlank())return
  val snapshot=JSONObject(fields.value.toString())
  val previous=saveJob
  saveJob=services.scope.launch {previous?.join();services.drafts.write(owner,key,snapshot)}
 }
 fun hasChanges()=initialized.value&&RecordDraftRules.signature(kind,fields.value,editing?.mediaId)!=cleanSignature
 fun discard(done:()->Unit)=work {
  require(services.userId.value==owner&&owner.isNotBlank()){"账号已切换，请重新打开草稿"}
  saveJob?.join()
  val draft=fields.value
  val pending=buildSet {
   draft.stringOrNull("uploaded_media")?.let(::add)
   draft.optJSONArray("cancel_media")?.let{rows->for(i in 0 until rows.length())add(rows.getString(i))}
  }
  for(media in pending)repositories.content.discardUpload(media,owner)
  services.drafts.remove(owner,key)
  services.photos.delete(draft.stringOrNull("local_photo"));services.photos.delete(draft.stringOrNull("thumbnail"))
  fields.value=JSONObject(editing?.raw?.toString()?:"{}")
  cleanSignature=RecordDraftRules.signature(kind,fields.value,editing?.mediaId)
  done()
 }
 fun photo(uri:Uri,finished:()->Unit={})=work {
  try {
  require(services.userId.value==owner && owner.isNotBlank()){"账号已切换，请重新打开草稿"}
  val paths=withContext(Dispatchers.IO){services.photos.import(uri,services.photos.draftId(owner))}
  if(services.userId.value!=owner) {
   services.photos.delete(paths.first);services.photos.delete(paths.second)
   error("账号已切换，请重新打开草稿")
  }
  val previousPhoto=fields.value.stringOrNull("local_photo")
  val previousThumbnail=fields.value.stringOrNull("thumbnail")
  val next=RecordDraftRules.importedPhoto(retireUploadedPhoto(fields.value),paths.first,paths.second).put("request_id",UUID.randomUUID().toString())
  saveJob?.join();services.drafts.write(owner,key,next)
  fields.value=next
  services.photos.delete(previousPhoto);services.photos.delete(previousThumbnail)
  } finally {finished()}
 }
 private fun retireUploadedPhoto(value:JSONObject):JSONObject {
  val draft=JSONObject(value.toString())
  draft.stringOrNull("uploaded_media")?.let { id ->
   val pending=draft.optJSONArray("cancel_media") ?: JSONArray()
   if((0 until pending.length()).none{pending.optString(it)==id})pending.put(id)
   draft.put("cancel_media",pending)
  }
  draft.remove("uploaded_media");return draft
 }
 fun removePhoto()=work {
  require(services.userId.value==owner && owner.isNotBlank()){"账号已切换，请重新打开草稿"}
  val previousPhoto=fields.value.stringOrNull("local_photo")
  val previousThumbnail=fields.value.stringOrNull("thumbnail")
  val next=RecordDraftRules.normalize(kind,retireUploadedPhoto(fields.value).apply{remove("local_photo");remove("thumbnail");put("clear_media",true)},editing?.mediaId)
  saveJob?.join();services.drafts.write(owner,key,next);fields.value=next
  services.photos.delete(previousPhoto);services.photos.delete(previousThumbnail)
 }
 fun submit(done:()->Unit)=work {
  require(services.userId.value==owner && owner.isNotBlank()){"账号已切换，请重新打开草稿"}
  // Only an explicit submit retries cleanup. There is no background publication queue.
  val cancel=fields.value.optJSONArray("cancel_media")
  if(cancel!=null) {
   for(i in 0 until cancel.length())repositories.content.discardUpload(cancel.getString(i),owner)
   fields.value=JSONObject(fields.value.toString()).apply{remove("cancel_media")}
   saveJob?.join();services.drafts.write(owner,key,fields.value)
  }
  val payload=RecordDraftRules.normalize(kind,fields.value,editing?.mediaId).put("location_id",target).put("scene_id",target)
  val body=payload.optString("body").trim()
  require(body.length<=1000){"文字最多1000字"}
  if(kind=="message")require(body.isNotBlank()){"请填写内容"}
  if(kind in listOf("new_location","correction")){
   require(payload.optString("association_type") in setOf("mv","album","song_title","lyric")){"请选择作品与地点的关联类型"}
   require(payload.optString("association_excerpt").length<=200){"关联文字最多200字"}
   if(payload.optString("association_type") in setOf("song_title","lyric"))require(payload.optString("association_excerpt").isNotBlank()){"请填写歌名或提到地点的短歌词"}
  }
  val local=payload.stringOrNull("local_photo")
  payload.remove("media_id")
  if(RecordDraftRules.requiresLocation(kind,editing!=null,local!=null)) {
   notice.value="正在获取本次现场位置…"
   val fix=services.location.locate()
   val place=services.catalog.value.places.find{if(kind=="visit")it.id==target else it.scenes.any{s->s.id==target}} ?: error("地点不存在")
   ArrivalRules.rejection(place,fix)?.let{error(it)}
   payload.put("fix",fix.json())
  }
  // Validate the live location before transferring the image. GPS never enters the draft.
  if(local!=null) {
   val media=repositories.content.upload(local,payload.optString("request_id"),owner)
   payload.put("media_id",media)
   fields.value=JSONObject(fields.value.toString()).put("uploaded_media",media)
   saveJob?.join();services.drafts.write(owner,key,fields.value)
  }
  payload.remove("local_photo");payload.remove("thumbnail");payload.remove("uploaded_media");payload.remove("cancel_media")
  val action=when {
   kind in listOf("new_location","correction") -> "submit"
   editing!=null && kind=="recreation" && local!=null -> "publish_recreation"
   editing!=null -> "update_content"
   kind=="visit" -> "verify_visit"
   kind=="recreation" -> "publish_recreation"
   else -> "create_message"
  }
  if(kind in listOf("new_location","correction"))payload.put("submission_type",kind).put("description",body).put("location_id",if(kind=="correction")target else "")
  repositories.content.save(action,payload,owner)
  saveJob?.join();services.drafts.remove(owner,key)
  services.photos.delete(local);services.photos.delete(fields.value.stringOrNull("thumbnail"))
  cleanSignature=RecordDraftRules.signature(kind,fields.value,editing?.mediaId)
  done()
 }
}
