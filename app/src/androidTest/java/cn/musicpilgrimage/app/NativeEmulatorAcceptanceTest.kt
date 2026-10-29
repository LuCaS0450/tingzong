package cn.musicpilgrimage.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Process
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import cn.musicpilgrimage.app.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import io.github.jan.supabase.auth.auth

/** Genuine Auth, production MainActivity/navigation, Android picker/camera and real backend.
 * The only visit fixture is explicitly private and uncertified. No positive onsite fix is injected. */
class NativeEmulatorAcceptanceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private val services get()=(context.applicationContext as PilgrimageApplication).services
    private val probe get()=context.getSharedPreferences("emulator-acceptance",Context.MODE_PRIVATE)
    private val folder get()=File(context.getExternalFilesDir(null),"emulator-acceptance").apply{mkdirs()}
    private fun fixtures():JSONObject {
        val file=File(context.filesDir,"acceptance-credentials.json")
        assertTrue("Use the scoped host helper to stage the disposable accounts",file.isFile)
        val value=JSONObject(file.readText());assertTrue(file.delete())
        val prior=probe.getString("nonce",null)
        assertTrue("Do not mix fixture runs",prior==null||prior==value.getString("nonce"))
        probe.edit().putString("nonce",value.getString("nonce")).commit()
        return value
    }
    private fun mark(name:String,value:Any=true) {
        val file=File(context.filesDir,"emulator-acceptance-result.json")
        val result=if(file.isFile)JSONObject(file.readText())else JSONObject()
        file.writeText(result.put(name,value).toString())
    }
    private suspend fun login(f:JSONObject,label:String="a") {
        services.authReady.first{it};val account=f.getJSONObject("accounts").getJSONObject(label)
        if(services.userId.value!=account.getString("id")) {
            if(services.userId.value!=null){services.signOut();waitTag("room-email-login")}
            services.signIn(account.getString("email"),account.getString("password"))
        }
        withTimeout(30000){services.userId.first{it==account.getString("id")}}
        assertEquals(account.getString("id"),(services.call("self") as JSONObject).getString("id"))
        waitTag("room-home")
    }
    private fun waitTag(tag:String,timeout:Long=25000) {
        compose.waitUntil(timeout){runCatching{compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}.getOrDefault(false)}
    }
    private fun profileReady() {
        waitTag("room-profile-editor")
        try {compose.waitUntil(30000){compose.onAllNodes(hasText("选择头像") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}}
        catch(e:Throwable) {
            val vm=androidx.lifecycle.ViewModelProvider(compose.activity)["music-profile-${services.userId.value}",MusicProfileEditorViewModel::class.java]
            val state=vm.state.value
            File(folder,"profile-state.txt").writeText("active=${state.active}; loading=${state.loading}; ownerMatches=${state.owner==services.userId.value}; error=${state.error}")
            systemDump("profile-not-ready");throw e
        }
    }
    private fun module(route:String) {
        compose.onNodeWithTag("room-space-button").performClick()
        compose.onNodeWithTag("room-module-$route").performClick()
    }
    private fun screenshot(name:String,system:Boolean=false) {
        if(!system)compose.waitForIdle()
        ins.uiAutomation.takeScreenshot().let{b->File(folder,"$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
    }
    private fun nodes():List<AccessibilityNodeInfo> {
        val list=mutableListOf<AccessibilityNodeInfo>()
        fun visit(node:AccessibilityNodeInfo?){if(node==null)return;list+=node;for(i in 0 until node.childCount)visit(node.getChild(i))}
        visit(ins.uiAutomation.rootInActiveWindow);return list
    }
    private fun systemDump(name:String) {
        File(folder,"$name.txt").writeText(nodes().joinToString("\n"){"${it.packageName} | ${it.viewIdResourceName} | ${it.text} | ${it.contentDescription} | click=${it.isClickable}"})
        screenshot(name,true)
    }
    private fun systemClick(timeout:Long=12000,match:(AccessibilityNodeInfo)->Boolean) {
        val end=System.currentTimeMillis()+timeout
        while(System.currentTimeMillis()<end) {
            val found=nodes().firstOrNull{it.isVisibleToUser&&match(it)}
            if(found!=null) {
                var target:AccessibilityNodeInfo?=found
                while(target!=null&&!target.isClickable)target=target.parent
                if(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true){Thread.sleep(400);return}
            }
            Thread.sleep(200)
        }
        systemDump("system-control-missing")
        throw AssertionError("Expected system control unavailable; inspect the scoped system UI evidence")
    }
    private fun systemBack(){ins.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);Thread.sleep(400)}
    private fun seedPhoto(f:JSONObject):Uri {
        val old=probe.getString("source-uri",null)
        old?.let{context.contentResolver.delete(Uri.parse(it),null,null)}
        val album="TingzongAcceptance-${f.getString("nonce")}"
        val values=ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME,"$album.jpg")
            put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/$album")
            put(MediaStore.Images.Media.IS_PENDING,1)
        }
        val uri=context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)!!
        probe.edit().putString("source-uri",uri.toString()).commit()
        val bitmap=Bitmap.createBitmap(800,600,Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply{drawColor(Color.rgb(232,221,199));drawRect(0f,0f,800f,170f,Paint().apply{color=Color.rgb(48,79,99)});drawText("TINGZONG TEST PHOTO",45f,330f,Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.DKGRAY;textSize=45f})}
        context.contentResolver.openOutputStream(uri)!!.use{bitmap.compress(Bitmap.CompressFormat.JPEG,90,it)};bitmap.recycle()
        context.contentResolver.update(uri,ContentValues().apply{put(MediaStore.Images.Media.IS_PENDING,0)},null,null)
        return uri
    }
    private fun pickOwnAlbum(f:JSONObject,returnTag:String="room-profile-editor") {
        systemDump("system-picker")
        systemClick {it.text?.toString() in listOf("Albums","相册")||it.contentDescription?.toString() in listOf("Collections","Albums","相册")}
        systemClick{it.text?.toString()=="From this device"}
        val album="TingzongAcceptance-${f.getString("nonce")}"
        systemClick {it.text?.toString()==album||it.contentDescription?.toString()?.contains(album)==true}
        systemDump("system-picker-own-album")
        systemClick {it.viewIdResourceName?.endsWith(":id/icon_thumbnail")==true||it.contentDescription?.toString()?.let{d->d.startsWith("Photo taken")||d.startsWith("照片拍摄于")}==true}
        systemDump("system-picker-after-selection")
        val confirm:(AccessibilityNodeInfo)->Boolean={it.text?.toString()?.let{t->t=="Add"||t.startsWith("Add (")||t=="Done"||t=="选择"||t=="添加"}==true}
        if(nodes().any(confirm))systemClick(match=confirm)
        try{waitTag(returnTag)}catch(e:Throwable){systemDump("picker-return-missing");throw e}
    }
    private fun pickPhotoForRecord(f:JSONObject) {
        pickOwnAlbum(f,"room-record-editor")
    }
    private fun openRecord(f:JSONObject) {
        if(compose.onAllNodesWithTag("room-place-favorites").fetchSemanticsNodes().isEmpty()) {
            module("library");waitTag("room-archive")
            compose.onNodeWithTag("room-drawer-footprints").performScrollTo().performClick()
        }
        val expand="archive-expand-${f.getString("location")}"
        waitTag(expand)
        if(compose.onAllNodesWithTag("archive-visit-${f.getString("visit")}").fetchSemanticsNodes().isEmpty())compose.onNodeWithTag(expand).performScrollTo().performClick()
        compose.onNodeWithTag("archive-visit-${f.getString("visit")}").performScrollTo()
        compose.onNodeWithContentDescription("到访记录操作").performClick()
        compose.onNodeWithText("编辑",substring=false).performClick();waitTag("room-record-editor")
    }
    private suspend fun record(f:JSONObject)=services.repositories.content.list(f.getString("location"),"visit",true).first{it.id==f.getString("visit")}
    private fun discardRecord() {
        compose.onNodeWithText("放弃修改",substring=false).performClick()
        compose.waitUntil(15000){compose.onAllNodesWithTag("room-record-editor").fetchSemanticsNodes().isEmpty()}
    }
    private fun recordClick(tag:String) {
        compose.onNodeWithTag("room-record-editor").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
    }
    private fun recordTextClick(label:String) {
        compose.onNodeWithTag("room-record-editor").performScrollToNode(hasText(label,substring=false))
        compose.onNodeWithText(label,substring=false).performClick()
    }
    private fun saveRecord()=recordClick("room-record-save")
    private fun expectDiscard(label:String) {
        try{compose.waitUntil(2500){compose.onAllNodesWithText("放弃未保存的修改？").fetchSemanticsNodes().isNotEmpty()};compose.onNodeWithText("放弃未保存的修改？").assertIsDisplayed()}
        catch(e:Throwable){systemDump("draft-guard-$label-failed");throw e}
    }

    @Test fun systemPickerAndRealProfileSave()=runBlocking {
        val f=fixtures();login(f);seedPhoto(f)
        module("me");waitTag("account-edit-profile");compose.onNodeWithTag("account-edit-profile").performClick()
        profileReady();compose.onNodeWithText("选择头像").assertIsEnabled().performClick()
        // Cancel once through Android, then choose the uniquely named fixture album.
        val end=System.currentTimeMillis()+12000
        while(System.currentTimeMillis()<end&&nodes().none{it.packageName?.toString()=="com.google.android.photopicker"})Thread.sleep(200)
        assertTrue(nodes().any{it.packageName?.toString()=="com.google.android.photopicker"});systemBack()
        waitTag("room-profile-editor");compose.onNodeWithText("选择头像").performClick();pickOwnAlbum(f)
        compose.waitUntil(20000){compose.onAllNodesWithText("移除头像").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("profile-nickname").performTextReplacement("模拟器验收甲已修改")
        compose.onNodeWithTag("profile-save").performScrollTo().performClick()
        waitTag("account-nickname");compose.onNodeWithTag("account-nickname").assertTextEquals("模拟器验收甲已修改")
        assertEquals("模拟器验收甲已修改",(services.call("self") as JSONObject).getString("nickname"))
        assertTrue(services.accountAvatar()?.startsWith("data:image/jpeg;base64,")==true)
        probe.edit().putInt("profile-pid",Process.myPid()).commit();screenshot("profile-saved")
        mark("system_picker_cancel_and_selection");mark("real_profile_nickname_and_avatar_saved")
    }

    @Test fun profileSurvivesRestartThenAvatarRemoval()=runBlocking {
        val f=fixtures();login(f);assertNotEquals(probe.getInt("profile-pid",0),Process.myPid())
        assertTrue(services.accountAvatar()?.startsWith("data:image/jpeg;base64,")==true)
        module("me");waitTag("account-nickname");compose.onNodeWithTag("account-nickname").assertTextEquals("模拟器验收甲已修改")
        compose.onNodeWithTag("account-edit-profile").performClick();waitTag("room-profile-editor")
        compose.waitUntil(20000){compose.onAllNodesWithText("移除头像").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("移除头像").performClick();compose.onNodeWithTag("profile-save").performScrollTo().performClick()
        waitTag("account-nickname");assertNull(services.accountAvatar());screenshot("avatar-removed")
        mark("profile_persisted_across_real_restart_and_avatar_removed")
    }

    @Test fun realProfileUnsavedExitGuards()=runBlocking {
        val f=fixtures();login(f);val original=(services.call("self") as JSONObject).getString("nickname")
        module("me");waitTag("account-edit-profile");compose.onNodeWithTag("account-edit-profile").performClick();profileReady()
        compose.onNodeWithTag("profile-nickname").performTextReplacement("不应保存的临时昵称")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("放弃修改？").assertIsDisplayed();compose.onNodeWithText("继续编辑").performClick()
        module("library");compose.onNodeWithText("放弃修改？").assertIsDisplayed();compose.onNodeWithText("继续编辑").performClick()
        compose.runOnIdle{compose.activity.onBackPressedDispatcher.onBackPressed()}
        compose.waitUntil(3000){compose.onAllNodesWithText("放弃修改？").fetchSemanticsNodes().isNotEmpty()}
        screenshot("profile-system-back-guard");compose.onNodeWithTag("account-discard-confirm").performClick()
        waitTag("account-nickname");compose.onNodeWithTag("account-nickname").assertTextEquals(original)
        assertEquals(original,(services.call("self") as JSONObject).getString("nickname"))
        mark("real_profile_header_module_and_system_back_discard_guards")
    }

    @Test fun productionDraftGuardsAndPrivatePhotoUpload()=runBlocking {
        val f=fixtures();login(f);openRecord(f)
        compose.onNodeWithTag("room-record-body").performTextReplacement("未提交的临时感想")
        compose.onNodeWithContentDescription("返回").performClick()
        expectDiscard("header");compose.onNodeWithText("继续编辑").performClick()
        module("me");expectDiscard("module");compose.onNodeWithText("继续编辑").performClick()
        compose.runOnIdle{compose.activity.onBackPressedDispatcher.onBackPressed()}
        expectDiscard("system");screenshot("production-draft-guard");discardRecord()
        assertEquals(0,services.drafts.read(services.userId.value!!,"visit:${f.getString("location")}:${f.getString("visit")}").length())
        mark("production_header_system_back_and_module_draft_guards")
        openRecord(f);compose.onNodeWithTag("room-record-body").assertTextContains("未认证的私有验收记录")
        recordTextClick("相册选择");pickPhotoForRecord(f)
        compose.waitUntil(25000){compose.onAllNodes(hasTestTag("room-record-photo") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("room-record-body").performTextReplacement("相册上传的私有验收记录")
        screenshot("record-photo-selected")
        saveRecord()
        compose.waitUntil(80000){compose.onAllNodesWithTag("room-record-editor").fetchSemanticsNodes().isEmpty()}
        val saved=record(f);assertEquals("相册上传的私有验收记录",saved.body);assertEquals("private",saved.visibility)
        assertNotNull(saved.mediaId);assertTrue(saved.raw.isNull("verified_at"))
        probe.edit().putString("record-media",saved.mediaId).commit();screenshot("private-record-saved")
        mark("production_header_system_back_and_module_draft_guards");mark("private_record_real_photo_upload")
    }

    @Test fun requestDedicatedMailboxCodeThroughLoginUi()=runBlocking {
        val f=fixtures();services.authReady.first{it};services.signOut()
        waitTag("room-email-login")
        compose.onNodeWithTag("account-email").performTextReplacement(f.getJSONObject("otp").getString("email"))
        compose.onNodeWithTag("account-send-code").performClick()
        compose.waitUntil(35000){compose.onNodeWithTag("account-feedback").fetchSemanticsNode().config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text){emptyList()}.joinToString(""){it.text}.isNotBlank()}
        val feedback=compose.onNodeWithTag("account-feedback").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString(""){it.text}
        mark("otp_send_feedback",feedback);mark("otp_send_accepted",feedback.contains("已发送"));screenshot("email-code-request")
    }

    @Test fun systemCameraDenialCancellationAndReplacement()=runBlocking {
        val f=fixtures();login(f);openRecord(f)
        val original=record(f).mediaId;assertNotNull(original)
        compose.onNodeWithTag("room-record-body").performTextReplacement("拍照权限检查期间保留的文字")
        recordTextClick("现场拍照")
        systemClick {it.viewIdResourceName?.endsWith(":id/permission_deny_button")==true}
        waitTag("room-record-editor");compose.onNodeWithTag("room-record-body").assertTextContains("拍照权限检查期间保留的文字")
        assertEquals(original,record(f).mediaId)
        recordTextClick("现场拍照")
        systemClick {it.viewIdResourceName?.endsWith(":id/permission_allow_foreground_only_button")==true||it.text?.toString() in listOf("While using the app","使用应用时")}
        Thread.sleep(1000);systemDump("system-camera-cancel")
        systemBack();waitTag("room-record-editor")
        compose.onNodeWithTag("room-record-body").assertTextContains("拍照权限检查期间保留的文字")
        recordTextClick("现场拍照")
        Thread.sleep(1000);systemDump("system-camera-capture")
        systemClick(20000) {it.viewIdResourceName?.endsWith(":id/shutter_button")==true||it.contentDescription?.toString() in listOf("Shutter","Take photo","Capture photo","拍照","快门")}
        systemDump("system-camera-review")
        systemClick {it.viewIdResourceName?.endsWith(":id/done_button")==true||it.contentDescription?.toString() in listOf("Done","Use photo","完成","确定")||it.text?.toString() in listOf("Use photo","OK","完成","确定")}
        waitTag("room-record-editor")
        val draftKey="visit:${f.getString("location")}:${f.getString("visit")}"
        // The old cloud thumbnail is already enabled; wait for the newly captured local file.
        try {
            withTimeout(25000){while(services.drafts.read(services.userId.value!!,draftKey).stringOrNull("local_photo")==null)delay(150)}
        } catch(e:Throwable) {
            systemDump("camera-import-failed")
            File(folder,"camera-import-state.txt").writeText("draftKeys="+services.drafts.read(services.userId.value!!,draftKey).keys().asSequence().toList()+"; cameraFiles="+File(context.cacheDir,"camera").listFiles()?.map{it.name+":"+it.length()})
            throw e
        }
        compose.waitUntil(25000){compose.onAllNodes(hasTestTag("room-record-photo") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("room-record-body").performTextReplacement("系统相机替换的私有验收记录")
        screenshot("system-camera-returned-photo")
        saveRecord()
        compose.waitUntil(80000){compose.onAllNodesWithTag("room-record-editor").fetchSemanticsNodes().isEmpty()}
        val saved=record(f);assertNotNull(saved.mediaId);assertNotEquals(original,saved.mediaId);assertTrue(saved.raw.isNull("verified_at"))
        mark("system_camera_permission_denial_cancel_and_photo_replacement")
    }

    @Test fun realSystemDocumentPickerPreservesAndImportsReviewedAudio()=runBlocking {
        val f=fixtures();login(f)
        val song=cn.musicpilgrimage.app.performance.PerformanceCatalog.load(context).first{it.id=="greatest-works"}
        val store=cn.musicpilgrimage.app.performance.PerformanceAudioStore(context)
        assertNotNull("Preserve the already installed reviewed full-song audio",store.directory(song))
        module("performance");waitTag("performance-list")
        compose.onNodeWithTag("performance-song-${song.id}").performScrollTo().performClick();waitTag("performance-preparation")
        compose.onNodeWithTag("performance-import").performScrollTo().performClick()
        Thread.sleep(500);systemDump("audio-document-picker-cancel");systemBack();waitTag("performance-preparation")
        assertNotNull(store.directory(song));compose.onNodeWithTag("performance-enter").assertIsEnabled()
        compose.onNodeWithTag("performance-import").performScrollTo().performClick();Thread.sleep(500);systemDump("audio-document-picker")
        val name=f.getString("audio_picker_file")
        val fileNode:(AccessibilityNodeInfo)->Boolean={it.text?.toString()==name||it.contentDescription?.toString()?.contains(name)==true}
        if(nodes().none(fileNode)) {
            if(nodes().none{it.text?.toString()=="Downloads"})systemClick{it.contentDescription?.toString() in listOf("Show roots","显示根目录")}
            systemClick{it.text?.toString() in listOf("Downloads","下载")}
        }
        systemClick(20000,match=fileNode);waitTag("performance-preparation")
        compose.waitUntil(80000){compose.onAllNodesWithText("音源已就绪").fetchSemanticsNodes().isNotEmpty()}
        assertNotNull(store.directory(song));compose.onNodeWithTag("performance-enter").assertIsEnabled();screenshot("system-audio-import-complete")
        mark("real_system_document_picker_cancel_and_reviewed_audio_import")
    }

    private suspend fun openCommunity(f:JSONObject) {
        services.repositories.content.save("favorite",JSONObject().put("location_id",f.getString("location")).put("enabled",true))
        module("library");waitTag("room-archive");compose.onNodeWithTag("room-drawer-places").performScrollTo().performClick()
        waitTag("archive-place-${f.getString("location")}")
        val name=services.catalog.value.places.first{it.id==f.getString("location")}.name
        compose.onNodeWithText(name,substring=false).performScrollTo().performClick();waitTag("room-place-detail")
        compose.onNodeWithText("地点社区",substring=false).performScrollTo().performClick();waitTag("room-community")
    }

    @Test fun realCommunityMessageEditPeerInteractionsProfileAndDelete()=runBlocking {
        val f=fixtures();login(f)
        val prefix="自动验收临时留言 ${f.getString("nonce").take(6)}"
        services.repositories.content.list(f.getString("location"),"message",true).filter{it.body.startsWith(prefix)}.forEach {
            services.repositories.content.save("delete_content",JSONObject().put("id",it.id))
        }
        openCommunity(f)
        val body="自动验收临时留言 ${f.getString("nonce").take(6)}（完成后删除）"
        compose.onNodeWithText("写留言").performScrollTo().performClick();waitTag("room-record-editor")
        compose.onNodeWithTag("room-record-body").performTextReplacement(body)
        saveRecord();waitTag("room-community")
        val row=services.repositories.content.list(f.getString("location"),"message",true).single{it.body==body}
        probe.edit().putString("community-message",row.id).commit()
        val tag="room-content-${row.id}";waitTag(tag);compose.onNodeWithTag(tag).performScrollTo()
        compose.onNodeWithTag("room-manage-${row.id}").performClick()
        compose.onNode(hasText("编辑",substring=false) and hasAnyAncestor(hasTestTag(tag))).performClick();waitTag("room-record-editor")
        val edited=body.replace("完成后删除","已编辑，完成后删除")
        compose.onNodeWithTag("room-record-body").performTextReplacement(edited)
        saveRecord();waitTag("room-community")
        assertEquals(edited,services.repositories.content.list(f.getString("location"),"message",true).single{it.id==row.id}.body)
        login(f,"b");openCommunity(f);waitTag(tag);compose.onNodeWithTag(tag).performScrollTo()
        compose.onNode(hasContentDescription("点赞") and hasAnyAncestor(hasTestTag(tag))).performClick()
        withTimeout(30000){while(!services.repositories.content.list(f.getString("location"),"message").single{it.id==row.id}.liked)delay(250)}
        compose.waitUntil(10000){compose.onAllNodes(hasContentDescription("取消点赞") and hasAnyAncestor(hasTestTag(tag))).fetchSemanticsNodes().isNotEmpty()}
        compose.onNode(hasContentDescription("取消点赞") and hasAnyAncestor(hasTestTag(tag))).performClick()
        withTimeout(30000){while(services.repositories.content.list(f.getString("location"),"message").single{it.id==row.id}.liked)delay(250)}
        val report=hasText("举报",substring=false) and hasAnyAncestor(hasTestTag(tag))
        compose.waitUntil(20000){compose.onAllNodes(report and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
        compose.onNode(report).performClick()
        compose.waitUntil(10000){compose.onAllNodes(hasText("其他",substring=false) and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty()}
        compose.onNode(hasText("其他",substring=false) and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(30000){compose.onAllNodesWithText("举报已提交").fetchSemanticsNodes().isNotEmpty()}
        val nickname=(services.repositories.content.profile(f.getJSONObject("accounts").getJSONObject("a").getString("id"))).getString("nickname")
        compose.onNode(hasText(nickname,substring=false) and hasAnyAncestor(hasTestTag(tag))).performClick();waitTag("room-public-profile")
        compose.waitUntil(20000){compose.onAllNodesWithText(nickname,substring=false).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("公开到访",substring=false).performClick()
        compose.waitUntil(20000){compose.onAllNodesWithText("还没有公开到访。").fetchSemanticsNodes().isNotEmpty()}
        screenshot("real-public-profile")
        login(f);openCommunity(f);waitTag(tag);compose.onNodeWithTag(tag).performScrollTo()
        compose.onNodeWithTag("room-manage-${row.id}").performClick()
        compose.onNode(hasText("删除内容",substring=false) and hasAnyAncestor(hasTestTag(tag))).performClick()
        compose.onNode(hasText("取消",substring=false) and hasAnyAncestor(isDialog())).performClick();compose.onNodeWithTag(tag).assertIsDisplayed()
        compose.onNode(hasText("删除内容",substring=false) and hasAnyAncestor(hasTestTag(tag))).performClick()
        compose.onNode(hasText("删除",substring=false) and hasAnyAncestor(isDialog())).performClick()
        withTimeout(30000){while(services.repositories.content.list(f.getString("location"),"message",true).any{it.id==row.id})delay(250)}
        mark("real_community_message_create_edit_peer_like_unlike_report_profile_and_delete")
    }

    private fun mediaStatus(id:String,authenticated:Boolean=true):Int {
        val connection=URL(services.mediaUrl(id)).openConnection() as HttpURLConnection
        connection.connectTimeout=25000;connection.readTimeout=25000
        connection.setRequestProperty("apikey",BuildConfig.SUPABASE_ANON_KEY)
        if(authenticated)connection.setRequestProperty("Authorization","Bearer "+services.client!!.auth.currentAccessTokenOrNull())
        return try{val status=connection.responseCode;if(status==200){val image=android.graphics.BitmapFactory.decodeStream(connection.inputStream);assertNotNull("Private media must decode",image);image?.recycle()};status}finally{connection.disconnect()}
    }

    @Test fun mediaOwnershipPrivacyPhotoRemovalAndRecordDeletion()=runBlocking {
        val f=fixtures();login(f);val saved=record(f);val media=saved.mediaId!!
        assertEquals(200,mediaStatus(media));assertNotEquals(200,mediaStatus(media,false))
        login(f,"b")
        assertTrue("A private visit stays outside the peer's community",services.repositories.content.list(f.getString("location"),"visit").none{it.id==f.getString("visit")})
        assertNotEquals(200,mediaStatus(media))
        var denied=false
        try {services.repositories.content.save("update_content",JSONObject().put("id",f.getString("visit")).put("body","拒绝跨账号修改"))}catch(e:ApiException){denied=true}
        assertTrue("Peer cannot alter another account's content",denied)
        login(f);openRecord(f);recordClick("room-record-public")
        saveRecord()
        compose.waitUntil(70000){compose.onAllNodesWithTag("room-record-editor").fetchSemanticsNodes().isEmpty()}
        assertEquals("public",record(f).visibility);assertTrue(record(f).raw.isNull("verified_at"))
        login(f,"b");assertEquals(200,mediaStatus(media))
        val a=f.getJSONObject("accounts").getJSONObject("a").getString("id")
        val publicProfile=services.repositories.content.profile(a)
        assertTrue("An uncertified fixture does not become a public certification",publicProfile.optJSONArray("visits")?.objects().orEmpty().none{it.optString("id")==f.getString("visit")})
        login(f);openRecord(f);recordClick("room-record-public")
        recordTextClick("移除照片")
        saveRecord()
        compose.waitUntil(70000){compose.onAllNodesWithTag("room-record-editor").fetchSemanticsNodes().isEmpty()}
        val removed=record(f);assertNull(removed.mediaId);assertEquals("private",removed.visibility)
        compose.onNodeWithContentDescription("到访记录操作").performScrollTo().performClick()
        compose.onNodeWithText("删除感想与照片",substring=false).performClick()
        compose.onNode(hasText("删除",substring=false) and hasAnyAncestor(isDialog())).performClick()
        withTimeout(30000){while(record(f).body.isNotEmpty())delay(300)}
        val empty=record(f);assertNull(empty.mediaId);assertTrue(empty.raw.isNull("verified_at"));assertEquals("private",empty.visibility)
        screenshot("record-content-deleted");mark("real_media_owner_and_peer_access_rules");mark("uncertified_visit_not_exposed_as_public_certification");mark("record_privacy_photo_removal_and_content_deletion")
    }

    @Test fun accountIsolationRealLogoutAndDisposableDeletion()=runBlocking {
        val f=fixtures();login(f);openRecord(f)
        val a=f.getJSONObject("accounts").getJSONObject("a").getString("id")
        val b=f.getJSONObject("accounts").getJSONObject("b").getString("id")
        suspend fun librarySettled(id:String) {
            withTimeout(60000){while(services.library.state.value.owner!="user:$id"||services.library.state.value.status!=LibrarySyncStatus.SYNCED||services.library.favorites.snapshot("user:$id").pending||services.library.trips.snapshot("user:$id").pending)delay(150)}
        }
        librarySettled(a)
        val song=MusicCatalogue.from(services.catalog.value).works.first().id
        val tripId=java.util.UUID.nameUUIDFromBytes((f.getString("nonce")+"account-isolation").toByteArray()).toString()
        assertTrue(services.library.favorites.read("user:$a").songs.isEmpty())
        assertTrue(services.library.trips.read("user:$a").isEmpty())
        services.library.favorites.add("user:$a",song,false)
        val day=java.time.LocalDate.of(2026,11,12)
        services.library.trips.update("user:$a"){it+MusicTrip(tripId,"账号隔离验收",day,day,listOf(MusicTripCity("Paris","France")),emptyList())}
        librarySettled(a)
        val key="visit:${f.getString("location")}:${f.getString("visit")}"
        compose.onNodeWithTag("room-record-body").performTextReplacement("只属于验收甲的本机草稿")
        withTimeout(15000){while(services.drafts.read(a,key).optString("body")!="只属于验收甲的本机草稿")delay(200)}
        login(f,"b");assertEquals(0,services.drafts.read(b,key).length())
        librarySettled(b)
        assertTrue(services.library.favorites.read("user:$b").songs.isEmpty())
        assertTrue(services.library.trips.read("user:$b").isEmpty())
        assertNull(services.accountAvatar());assertEquals("模拟器验收乙",(services.call("self") as JSONObject).getString("nickname"))
        module("me");waitTag("account-nickname");compose.waitUntil(20000){compose.onAllNodesWithText("模拟器验收乙").fetchSemanticsNodes().isNotEmpty()}
        assertTrue(compose.onAllNodesWithText("只属于验收甲的本机草稿").fetchSemanticsNodes().isEmpty());screenshot("second-account-isolated")
        login(f);openRecord(f);compose.onNodeWithTag("room-record-body").assertTextContains("只属于验收甲的本机草稿")
        librarySettled(a)
        assertTrue(song in services.library.favorites.read("user:$a").songs)
        assertTrue(services.library.trips.read("user:$a").any{it.id==tripId})
        compose.runOnUiThread{compose.activity.onBackPressedDispatcher.onBackPressed()};discardRecord()
        services.library.favorites.remove("user:$a",song,false)
        services.library.trips.update("user:$a"){it.filterNot{trip->trip.id==tripId}}
        librarySettled(a)
        login(f,"b");module("settings");waitTag("room-settings")
        compose.onNodeWithTag("account-sign-out").performScrollTo().performClick()
        compose.onNode(hasText("退出登录",substring=false) and hasAnyAncestor(isDialog())).performClick()
        waitTag("room-email-login");assertNull(services.client!!.auth.currentUserOrNull());assertNull(services.userId.value)
        assertEquals(0,services.drafts.read(b,key).length());mark("two_real_accounts_isolate_profile_library_and_drafts");mark("native_logout_clears_sdk_session")
        login(f,"b");module("settings");compose.onNodeWithText("邮箱账号").performScrollTo().performClick()
        compose.onNodeWithTag("account-delete-entry").performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextReplacement("删除账号")
        compose.onNodeWithText("确认注销",substring=false).performClick()
        waitTag("room-email-login",70000);assertNull(services.client!!.auth.currentUserOrNull())
        assertEquals(0,services.drafts.read(b,key).length());assertTrue(services.library.favorites.read("user:$b").songs.isEmpty())
        screenshot("disposable-account-deleted");mark("disposable_account_deleted_through_native_confirmation")
    }

    @Test fun cleanupLocalFixturesAndSignOut()=runBlocking {
        val f=fixtures();services.authReady.first{it}
        val owners=f.getJSONObject("accounts");for(label in owners.keys()){services.drafts.clearOwner(owners.getJSONObject(label).getString("id"));services.library.clearAccount(owners.getJSONObject(label).getString("id"))}
        probe.getString("source-uri",null)?.let{context.contentResolver.delete(Uri.parse(it),null,null)}
        probe.edit().clear().commit()
        context.getSharedPreferences("two-clients",Context.MODE_PRIVATE).edit().clear().commit()
        if(services.userId.value in owners.keys().asSequence().map{owners.getJSONObject(it).getString("id")}.toList())services.signOut()
        mark("local_tagged_media_drafts_and_library_caches_removed")
    }

    @Test fun verifyRealDeliveredCodeThroughLoginUi()=runBlocking {
        val f=fixtures();services.authReady.first{it};if(services.userId.value!=null)services.signOut();assertNull(services.userId.value)
        val file=File(context.filesDir,"acceptance-email-code.json");assertTrue("A code must come from a real owned mailbox",file.isFile)
        val mail=JSONObject(file.readText());assertTrue(file.delete());assertTrue(mail.getString("delivery") in setOf("dedicated_mail_api","authorized_gmail_browser"))
        waitTag("room-email-login");compose.onNodeWithTag("account-email").performTextReplacement(mail.getString("email"))
        val realCode=mail.getString("code")
        val wrongCode=(if(realCode[0]=='9')"0" else (realCode[0].digitToInt()+1).toString())+realCode.drop(1)
        compose.onNodeWithTag("account-code").performTextReplacement(wrongCode)
        compose.onNodeWithTag("account-sign-in").performScrollTo().performClick()
        compose.waitUntil(35000){compose.onAllNodesWithText("验证码不正确或已失效，请重新获取").fetchSemanticsNodes().isNotEmpty()}
        assertNull("An incorrect delivered-code attempt must not authenticate",services.userId.value)
        compose.onNodeWithTag("account-code").performTextReplacement(mail.getString("code"))
        compose.onNodeWithTag("account-sign-in").performScrollTo().performClick()
        withTimeout(35000){services.userId.first{it==f.getJSONObject("otp").getString("id")}}
        waitTag("room-home");mark("real_email_delivery_and_native_otp_login");screenshot("otp-signed-in")
        mark("real_incorrect_code_rejected_without_losing_valid_code")
    }

    @Test fun closedRegistrationUsesRealServerGate()=runBlocking {
        val f=fixtures();services.authReady.first{it};if(services.userId.value!=null)services.signOut()
        waitTag("room-email-login");compose.onNodeWithTag("account-registration").performClick()
        compose.onNodeWithTag("account-email").performTextReplacement(f.getJSONObject("otp").getString("email"))
        compose.onNodeWithTag("account-send-code").performClick()
        compose.waitUntil(35000){compose.onAllNodesWithText("暂未开放注册，已有账号可以返回登录").fetchSemanticsNodes().isNotEmpty()}
        assertNull(services.userId.value);screenshot("registration-closed-real-gate");mark("real_registration_closed_ui_gate")
    }
}
