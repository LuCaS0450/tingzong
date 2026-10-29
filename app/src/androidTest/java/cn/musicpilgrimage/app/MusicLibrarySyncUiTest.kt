package cn.musicpilgrimage.app

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.work.WorkManager
import cn.musicpilgrimage.app.data.*
import cn.musicpilgrimage.app.performance.LibraryDatabase
import cn.musicpilgrimage.app.ui.PilgrimageTheme
import cn.musicpilgrimage.app.ui.room.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.UUID

class MusicLibrarySyncUiTest {
    @get:Rule val compose=createAndroidComposeRule<MusicSpacePreviewActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun capture(name:String) {
        compose.waitForIdle();instrumentation.waitForIdleSync();Thread.sleep(250)
        val folder=File(context.getExternalFilesDir(null),"music-space-stage8").apply{mkdirs()}
        instrumentation.uiAutomation.takeScreenshot().let{bitmap->File(folder,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
    }
    @Test fun guestPagesRemainLocalAndDoNotScheduleAccountUploads() {
        val services=(context.applicationContext as PilgrimageApplication).services
        compose.waitUntil(12000){services.authReady.value&&compose.onAllNodesWithTag("room-home").fetchSemanticsNodes().isNotEmpty()}
        assumeTrue("Do not operate a signed-in account",services.userId.value==null)
        compose.onNodeWithTag("room-space-button").performClick();compose.onNodeWithTag("room-module-library").performClick()
        compose.onNodeWithTag("room-archive").performScrollToNode(hasTestTag("room-drawer-songs"))
        compose.onNodeWithTag("room-drawer-songs").performClick()
        compose.onNodeWithTag("room-music-favorites").assertIsDisplayed()
        compose.onNodeWithText("行程和音乐收藏保存在这台设备。").assertIsDisplayed();capture("favorites")
        // Production trips remain account-gated. Preview the real guest cache directly,
        // as in the existing trip UI harness, without creating a session or changing plans.
        val vm=ViewModelProvider(compose.activity)[MusicTripViewModel::class.java]
        compose.waitUntil(5000){vm.state.value.owner.isNotBlank()&&!vm.state.value.busy}
        compose.runOnUiThread{compose.activity.setContent{val rows by vm.state.collectAsState();PilgrimageTheme{RoomWall(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Column{RoomHeader("我的行程",onBack={},onSpace={});Box(Modifier.weight(1f)){RoomTripList(rows,rows.owner,emptyList(),vm){}}}
        }}}}
        compose.onNodeWithTag("room-trips").assertIsDisplayed();compose.onNodeWithText("行程和音乐收藏保存在这台设备。").assertIsDisplayed()
        compose.onNodeWithTag("room-library-sync-retry").assertDoesNotExist();capture("trips")
        assertTrue(WorkManager.getInstance(context).getWorkInfosByTag("personal-library-sync").get().none{!it.state.isFinished})
        var rejected=false
        kotlinx.coroutines.runBlocking{try{services.call("library_state",expectedOwner=null)}catch(_:IllegalArgumentException){rejected=true}}
        assertTrue("Guest library RPC must stop before sending a request",rejected)
    }
    /** Stateless feedback fixture only; this does not create or replace an authenticated session. */
    @Test fun syncFeedbackHasRetryAndDoesNotClaimSuccessWhileWaiting() {
        val state=mutableStateOf(PersonalLibrarySyncState("user:ui-status-fixture",LibrarySyncStatus.ERROR,error="暂时无法同步，本机更改已保留。"));var retries=0
        compose.runOnUiThread{compose.activity.setContent{PilgrimageTheme{RoomWall(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Column {RoomHeader("同步状态",onBack={},onSpace={});RoomPaper(Modifier.padding(18.dp)){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("界面反馈验证")
                RoomLibrarySyncStatus(state.value,{retries++;state.value=state.value.copy(status=LibrarySyncStatus.SYNCING,error=null)})
            }}}
        }}}}
        compose.onNodeWithText("重试").assertIsDisplayed();capture("sync-retry")
        compose.onNodeWithTag("room-library-sync-retry").performClick();assertEquals(1,retries)
        compose.onNodeWithText("正在同步，本机更改会保留").assertIsDisplayed();compose.onNodeWithTag("room-library-sync-retry").assertIsNotEnabled()
        compose.runOnIdle{state.value=state.value.copy(status=LibrarySyncStatus.WAITING)}
        compose.onNodeWithText("已保存到本机，等待同步").assertIsDisplayed();compose.onNodeWithText("行程和音乐收藏已同步").assertDoesNotExist()
        compose.runOnIdle{state.value=state.value.copy(status=LibrarySyncStatus.SYNCED,notice="另一设备也修改了行程，两个版本已分别保留。")}
        compose.onNodeWithText("行程和音乐收藏已同步").assertIsDisplayed();compose.onNodeWithText("另一设备也修改了行程，两个版本已分别保留。").assertIsDisplayed();capture("sync-complete")
    }
    /** Guest cache fixture exercises editor refresh; no authentication or cloud write is used. */
    @Test fun staleTripEditorRetainsDraftAndReportsConflict() {
        val services=(context.applicationContext as PilgrimageApplication).services
        compose.waitUntil(12000){services.authReady.value&&compose.onAllNodesWithTag("room-home").fetchSemanticsNodes().isNotEmpty()}
        assumeTrue("Do not operate a signed-in account",services.userId.value==null)
        val owner=LibraryDatabase.guestOwner(context);val store=services.library.trips
        val start=LocalDate.of(2026,11,1)
        val fixture=MusicTrip(UUID.randomUUID().toString(),"编辑冲突界面验证",start,start.plusDays(2),listOf(MusicTripCity("巴黎","法国")))
        try {
            store.update(owner){it+fixture}
            val vm=ViewModelProvider(compose.activity)[MusicTripViewModel::class.java]
            compose.waitUntil(5000){vm.state.value.trips.any{it.id==fixture.id}}
            compose.runOnUiThread{compose.activity.setContent{val state by vm.state.collectAsState();PilgrimageTheme{RoomWall(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                state.trips.find{it.id==fixture.id}?.let{RoomTripDetail(it,state,owner,vm,{})}
            }}}}
            compose.onNodeWithText("名称与城市").performClick()
            compose.onNode(hasSetTextAction() and hasText(fixture.name)).performTextReplacement("尚未保存的名称")
            store.update(owner){rows->rows.map{if(it.id==fixture.id)it.copy(name="收到的新名称")else it}}
            compose.waitUntil(5000){vm.state.value.trips.any{it.id==fixture.id&&it.name=="收到的新名称"}}
            compose.onNodeWithText("保存").performScrollTo().performClick()
            compose.onNode(hasText("行程名称已更新，请关闭后重新打开编辑") and hasAnyAncestor(isDialog())).performScrollTo().assertIsDisplayed()
            compose.onNode(hasSetTextAction() and hasText("尚未保存的名称")).assertExists()
            assertEquals("收到的新名称",store.read(owner).first{it.id==fixture.id}.name)
            capture("editor-conflict")
            compose.onNodeWithText("取消").performScrollTo().performClick()
            compose.onNodeWithText("名称与城市").performClick()
            compose.onNode(hasSetTextAction() and hasText("收到的新名称")).performTextReplacement("确认后的名称")
            compose.onNodeWithText("保存").performScrollTo().performClick()
            compose.waitUntil(5000){vm.state.value.trips.any{it.id==fixture.id&&it.name=="确认后的名称"}}
            compose.onNodeWithText("确认后的名称").assertIsDisplayed()
            compose.onNodeWithText("修改日期").performClick()
            compose.onNodeWithText("修改出行日期").assertIsDisplayed()
            compose.onNodeWithText("保存").performClick()
            compose.waitUntil(5000){compose.onAllNodesWithText("修改出行日期").fetchSemanticsNodes().isEmpty()}
            compose.onNodeWithText("添加地点").performClick()
            compose.onNode(hasSetTextAction() and hasText("地点名称")).performTextInput("手动地点验证")
            store.update(owner){rows->rows.map{if(it.id==fixture.id)it.copy(cities=listOf(MusicTripCity("里昂","法国")))else it}}
            compose.onNodeWithText("行程城市已更新，请重新选择").assertIsDisplayed()
            compose.onNodeWithText("添加",substring=false).assertIsNotEnabled()
            compose.onNode(hasText("里昂",substring=false) and hasAnyAncestor(isDialog())).performClick()
            compose.onNodeWithText("添加",substring=false).performClick()
            compose.waitUntil(5000){vm.state.value.trips.any{it.id==fixture.id&&it.stops.any{s->s.name=="手动地点验证"&&s.city=="里昂"}}}
        } finally {store.update(owner){rows->rows.filterNot{it.id==fixture.id}}}
    }
}
