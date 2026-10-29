package cn.musicpilgrimage.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import cn.musicpilgrimage.app.data.*
import cn.musicpilgrimage.app.ui.PilgrimageTheme
import cn.musicpilgrimage.app.ui.room.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Native UI fixtures only. No fake session, publication, upload or location certification is injected. */
class MusicCommunityUiTest {
    @get:Rule val compose=createAndroidComposeRule<MusicSpacePreviewActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val files=mutableListOf<File>()
    @After fun cleanFiles(){files.forEach{it.delete()}}
    private fun photo():String {
        val bitmap=Bitmap.createBitmap(600,900,Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(android.graphics.Color.rgb(210,222,213))
            val paint=Paint().apply{color=android.graphics.Color.rgb(34,60,81);textSize=42f;isAntiAlias=true}
            drawText("UI TEST PHOTO",60f,140f,paint)
            paint.color=android.graphics.Color.rgb(188,133,99);drawRect(70f,230f,530f,810f,paint)
            paint.color=android.graphics.Color.WHITE;drawText("600 x 900",150f,540f,paint)
        }
        val file=File(context.cacheDir,"community-ui-${UUID.randomUUID()}.png");files+=file
        file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        return file.toURI().toString()
    }
    private fun item(id:String,kind:String,owner:String="fixture-owner")=ContentItem(JSONObject()
        .put("id",id).put("kind",kind).put("user_id",owner).put("nickname","界面验证用户")
        .put("location_name","莎玛丽丹百货").put("location_id","samaritaine")
        .put("created_at","2026-10-20T12:00:00Z").put("verified_at","2026-10-18T10:00:00Z")
        .put("visibility","public").put("body","原生界面验证：保留与音乐有关的旅行记忆。")
        .put("scene_work","最伟大的作品").put("artist","周杰伦").put("likes",3))
    private fun show(title:String,body:@Composable ()->Unit) {
        compose.runOnUiThread{compose.activity.setContent{PilgrimageTheme{RoomWall(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Column{RoomHeader(title,onBack={},onSpace={});Box(Modifier.weight(1f)){body()}}
        }}}}
    }
    private fun capture(name:String) {
        compose.waitForIdle();instrumentation.waitForIdleSync();Thread.sleep(350)
        val folder=File(context.getExternalFilesDir(null),"music-space-stage7").apply{mkdirs()}
        instrumentation.uiAutomation.takeScreenshot().let{bitmap->File(folder,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
    }
    private fun awaitPhoto(tag:String) {
        compose.waitUntil(15000){compose.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
    }
    private fun revealEditorActions() {
        // Wait for the IME transition before scrolling a separately composed lazy item.
        compose.runOnIdle{androidx.core.view.WindowInsetsControllerCompat(compose.activity.window,compose.activity.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime())}
        compose.waitUntil(5000){androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())!=true}
        compose.onNodeWithTag("room-record-editor").performScrollToKey("actions")
    }
    @Test fun communityTabsOwnManagementAndDeleteConfirmationKeepCertification() {
        var actions=mutableListOf<String>()
        val message=item("message","message","someone-else")
        val own=item("visit","visit")
        show("地点社区") {
            var tab by remember{mutableIntStateOf(0)}
            RoomCommunityFeedContent(Place("samaritaine","莎玛丽丹百货","巴黎","法国",48.85,2.35),"fixture-owner",listOf(message,own),true,false,null,null,tab,{tab=it},{},{},{},{},{}) {row->
                RoomContentEntry(row,"fixture-owner",true,false,null,null,null,onEdit={actions.add("edit")},onProfile={},onAction={action,_->actions.add(action)})
            }
        }
        compose.onNodeWithText("写留言").assertIsDisplayed();compose.onNodeWithText("记录到访").assertIsDisplayed()
        compose.onNodeWithTag("room-content-message").assertExists();compose.onNodeWithTag("room-content-visit").assertDoesNotExist()
        capture("community")
        compose.onNodeWithText("到访与复刻").performClick()
        compose.onNodeWithTag("room-manage-visit").performScrollTo().performClick()
        compose.onNodeWithText("编辑",useUnmergedTree=true).performScrollTo().performClick()
        assertEquals(listOf("edit"),actions)
        compose.onNodeWithText("删除感想与照片").performScrollTo().performClick()
        compose.onNodeWithText("只移除感想和照片，到访日期与认证会保留。").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        assertEquals(listOf("edit"),actions)
        compose.onNodeWithText("到访于 2026-10-18").assertExists()
        compose.onNodeWithText("转为私密").performScrollTo().assertExists()
        capture("record-management")
    }
    @Test fun publicProfileDefaultsToRecreationShowsOriginalFirstAndPhotoDialogDoesNotJump() {
        val sample=photo()
        val app=context.applicationContext as PilgrimageApplication
        compose.waitUntil(10000){app.services.catalog.value.publicOnly().places.any{it.id=="samaritaine"}}
        val place=app.services.catalog.value.publicOnly().places.first{it.id=="samaritaine"}
        val scene=place.scenes.first{it.imageUrl!=null}
        val original=BundledCovers.model(context,"reference-${scene.id}",scene.imageUrl)
        val record=item("recreation","recreation","profile-owner")
        val visit=item("public-visit","visit","profile-owner")
        val profile=JSONObject().put("id","profile-owner").put("nickname","界面验证用户").put("visit_count",123).put("recreation_count",456)
            .put("recreations",JSONArray().put(record.raw)).put("visits",JSONArray().put(visit.raw))
        show("公开主页") {
            RoomPublicProfileContent(profile,false,null,{}) {row->
                RoomContentEntry(row,"reader",true,false,if(row.kind=="recreation")sample else null,original,"MV原作",false,{},{},{_,_->})
            }
        }
        compose.onNode(hasText("复刻") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).assertIsSelected()
        compose.onAllNodesWithText("界面验证用户").assertCountEquals(1)
        compose.onAllNodesWithText("123",substring=true).assertCountEquals(0)
        awaitPhoto("room-original-recreation");awaitPhoto("room-user-photo-recreation")
        capture("public-profile")
        val partialTop=compose.onNodeWithTag("room-user-photo-recreation").fetchSemanticsNode().boundsInRoot.top
        // The photo is partly below the viewport; tap its visible upper edge.
        compose.onNodeWithTag("room-user-photo-recreation").performTouchInput{click(Offset(center.x,40f))}
        capture("photo-viewer")
        compose.waitUntil(5000){compose.onAllNodesWithTag("room-community-photo-viewer",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("room-community-photo-viewer",useUnmergedTree=true).assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭大图").performClick()
        compose.waitUntil(5000){compose.onAllNodesWithTag("room-community-photo-viewer",useUnmergedTree=true).fetchSemanticsNodes().isEmpty()}
        assertEquals(partialTop,compose.onNodeWithTag("room-user-photo-recreation").fetchSemanticsNode().boundsInRoot.top,2f)
        compose.onNodeWithTag("room-user-photo-recreation").performScrollTo()
        val originalBounds=compose.onNodeWithTag("room-original-recreation").fetchSemanticsNode().boundsInRoot
        val userBounds=compose.onNodeWithTag("room-user-photo-recreation").fetchSemanticsNode().boundsInRoot
        assertTrue(originalBounds.top<userBounds.top)
        assertEquals(2f/3f,userBounds.width/userBounds.height,.03f)
        capture("recreation-photo")
        compose.onNodeWithTag("room-original-recreation").performScrollTo().performClick()
        compose.waitUntil(5000){compose.onAllNodesWithTag("room-community-photo-viewer",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription("关闭大图").performClick()
        compose.waitUntil(5000){compose.onAllNodesWithTag("room-community-photo-viewer",useUnmergedTree=true).fetchSemanticsNodes().isEmpty()}
        compose.onNodeWithTag("room-public-profile").performScrollToKey("tabs")
        compose.onNodeWithText("公开到访").performScrollTo().performClick()
        compose.onNodeWithText("到访于 2026-10-18").assertExists()
        compose.onAllNodesWithText("界面验证用户").assertCountEquals(1)
    }
    @Test fun editorKeepsSmallFullRatioPhotoAndUnsavedContinueDiscardAndEmptyPrivateRules() {
        val sample=photo()
        val baseline=JSONObject().put("body","原来的到访感想").put("visibility","public").put("local_photo",sample)
        var saved:JSONObject?=null;var picked=0;var shot=0
        show("到访记录") {
            var fields by remember{mutableStateOf(JSONObject(baseline.toString()))}
            var asking by remember{mutableStateOf(false)}
            var finished by remember{mutableStateOf(false)}
            if(finished)Text("已返回原记录：2026-10-18")
            else RoomRecordEditorContent("visit","莎玛丽丹百货","2026-10-18",fields,
                fields.stringOrNull("local_photo"),RecordDraftRules.hasPhoto(fields,null),null,false,true,null,false,
                {key,value->fields=RecordDraftRules.normalize("visit",JSONObject(fields.toString()).put(key,value),null)},
                {picked++},{shot++},{fields=RecordDraftRules.normalize("visit",JSONObject(fields.toString()).apply{remove("local_photo");put("clear_media",true)},null)},
                {saved=JSONObject(fields.toString());finished=true},
                {asking=RecordDraftRules.signature("visit",fields,null)!=RecordDraftRules.signature("visit",baseline,null)})
            if(asking)RoomRecordDiscardDialog(false,{asking=false},{fields=JSONObject(baseline.toString());asking=false})
        }
        compose.onNodeWithText("相册选择").performScrollTo().performClick();compose.onNodeWithText("现场拍照").performClick()
        assertEquals(1,picked);assertEquals(1,shot)
        awaitPhoto("room-record-photo");compose.onNodeWithTag("room-record-photo").performScrollTo()
        val photoBounds=compose.onNodeWithTag("room-record-photo").fetchSemanticsNode().boundsInRoot
        assertEquals(2f/3f,photoBounds.width/photoBounds.height,.03f)
        capture("record-editor")
        compose.onNodeWithTag("room-record-body").performScrollTo().performTextReplacement("修改后的感想")
        revealEditorActions()
        capture("record-editor-actions")
        compose.onNodeWithText("取消").performScrollTo().performClick()
        capture("record-unsaved")
        compose.onNodeWithText("继续编辑").performClick()
        compose.onNodeWithTag("room-record-body").assertTextContains("修改后的感想")
        revealEditorActions()
        compose.onNodeWithText("取消").performScrollTo().performClick()
        compose.onNodeWithText("放弃修改").performClick()
        compose.onNodeWithTag("room-record-body").assertTextContains("原来的到访感想")
        compose.onNodeWithTag("room-record-body").performScrollTo().performTextClearance()
        compose.onNodeWithText("移除照片").performScrollTo().performClick()
        compose.onNodeWithTag("room-record-public").performScrollTo().assertIsNotEnabled().assertIsOff()
        revealEditorActions()
        compose.onNodeWithTag("room-record-save").performScrollTo().performClick()
        assertEquals("private",saved!!.getString("visibility"))
        compose.onNodeWithText("已返回原记录：2026-10-18").assertIsDisplayed()
    }
    @Test fun unauthenticatedDiscardCannotRemoveAnotherOwnersDraftOrPhoto()=runBlocking {
        val app=context.applicationContext as PilgrimageApplication
        compose.waitUntil(10000){app.services.authReady.value}
        assumeTrue("Do not modify signed-in accounts",app.services.userId.value==null)
        val owner="record-guard-${UUID.randomUUID()}"
        val file=File(context.filesDir,"photos/record-guard-${UUID.randomUUID()}.jpg").apply{parentFile!!.mkdirs();writeBytes(byteArrayOf(1,2,3))}
        val store=DraftStore(context)
        val vm=FormViewModel(app)
        try {
            store.write(owner,"visit:samaritaine:",JSONObject().put("body","保留的草稿").put("local_photo",file.path))
            vm.initialize("visit","samaritaine",null)
            compose.waitUntil(5000){!vm.busy.value&&vm.error.value!=null}
            var completed=false
            vm.discard{completed=true}.join()
            assertFalse(completed);assertTrue(file.exists())
            assertEquals("保留的草稿",store.read(owner,"visit:samaritaine:").getString("body"))
        } finally {vm.viewModelScope.cancel();store.clearOwner(owner);file.delete()}
    }
}
