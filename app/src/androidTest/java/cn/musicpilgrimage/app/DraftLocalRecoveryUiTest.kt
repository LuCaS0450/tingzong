package cn.musicpilgrimage.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Process
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import cn.musicpilgrimage.app.data.*
import cn.musicpilgrimage.app.ui.PilgrimageTheme
import cn.musicpilgrimage.app.ui.room.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Two phases run in separate processes. Only isolated local draft keys are written. */
class DraftLocalRecoveryUiTest {
    @get:Rule val compose=createAndroidComposeRule<MusicSpacePreviewActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val app get()=context.applicationContext as PilgrimageApplication
    private val owner="local-draft-recovery-fixture"
    private val key="visit:local-recovery-fixture:"
    private val probe get()=context.getSharedPreferences("local-draft-restart-probe",Context.MODE_PRIVATE)
    private val body="重启后保留的到访感想"
    private fun requireGuest() {
        compose.waitUntil(12000){app.services.authReady.value}
        assumeTrue("Do not operate a signed-in account",app.services.userId.value==null)
    }
    private fun capture(name:String) {
        compose.waitForIdle();instrumentation.waitForIdleSync();Thread.sleep(250)
        val folder=File(context.getExternalFilesDir(null),"local-draft-checks").apply{mkdirs()}
        instrumentation.uiAutomation.takeScreenshot().let{bitmap->File(folder,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
    }
    @Test fun writePhotoAndDraftBeforeRestart()=runBlocking {
        requireGuest()
        val drafts=DraftStore(context);drafts.clearOwner(owner);probe.edit().clear().commit()
        val source=File(context.cacheDir,"camera/local-draft-recovery-source.jpg").apply{parentFile!!.mkdirs()}
        try {
            val bitmap=Bitmap.createBitmap(800,600,Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply {
                drawColor(Color.rgb(231,223,205))
                drawRect(0f,0f,800f,180f,Paint().apply{color=Color.rgb(61,91,110)})
                drawText("DRAFT TEST PHOTO",65f,340f,Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.DKGRAY;textSize=50f})
            }
            source.outputStream().use{bitmap.compress(Bitmap.CompressFormat.JPEG,90,it)};bitmap.recycle()
            ExifInterface(source).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_ROTATE_90.toString())
                setLatLong(12.0,34.0) // Synthetic metadata; no location is obtained.
                saveAttributes()
            }
            val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",source)
            val paths=PhotoFiles(context).import(uri,PhotoFiles(context).draftId(owner))
            drafts.write(owner,key,JSONObject().put("body",body).put("visibility","private")
                .put("local_photo",paths.first).put("thumbnail",paths.second)
                .put("fix",JSONObject().put("latitude",12).put("longitude",34)))
            val saved=drafts.read(owner,key)
            assertEquals(body,saved.getString("body"));assertFalse(saved.has("fix"))
            assertTrue(probe.edit().putInt("writer-pid",Process.myPid()).putBoolean("ready",true).commit())
        } catch(error:Throwable) {drafts.clearOwner(owner);probe.edit().clear().commit();throw error}
        finally {source.delete()}
    }

    /** Actual editor registration and discard guard, with local form fields only; no Auth or VM owner is injected. */
    @Test fun restorePhotoThenGuardBackAndModuleExit()=runBlocking {
        requireGuest()
        val drafts=DraftStore(context);val vm=FormViewModel(app)
        try {
            assertTrue("Run write phase before the real process stop",probe.getBoolean("ready",false))
            assertNotEquals(probe.getInt("writer-pid",0),Process.myPid())
            val restored=drafts.read(owner,key)
            assertEquals(body,restored.getString("body"));assertFalse(restored.has("fix"))
            val photo=File(restored.getString("local_photo"));val thumbnail=File(restored.getString("thumbnail"))
            assertTrue(photo.isFile);assertTrue(thumbnail.isFile)
            val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
            BitmapFactory.decodeFile(photo.path,bounds)
            assertEquals(600,bounds.outWidth);assertEquals(800,bounds.outHeight)
            assertNull(ExifInterface(photo).getAttribute(ExifInterface.TAG_GPS_LATITUDE))
            assertTrue(photo.length()<=400*1024)
            vm.fields.value=JSONObject(restored.toString());vm.initialized.value=true
            var guard:(((()->Unit)->Unit))?=null
            var exits=0
            val leave:(()->Unit)->Unit={action->guard?.invoke(action)?:action()}
            compose.runOnUiThread{compose.activity.setContent{PilgrimageTheme{RoomWall(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                BackHandler{leave{exits++}}
                Column {
                    RoomHeader("本机草稿恢复检查",onBack={leave{exits++}},onSpace={})
                    TextButton({leave{exits++}}){Text("切换模块（检查）")}
                    Box(Modifier.weight(1f)){RoomRecordEditor("visit","local-recovery-fixture",null,vm,
                        {leave{exits++}},{fail("No save is performed")},{fail("No camera is opened")},
                        {guard=it},{})}
                }
            }}}}
            compose.waitUntil(5000){guard!=null}
            compose.onNodeWithTag("room-record-body").assertTextContains(body)
            compose.waitUntil(10000){compose.onAllNodes(hasTestTag("room-record-photo") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("room-record-photo").performScrollTo();capture("restored-photo")
            compose.onNodeWithText("切换模块（检查）").performClick()
            compose.onNodeWithText("放弃未保存的修改？").assertIsDisplayed();assertEquals(0,exits)
            compose.onNodeWithText("继续编辑").performClick()
            compose.onNodeWithTag("room-record-body").performScrollTo().assertTextContains(body)
            compose.runOnUiThread{compose.activity.onBackPressedDispatcher.onBackPressed()}
            compose.onNodeWithText("放弃未保存的修改？").assertIsDisplayed();capture("leave-confirmation")
            compose.onNodeWithText("放弃修改").performClick()
            compose.waitUntil(5000){!vm.busy.value&&vm.error.value!=null}
            compose.onNode(hasText("账号已切换，请重新打开草稿") and hasAnyAncestor(isDialog())).assertIsDisplayed()
            assertEquals(0,exits);assertTrue(photo.isFile);assertTrue(thumbnail.isFile)
            assertEquals(body,drafts.read(owner,key).getString("body"));capture("discard-blocked")
            compose.runOnIdle{vm.busy.value=true}
            compose.onNodeWithText("继续编辑").assertIsNotEnabled()
            compose.onNodeWithText("正在处理…").assertIsNotEnabled()
            compose.runOnIdle{vm.busy.value=false}
            compose.onNodeWithText("继续编辑").performClick()
            compose.onNodeWithTag("room-record-body").assertTextContains(body)
            compose.runOnUiThread{compose.activity.setContent{Text("本机检查完成")}}
            compose.waitUntil(5000){guard==null}
        } finally {
            vm.viewModelScope.cancel();drafts.clearOwner(owner);probe.edit().clear().commit()
        }
    }
}
