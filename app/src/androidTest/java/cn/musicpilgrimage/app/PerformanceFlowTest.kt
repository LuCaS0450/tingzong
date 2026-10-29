package cn.musicpilgrimage.app

import android.app.Application
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.lifecycle.*
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cn.musicpilgrimage.app.performance.*
import cn.musicpilgrimage.app.ui.PilgrimageTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

/** Deliberately artificial notes validate control behavior; never shipped as the first song. */
class PerformanceFlowTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture():SongPackage {
        val events=listOf(MusicEvent(0,1_700_000,250_000,69,100,3),MusicEvent(1,3_000_000,1_400_000,72,100,3),MusicEvent(2,6_000_000,300_000,76,100,3),
            MusicEvent(3,6_000_000,600_000,48,65,0)).sortedBy{it.onsetUs}.mapIndexed{i,e->e.copy(id=i)}
        val targets=listOf(Target(0,listOf(0),0,1_700_000,250_000,false),Target(1,listOf(1),1,3_000_000,1_400_000,true),Target(2,listOf(2),2,6_000_000,300_000,false))
        return SongPackage("greatest-works",999,"操作测试音符（非原曲）","测试","人工测试序列",8_000_000,events,targets,targets,
            listOf(SongSection("测试大厅",0,2_000_000,"hall",120.0),SongSection("测试画框",2_000_000,5_000_000,"gallery",120.0),SongSection("测试天光",5_000_000,8_000_000,"sky",120.0)),emptyList(),JSONObject())
    }
    private fun create(store:ViewModelStore,owner:String,options:PlayOptions):PerformanceController=ViewModelProvider(store,object:ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass:Class<T>):T=PerformanceController(context.applicationContext as Application,fixture(),owner,options) as T
    })[PerformanceController::class.java]
    @Before fun useRealAudioTime(){compose.mainClock.autoAdvance=false}
    private fun await(condition:()->Boolean) {
        val deadline=android.os.SystemClock.elapsedRealtime()+15000
        while(!condition() && android.os.SystemClock.elapsedRealtime()<deadline){compose.mainClock.advanceTimeByFrame();Thread.sleep(10)}
        assertTrue("当前演奏状态未在规定时间到达",condition())
        compose.mainClock.advanceTimeBy(32);compose.waitForIdle()
    }
    private fun capture(name:String) {java.io.File(context.getExternalFilesDir(null),name).outputStream().use {
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
    }}
    private fun cleanup(owner:String) = runBlocking(Dispatchers.IO) {
        LibraryDatabase.open(context).openHelper.writableDatabase.apply {
            execSQL("delete from performance_checkpoints where owner=?",arrayOf(owner))
            execSQL("delete from performance_runs where owner=?",arrayOf(owner))
            execSQL("delete from performance_results where owner=?",arrayOf(owner))
        }
    }
    @Test fun realTouchHoldPauseProcessRecoveryAndResultAreConsistent() {
        val owner="test:${UUID.randomUUID()}"
        var store=ViewModelStore()
        lateinit var controller:PerformanceController
        var visible by mutableStateOf<PerformanceController?>(null)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {activity->
                activity.onActivity{host->controller=create(store,owner,PlayOptions());visible=controller
                    host.setContent{PilgrimageTheme{visible?.let{PerformanceScreen(it,{})}}}}
                await{controller.ui.value.state==PlayState.READY}
                compose.onNodeWithTag("performance-start").performClick()
                await{controller.ui.value.countIn==0 && controller.ui.value.positionUs>=1_640_000}
                compose.onNodeWithTag("performance-lane-0").performTouchInput{click(center)}
                assertTrue(controller.ui.value.judgments[0] in listOf(Judgment.PERFECT,Judgment.GOOD))
                // Do not block the live audio clock on a screenshot before the next target.
                await{controller.ui.value.positionUs>=2_940_000}
                compose.onNodeWithTag("performance-lane-1").performTouchInput{down(center)}
                assertEquals("长按起点 ${controller.ui.value.positionUs}",Judgment.HOLDING,controller.ui.value.judgments[1])
                await{controller.ui.value.positionUs>=3_220_000}
                compose.onNodeWithTag("performance-pause").performSemanticsAction(SemanticsActions.OnClick)
                compose.onNodeWithTag("performance-lane-1").performTouchInput{up()}
                await{controller.ui.value.saved}
                capture("performance-paused.png")
                val pauseAt=controller.ui.value.positionUs
                Thread.sleep(120);assertEquals(pauseAt,controller.ui.value.positionUs)
                activity.onActivity{store.clear();store=ViewModelStore();controller=create(store,owner,PlayOptions());visible=controller}
                await{controller.ui.value.state==PlayState.PAUSED}
                assertEquals(pauseAt,controller.ui.value.positionUs)
                assertEquals(1_000_000L,pauseAt)
                assertEquals(Judgment.WAITING,controller.ui.value.judgments[1])
                compose.onNodeWithTag("performance-start").performClick()
                await{controller.ui.value.countIn==0 && controller.ui.value.positionUs>=2_940_000}
                compose.onNodeWithTag("performance-lane-1").performTouchInput{down(center)}
                await{controller.ui.value.positionUs>=4_360_000}
                compose.onNodeWithTag("performance-lane-1").performTouchInput{up()}
                await{controller.ui.value.countIn==0 && controller.ui.value.positionUs>=5_940_000}
                compose.onNodeWithTag("performance-lane-2").performTouchInput{click(center)}
                await{controller.ui.value.state==PlayState.FINISHED && controller.ui.value.saved}
                compose.onNodeWithTag("performance-result").assertIsDisplayed()
                compose.onNodeWithText("新纪录").assertIsDisplayed()
                activity.onActivity{host->assertTrue(androidx.core.view.WindowCompat.getInsetsController(host.window,host.window.decorView).isAppearanceLightStatusBars)}
                capture("performance-result.png")
                assertTrue(controller.ui.value.judgments.all{it in listOf(Judgment.PERFECT,Judgment.GOOD)})
                val rows=runBlocking{LibraryDatabase.open(context).performance().results(owner).first()}
                assertEquals(1,rows.size);assertEquals(controller.ui.value.score,rows.single().score)
                activity.onActivity{store.clear()}
            }
        } finally {InstrumentationRegistry.getInstrumentation().runOnMainSync{store.clear()};cleanup(owner)}
    }
    @Test fun backgroundStopsPlaybackAndListenNeverCreatesFormalResult() {
        val owner="test:${UUID.randomUUID()}";val store=ViewModelStore()
        lateinit var controller:PerformanceController
        try {
            ActivityScenario.launch(MainActivity::class.java).use {activity->
                activity.onActivity{host->controller=create(store,owner,PlayOptions(mode=PlayMode.LISTEN));host.setContent{PilgrimageTheme{PerformanceScreen(controller,{})}}}
                await{controller.ui.value.state==PlayState.READY}
                compose.onNodeWithTag("performance-start").performClick()
                await{controller.ui.value.countIn==0 && controller.ui.value.positionUs>300_000}
                activity.moveToState(Lifecycle.State.CREATED)
                await{controller.ui.value.state==PlayState.PAUSED && controller.ui.value.saved}
                val pauseAt=controller.ui.value.positionUs;Thread.sleep(150);assertEquals(pauseAt,controller.ui.value.positionUs)
                activity.moveToState(Lifecycle.State.RESUMED)
                compose.onNodeWithTag("performance-start").performClick()
                await{controller.ui.value.state==PlayState.FINISHED && controller.ui.value.saved}
                assertTrue(runBlocking{LibraryDatabase.open(context).performance().results(owner).first()}.isEmpty())
                activity.onActivity{store.clear()}
            }
        } finally {InstrumentationRegistry.getInstrumentation().runOnMainSync{store.clear()};cleanup(owner)}
    }
    @Test fun originalSpeedPracticeLoopsWithoutWritingAFormalScore() {
        val owner="test:${UUID.randomUUID()}";val store=ViewModelStore()
        lateinit var controller:PerformanceController
        try {
            ActivityScenario.launch(MainActivity::class.java).use {activity->
                activity.onActivity{host->controller=create(store,owner,PlayOptions(mode=PlayMode.PRACTICE,section=0,loop=true));host.setContent{PilgrimageTheme{PerformanceScreen(controller,{})}}}
                await{controller.ui.value.state==PlayState.READY}
                assertEquals(1.0,controller.options.speed,0.0)
                compose.onNodeWithTag("performance-start").performClick()
                await{controller.ui.value.positionUs>1_200_000}
                await{controller.ui.value.state==PlayState.PLAYING && controller.ui.value.countIn>0 && controller.ui.value.positionUs==0L}
                compose.onNodeWithTag("performance-pause").performClick()
                await{controller.ui.value.state==PlayState.PAUSED && controller.ui.value.saved}
                assertTrue(runBlocking{LibraryDatabase.open(context).performance().results(owner).first()}.isEmpty())
                activity.onActivity{store.clear()}
            }
        } finally {InstrumentationRegistry.getInstrumentation().runOnMainSync{store.clear()};cleanup(owner)}
    }
}
