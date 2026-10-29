package cn.musicpilgrimage.app
import androidx.test.core.app.ApplicationProvider
import cn.musicpilgrimage.app.data.DraftStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class DraftIsolationTest {
 @Test fun deletingAccountDraftsRemovesItsPhotosAndPreservesAnotherAccount()=runBlocking {
  val app=ApplicationProvider.getApplicationContext<PilgrimageApplication>()
  val store=DraftStore(app)
  val directory=java.io.File(app.filesDir,"photos").apply{mkdirs()}
  val a=java.io.File(directory,"draft-cleanup-a.jpg").apply{writeBytes(byteArrayOf(1,2,3))}
  val b=java.io.File(directory,"draft-cleanup-b.jpg").apply{writeBytes(byteArrayOf(4,5,6))}
  try {
   store.write("cleanup-a","visit",JSONObject().put("local_photo",a.path).put("uploaded_media","cloud-id"))
   store.write("cleanup-b","visit",JSONObject().put("local_photo",b.path))
   assertEquals("cloud-id",DraftStore(app).read("cleanup-a","visit").getString("uploaded_media"))
   store.clearOwner("cleanup-a")
   assertFalse(a.exists());assertTrue(b.exists())
   assertEquals(0,store.read("cleanup-a","visit").length())
   assertEquals(b.path,store.read("cleanup-b","visit").getString("local_photo"))
  } finally {store.clearOwner("cleanup-a");store.clearOwner("cleanup-b");a.delete();b.delete()}
 }
 @Test fun switchingAccountsKeepsDraftsSeparateAndNeverPersistsFix()=runBlocking {
  val app=ApplicationProvider.getApplicationContext<PilgrimageApplication>()
  val store=DraftStore(app)
  try {
   store.write("test-a","message",JSONObject().put("body","A的文字").put("fix",JSONObject().put("latitude",1)))
   store.write("test-b","message",JSONObject().put("body","B的文字"))
   val recreated=DraftStore(app)
   assertEquals("A的文字",recreated.read("test-a","message").getString("body"))
   assertFalse(recreated.read("test-a","message").has("fix"))
   recreated.clearOwner("test-a")
   assertEquals("B的文字",recreated.read("test-b","message").getString("body"))
  } finally {store.clearOwner("test-a");store.clearOwner("test-b")}
 }
}
