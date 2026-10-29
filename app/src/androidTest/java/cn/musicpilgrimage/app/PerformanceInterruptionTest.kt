package cn.musicpilgrimage.app

import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cn.musicpilgrimage.app.performance.*
import cn.musicpilgrimage.app.ui.PilgrimageTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Private test owners; the explicit two-phase test requires a host-issued process kill. */
class PerformanceInterruptionTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val evidence get()=File(context.getExternalFilesDir(null),"performance-interruptions").apply{mkdirs()}
    private val processOwner="test:performance-interruption:process"
    private fun song()=PerformanceCatalog.load(context).single{it.id=="common-jasmine-orange"}
    private external fun disconnectNative(handle:Long)
    @Before fun realAudioClock(){compose.mainClock.autoAdvance=false}

    private fun await(timeout:Long=45000,condition:()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+timeout
        while(!condition()&&SystemClock.elapsedRealtime()<deadline){compose.mainClock.advanceTimeByFrame();Thread.sleep(12)}
        assertTrue("Interruption test timed out",condition())
        compose.mainClock.advanceTimeBy(32);compose.waitForIdle()
    }
    private fun create(store:ViewModelStore,owner:String)=ViewModelProvider(store,object:ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass:Class<T>):T=
            PerformanceController(context.applicationContext as PilgrimageApplication,song(),owner,PlayOptions(),
                requireNotNull(PerformanceAudioStore(context).directory(song()))) as T
    })[PerformanceController::class.java]
    private fun show(activity:ActivityScenario<MainActivity>,store:ViewModelStore,owner:String):PerformanceController {
        lateinit var controller:PerformanceController
        activity.onActivity{host->controller=create(store,owner);host.setContent{PilgrimageTheme{PerformanceScreen(controller,{})}}}
        return controller
    }
    private fun cleanup(store:ViewModelStore,owner:String) {
        instrumentation.runOnMainSync{store.clear()}
        Thread.sleep(250)
        runBlocking(Dispatchers.IO){LibraryDatabase.open(context).openHelper.writableDatabase.apply {
            for(table in listOf("performance_checkpoints","performance_runs","performance_results"))
                execSQL("delete from $table where owner=?",arrayOf(owner))
        }}
    }
    private fun capture(name:String) {
        compose.mainClock.advanceTimeBy(200);compose.waitForIdle();instrumentation.waitForIdleSync()
        val bitmap=instrumentation.uiAutomation.takeScreenshot()
        File(evidence,"$name.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
    private fun hitFirstTap(controller:PerformanceController) {
        val tap=controller.targets.first{!it.hold}
        await{controller.ui.value.countIn==0&&controller.ui.value.positionUs>=tap.onsetUs-60000}
        compose.onNodeWithTag("performance-lane-${tap.lane}").performTouchInput{click(center)}
        assertTrue(controller.ui.value.judgments[tap.id] in listOf(Judgment.PERFECT,Judgment.GOOD))
    }

    @Test fun pausedHoldReplaysWithLeadInAndNewPhysicalPress() {
        val owner="test:performance-interruption:${UUID.randomUUID()}"
        val store=ViewModelStore()
        try {
            ActivityScenario.launch(MainActivity::class.java).use{activity->
                val controller=show(activity,store,owner)
                await{controller.ui.value.state==PlayState.READY}
                compose.onNodeWithTag("performance-start").performClick();hitFirstTap(controller)
                val hold=controller.targets.first{it.hold}
                await{controller.ui.value.positionUs>=hold.onsetUs-50000}
                compose.onNodeWithTag("performance-lane-${hold.lane}").performTouchInput{down(center)}
                await{controller.ui.value.positionUs>=hold.onsetUs+100000}
                val previousScore=controller.ui.value.score
                val resolved=controller.ui.value.judgments.withIndex().filter{it.value in listOf(Judgment.PERFECT,Judgment.GOOD,Judgment.MISSED,Judgment.PARTIAL)}
                compose.onNodeWithTag("performance-pause").performTouchInput{down(1,center);up(1)}
                await{controller.ui.value.state==PlayState.PAUSED}
                compose.onNodeWithTag("performance-lane-${hold.lane}").performTouchInput{up()}
                val rewind=hold.onsetUs-2_000_000
                assertEquals(rewind,controller.ui.value.positionUs)
                assertEquals(Judgment.WAITING,controller.ui.value.judgments[hold.id])
                assertEquals(previousScore,controller.ui.value.score)
                instrumentation.runOnMainSync{controller.pause()}
                assertEquals(rewind,controller.ui.value.positionUs)
                capture("hold-rewind-paused")
                compose.onNodeWithTag("performance-start").performClick()
                await{controller.ui.value.countIn==0&&controller.ui.value.positionUs>=hold.onsetUs-50000}
                compose.onNodeWithTag("performance-lane-${hold.lane}").performTouchInput{down(center)}
                assertEquals(Judgment.HOLDING,controller.ui.value.judgments[hold.id])
                await{controller.ui.value.positionUs>=hold.endUs-40000}
                compose.onNodeWithTag("performance-lane-${hold.lane}").performTouchInput{up()}
                assertTrue(controller.ui.value.judgments[hold.id] in listOf(Judgment.PERFECT,Judgment.GOOD))
                resolved.forEach{assertEquals(it.value,controller.ui.value.judgments[it.index])}
                assertTrue(controller.ui.value.score>previousScore)
                capture("hold-replayed-with-new-press")
                File(evidence,"manual-hold.json").writeText(JSONObject().put("passed",true).put("rewindUs",rewind)
                    .put("newPhysicalPressRequired",true).put("resolvedNotesPreserved",true).toString())
            }
        } finally {cleanup(store,owner)}
    }

    @Test fun closedNativeStreamRebuildsAndWaitsForManualContinue() {
        val owner="test:performance-interruption:${UUID.randomUUID()}"
        val store=ViewModelStore()
        try {
            ActivityScenario.launch(MainActivity::class.java).use{activity->
                val controller=show(activity,store,owner)
                await{controller.ui.value.state==PlayState.READY}
                compose.onNodeWithTag("performance-start").performClick();hitFirstTap(controller)
                val previous=controller.ui.value
                val field=PerformanceController::class.java.getDeclaredField("piano").apply{isAccessible=true}
                instrumentation.runOnMainSync{disconnectNative((field.get(controller) as NativePiano).handle)}
                await{controller.ui.value.state==PlayState.RECOVERING}
                await{controller.ui.value.state==PlayState.PAUSED&&controller.ui.value.message=="声音已恢复，点击继续演奏"}
                val paused=controller.ui.value.positionUs
                assertEquals(previous.score,controller.ui.value.score)
                assertTrue(kotlin.math.abs(paused-previous.positionUs)<150000)
                Thread.sleep(300)
                assertEquals(paused,controller.ui.value.positionUs)
                assertEquals(0f,controller.audioOutputPeak() ?: -1f,0f)
                capture("audio-recovered-waiting")
                compose.onNodeWithTag("performance-start").performClick()
                await{controller.ui.value.countIn==0&&controller.ui.value.positionUs>=paused+500000}
                assertTrue((controller.audioOutputPeak() ?: 0f)>.0001f)
                assertEquals(previous.score,controller.ui.value.score)
                File(evidence,"native-stream-recovery.json").writeText(JSONObject().put("passed",true)
                    .put("realStreamClosed",true).put("manualContinueRequired",true).put("positionPreservedUs",paused).toString())
            }
        } finally {cleanup(store,owner)}
    }

    @Test fun beforeProcessKillWhileHolding() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("interruptionPhase")=="before-kill")
        val store=ViewModelStore()
        cleanup(ViewModelStore(),processOwner)
        try {
            ActivityScenario.launch(MainActivity::class.java).use{activity->
                val controller=show(activity,store,processOwner)
                await{controller.ui.value.state==PlayState.READY}
                compose.onNodeWithTag("performance-start").performClick();hitFirstTap(controller)
                val hold=controller.targets.first{it.hold}
                await{controller.ui.value.positionUs>=hold.onsetUs-50000}
                compose.onNodeWithTag("performance-lane-${hold.lane}").performTouchInput{down(center)}
                var row:PerformanceCheckpoint?=null
                await {
                    row=runBlocking(Dispatchers.IO){LibraryDatabase.open(context).performance().checkpoint(processOwner,controller.song.id,controller.song.version,2,"play")}
                    row?.let{JSONObject(it.snapshot).getJSONArray("progress").getJSONObject(hold.id).getString("state")=="HOLDING"}==true
                }
                File(evidence,"kill-ready.json").writeText(JSONObject().put("pid",android.os.Process.myPid())
                    .put("checkpointUs",row!!.positionUs).put("rewindUs",hold.onsetUs-2_000_000)
                    .put("targetId",hold.id).put("score",controller.ui.value.score).toString())
                Thread.sleep(30000)
                fail("Host did not terminate the process while the hold was active")
            }
        } finally {cleanup(store,processOwner)}
    }

    @Test fun afterProcessKillRestoresLeadInAndNeverInventsHeldFinger() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("interruptionPhase")=="after-kill")
        val expected=JSONObject(File(evidence,"kill-ready.json").readText())
        assertNotEquals(expected.getInt("pid"),android.os.Process.myPid())
        val store=ViewModelStore()
        try {
            ActivityScenario.launch(MainActivity::class.java).use{activity->
                val controller=show(activity,store,processOwner)
                await{controller.ui.value.state==PlayState.PAUSED}
                val rewind=expected.getLong("rewindUs")
                val hold=controller.targets[expected.getInt("targetId")]
                assertEquals(rewind,controller.ui.value.positionUs)
                assertEquals(Judgment.WAITING,controller.ui.value.judgments[hold.id])
                assertEquals(expected.getInt("score"),controller.ui.value.score)
                capture("process-restored-with-lead-in")
                compose.onNodeWithTag("performance-start").performClick()
                await{controller.ui.value.countIn==0&&controller.ui.value.positionUs>=hold.endUs+100000}
                assertEquals(Judgment.MISSED,controller.ui.value.judgments[hold.id])
                assertEquals(expected.getInt("score"),controller.ui.value.score)
                File(evidence,"process-recovery.json").writeText(JSONObject().put("passed",true).put("actualProcessKill",true)
                    .put("rewindUs",rewind).put("untouchedHoldMissed",true).put("previousScorePreserved",true).toString())
            }
        } finally {cleanup(store,processOwner)}
    }
}
