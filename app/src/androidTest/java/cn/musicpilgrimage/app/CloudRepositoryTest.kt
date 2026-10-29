package cn.musicpilgrimage.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Opt-in real cloud check; the runner supplies disposable identities in app-private storage. */
class CloudRepositoryTest {
 @Test fun realAuthRepositoriesKeepAccountsAndDraftsSeparate()=runBlocking {
  assumeTrue(InstrumentationRegistry.getArguments().getString("cloudLive")=="true")
  val app=ApplicationProvider.getApplicationContext<PilgrimageApplication>()
  val file=File(app.filesDir,"v2-live-test.json")
  val credentials=JSONObject(file.readText()).also { check(file.delete()) }
  val a=credentials.getJSONObject("a");val b=credentials.getJSONObject("b")
  val services=app.services;val repositories=services.repositories
  suspend fun signIn(user:JSONObject) {
   repositories.auth.signIn(user.getString("email"),user.getString("password"))
   withTimeout(10000){services.userId.first{it==user.getString("id")}}
  }
  var message:String?=null
  try {
   services.ready();repositories.auth.signOut()
   signIn(a)
   assertTrue(repositories.auth.self().getString("id")==a.getString("id"))
   repositories.content.save("profile_update",JSONObject().put("nickname","模拟器联调甲"),a.getString("id"))
   repositories.catalog.refresh()
   val place=services.catalog.value.places.first().id
   repositories.content.save("favorite",JSONObject().put("location_id",place).put("enabled",true),a.getString("id"))
   assertTrue(repositories.auth.self().getJSONArray("favorites").toString().contains(place))
   message=UUID.randomUUID().toString()
   repositories.content.save("create_message",JSONObject().put("id",message).put("location_id",place).put("body","模拟器隔离 Repository 联调"),a.getString("id"))
   services.drafts.write(a.getString("id"),"message",JSONObject().put("body","A 的本机草稿"))
   assertTrue(repositories.content.list(mine=true).isNotEmpty())
   assertNotNull(services.secure.loadSession())
   repositories.auth.signOut()
   signIn(b)
   assertTrue(repositories.auth.self().getString("id")==b.getString("id"))
   repositories.content.save("profile_update",JSONObject().put("nickname","模拟器联调乙"),b.getString("id"))
   assertEquals(0,repositories.auth.self().getJSONArray("favorites").length())
   assertTrue(repositories.content.list(mine=true).isEmpty())
   assertEquals(0,services.drafts.read(b.getString("id"),"message").length())
   assertTrue(services.drafts.read(a.getString("id"),"message").has("body"))
   var rejected=false
   try {
    repositories.content.save("favorite",JSONObject().put("location_id",place).put("enabled",true),a.getString("id"))
   } catch(e:IllegalArgumentException) { rejected=e.message.orEmpty().contains("账号已切换") }
   assertTrue("A pending write must be rejected after switching to B",rejected)
   assertEquals(0,repositories.auth.self().getJSONArray("favorites").length())
   repositories.auth.signOut()
   assertNull(services.secure.loadSession())
  } finally {
   // Cloud deletion is completed by the host runner even if device connectivity fails.
   runCatching { if(message!=null){signIn(a);repositories.content.save("delete_content",JSONObject().put("id",message),a.getString("id"))} }
   services.drafts.clearOwner(a.getString("id"));services.drafts.clearOwner(b.getString("id"))
   runCatching{repositories.auth.signOut()}
  }
 }
}
