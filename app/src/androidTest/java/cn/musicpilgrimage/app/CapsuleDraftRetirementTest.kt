package cn.musicpilgrimage.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import cn.musicpilgrimage.app.data.DraftStore
import cn.musicpilgrimage.app.data.ApiException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CapsuleDraftRetirementTest {
 @Test fun realEdgeRejectsRetiredActionAndProfileHasNoCapsuleBadge()=runBlocking {
  val app=ApplicationProvider.getApplicationContext<PilgrimageApplication>()
  val services=app.services
  services.ready()
  val owner=withTimeout(20000){services.userId.first{it!=null}}!!
  val failure=runCatching{services.call("create_capsule",JSONObject(),owner)}.exceptionOrNull()
  assertTrue("Retired action must use the structured API error",failure is ApiException)
  assertEquals("feature_disabled",(failure as ApiException).reason)
  val profile=services.call("self",expectedOwner=owner) as JSONObject
  val badges=profile.getJSONArray("badges")
  assertTrue((0 until badges.length()).none{badges.getJSONObject(it).optString("id")=="first_capsule"})
 }
 @Test fun removesOnlyCapsulesAcrossOwnersAndIsRepeatable()=runBlocking {
  val store=DraftStore(InstrumentationRegistry.getInstrumentation().targetContext)
  val owners=listOf("capsule-retirement-test-a","capsule-retirement-test-b")
  store.retireCapsules()
  try {
   owners.forEach { owner ->
    store.write(owner,"capsule:new:",JSONObject().put("body","retired"))
    store.write(owner,"message:capsule-place:",JSONObject().put("body",owner))
    store.write(owner,"recreation:scene:",JSONObject().put("body","retained"))
   }
   assertEquals(2,store.retireCapsules())
   assertEquals(0,store.retireCapsules())
   owners.forEach { owner ->
    assertEquals(0,store.read(owner,"capsule:new:").length())
    assertEquals(owner,store.read(owner,"message:capsule-place:").getString("body"))
    assertEquals("retained",store.read(owner,"recreation:scene:").getString("body"))
   }
  } finally {owners.forEach {store.clearOwner(it)}}
 }
}
