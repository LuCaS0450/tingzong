package cn.musicpilgrimage.app

import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cn.musicpilgrimage.app.data.MusicCatalogue
import cn.musicpilgrimage.app.design.AppPreferences
import cn.musicpilgrimage.app.performance.*
import cn.musicpilgrimage.app.ui.PilgrimageTheme
import cn.musicpilgrimage.app.ui.room.RoomWall
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class MusicPerformanceUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    @Before fun nativeFrameClock(){compose.mainClock.autoAdvance=false}
    private fun await(condition:()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+45000
        while(!condition()&&SystemClock.elapsedRealtime()<deadline){compose.mainClock.advanceTimeByFrame();Thread.sleep(10)}
        assertTrue("演奏未到达预期状态",condition());compose.mainClock.advanceTimeBy(32);compose.waitForIdle()
    }
    private fun capture(name:String) {
        settle()
        // The platform/Coil artwork crossfade uses the Android clock rather than the test clock.
        Thread.sleep(350);compose.mainClock.advanceTimeByFrame();compose.waitForIdle();instrumentation.waitForIdleSync()
        val folder=File(context.getExternalFilesDir(null),"music-space-stage4").apply{mkdirs()}
        instrumentation.uiAutomation.takeScreenshot().let{bitmap->File(folder,"$name.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
    }
    private fun settle() {
        // First commit starts LaunchedEffect transitions; the following frames finish them.
        compose.mainClock.advanceTimeBy(300);compose.waitForIdle()
        compose.mainClock.advanceTimeBy(300);compose.waitForIdle()
    }
    @Test fun nativeSpaceCataloguePreparationAndStandardOnly() {
        val prefs=context.getSharedPreferences("music-performance-ui",0)
        val key="${LibraryDatabase.guestOwner(context)}:difficulty"
        val previous=if(prefs.contains(key))prefs.getInt(key,1)else null
        prefs.edit().putInt(key,1).commit()
        try {ActivityScenario.launch(MusicSpacePreviewActivity::class.java).use {
            await{compose.onAllNodesWithTag("room-home").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("room-space-button").performClick();settle()
            compose.onNodeWithTag("room-module-performance").performClick()
            await{compose.onAllNodesWithTag("performance-list").fetchSemanticsNodes().isNotEmpty()}
            capture("selection")
            compose.onNodeWithTag("performance-song-greatest-works").performScrollTo().performClick()
            await{compose.onAllNodesWithTag("performance-preparation").fetchSemanticsNodes().isNotEmpty()}
            settle()
            compose.onNodeWithText("试听",substring=false).assertDoesNotExist()
            compose.onNodeWithText("练习",substring=false).assertDoesNotExist()
            compose.onNodeWithTag("performance-difficulty-1").assertDoesNotExist()
            compose.onNodeWithTag("performance-difficulty-2").assertDoesNotExist()
            compose.onNodeWithText("标准演奏").assertIsDisplayed()
            capture("preparation")
            compose.onNodeWithTag("room-back").performClick()
            settle()
            compose.onNodeWithTag("performance-song-greatest-works").performScrollTo().performClick()
            settle()
            compose.onNodeWithText("标准演奏").assertIsDisplayed()
        }} finally {prefs.edit().apply{if(previous==null)remove(key)else putInt(key,previous)}.commit()}
    }
    @Test fun localShortSampleCountdownMutePauseExitAndResult() {
        val owner="test:performance-ui:${UUID.randomUUID()}"
        val entry=RhythmPreview.entry(context){}
        requireNotNull(entry.audioDirectory){"需先用已有独立试玩入口导入本地短样"}
        val preferences=AppPreferences(context)
        val previous=runBlocking{preferences.read()}
        lateinit var controller:PerformanceController
        var controllerReady=false
        var immersive by mutableStateOf(false)
        var related=false
        try {
            runBlocking{preferences.setMuted(true)}
            ActivityScenario.launch(MainActivity::class.java).use {activity->
                await{(context.applicationContext as PilgrimageApplication).services.catalog.value.places.isNotEmpty()}
                activity.onActivity{host->
                    val catalogue=MusicCatalogue.from((host.application as PilgrimageApplication).services.catalog.value)
                    val art=catalogue.works.firstOrNull{it.title==entry.song.title&&it.artist==entry.song.artist}?.artwork
                    host.setContent{PilgrimageTheme{RoomWall(Modifier.fillMaxSize().then(if(immersive)Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing))) {
                        PerformanceFlow(entry,owner,{},art,{related=true},{immersive=it},{if(it!=null){controller=it;controllerReady=true}})
                    }}}
                }
                await{compose.onAllNodesWithTag("performance-preparation").fetchSemanticsNodes().isNotEmpty()}
                settle()
                compose.onNodeWithTag("performance-enter").performScrollTo().performClick()
                await{controllerReady&&controller.ui.value.countIn==3}
                val countedAt=SystemClock.elapsedRealtime()
                assertEquals(0L,controller.ui.value.positionUs)
                capture("countdown")
                compose.onNodeWithTag("room-space-button").assertDoesNotExist()
                await{controller.ui.value.countIn==0&&controller.ui.value.positionUs>50_000}
                assertTrue("倒计时应为3秒",SystemClock.elapsedRealtime()-countedAt in 2400..4500)
                await{controller.audioOutputPeak()==0f&&controller.ui.value.positionUs>180_000}
                runBlocking{preferences.setMuted(false)}
                await{(controller.audioOutputPeak()?:0f)>.001f}
                runBlocking{preferences.setMuted(true)}
                await{controller.audioOutputPeak()==0f}
                val approaching=controller.targets.firstOrNull{it.onsetUs>controller.ui.value.positionUs+800_000}
                if(approaching!=null)await{controller.ui.value.positionUs>=approaching.onsetUs-500_000}
                capture("gameplay")
                compose.onNodeWithTag("performance-pause").performSemanticsAction(SemanticsActions.OnClick)
                await{controller.ui.value.state==PlayState.PAUSED&&controller.ui.value.saved}
                val pausedAt=controller.ui.value.positionUs
                Thread.sleep(100);assertEquals(pausedAt,controller.ui.value.positionUs)
                activity.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                Thread.sleep(100)
                activity.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                // Let the platform's activity-resume window animation finish before its screenshot.
                Thread.sleep(700)
                assertEquals(PlayState.PAUSED,controller.ui.value.state)
                assertEquals(pausedAt,controller.ui.value.positionUs)
                capture("paused")
                compose.onNodeWithTag("performance-start").performClick()
                compose.mainClock.advanceTimeBy(32);compose.waitForIdle()
                compose.onNodeWithTag("performance-pause-panel").assertDoesNotExist()
                compose.onNodeWithTag("performance-countdown").assertIsDisplayed()
                capture("resume-countdown")
                compose.onNodeWithTag("performance-back").performClick()
                compose.mainClock.advanceTimeBy(160);compose.waitForIdle()
                compose.onNodeWithText("返回准备页？").assertIsDisplayed()
                capture("exit-confirmation")
                compose.onNodeWithTag("performance-stay").performClick()
                await{controller.ui.value.state==PlayState.FINISHED&&controller.ui.value.saved}
                compose.onNodeWithTag("performance-result").assertIsDisplayed()
                compose.onNodeWithText("新纪录").assertDoesNotExist()
                capture("short-sample-result")
                compose.onNodeWithTag("performance-explore").performScrollTo().performClick();assertTrue(related)
                assertTrue(runBlocking{LibraryDatabase.open(context).performance().results(owner).first()}.isEmpty())
                compose.onNodeWithTag("performance-again").performScrollTo().performClick()
                await{controller.ui.value.countIn==3}
                compose.onNodeWithTag("performance-back").performClick()
                compose.mainClock.advanceTimeBy(160);compose.waitForIdle()
                compose.onNodeWithTag("performance-confirm-exit").performClick()
                compose.mainClock.advanceTimeBy(32);compose.waitForIdle()
                compose.onNodeWithTag("performance-preparation").assertIsDisplayed()
            }
        } finally {
            runBlocking{preferences.set(previous)}
            runBlocking(Dispatchers.IO){LibraryDatabase.open(context).openHelper.writableDatabase.apply {
                execSQL("delete from performance_checkpoints where owner=?",arrayOf(owner))
                execSQL("delete from performance_runs where owner=?",arrayOf(owner))
                execSQL("delete from performance_results where owner=?",arrayOf(owner))
            }}
        }
    }
}
