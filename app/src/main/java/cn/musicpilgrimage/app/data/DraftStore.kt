package cn.musicpilgrimage.app.data
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.security.MessageDigest

private val Context.draftDataStore by preferencesDataStore("v2-drafts")
class DraftStore(private val context:Context) {
 private fun ownerPrefix(owner:String)=MessageDigest.getInstance("SHA-256").digest(owner.toByteArray()).joinToString("") { "%02x".format(it) }+":"
 private fun key(owner:String,form:String)=stringPreferencesKey(ownerPrefix(owner)+form)
 suspend fun read(owner:String,form:String):JSONObject = if(form.startsWith("capsule:"))JSONObject() else context.draftDataStore.data.first()[key(owner,form)]?.let {
  runCatching { JSONObject(it) }.getOrNull()
 } ?: JSONObject()
 suspend fun write(owner:String,form:String,value:JSONObject) {
  require(owner.isNotBlank())
  val copy=JSONObject(value.toString()).apply { remove("fix") }
  context.draftDataStore.edit { it[key(owner,form)]=copy.toString() }
 }
 suspend fun remove(owner:String,form:String) { context.draftDataStore.edit { it.remove(key(owner,form)) } }
 /** Keys are owner-hash:kind:target:record. Remove only the retired kind, across owners. */
 suspend fun retireCapsules():Int {
  var removed=0
  context.draftDataStore.edit { prefs ->
   val keys=prefs.asMap().keys.filter { it.name.substringAfter(':',"").startsWith("capsule:") }
   removed=keys.size
   keys.forEach { prefs.remove(stringPreferencesKey(it.name)) }
  }
  return removed
 }
 suspend fun clearOwner(owner:String) {
  val paths=mutableSetOf<String>()
  context.draftDataStore.edit { prefs ->
   prefs.asMap().filterKeys { it.name.startsWith(ownerPrefix(owner)) }.forEach { (key,value) ->
    runCatching { JSONObject(value.toString()) }.getOrNull()?.let { draft ->
     listOf("local_photo","thumbnail").forEach { field -> draft.stringOrNull(field)?.let(paths::add) }
    }
    prefs.remove(stringPreferencesKey(key.name))
   }
  }
  kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
   val photos=PhotoFiles(context);paths.forEach(photos::delete);photos.deleteOwner(owner)
  }
 }
}
