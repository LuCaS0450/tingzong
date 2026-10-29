package cn.musicpilgrimage.app

import android.os.Process
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cn.musicpilgrimage.app.data.MusicCatalogue
import cn.musicpilgrimage.app.design.AppPreferences
import cn.musicpilgrimage.app.performance.*
import cn.musicpilgrimage.app.ui.PilgrimageTheme
import cn.musicpilgrimage.app.ui.room.RoomWall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.UUID

/** Two explicitly selected phases: the host actually force-stops the app between them. */
class FullPerformanceRecoveryTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val handoff get()=File(context.getExternalFilesDir(null),"full-performance-recovery.json")
    private lateinit var controller:PerformanceController
    private var ready=false
    @Before fun nativeClock(){compose.mainClock.autoAdvance=false}
    private fun await(timeout:Long=45000,condition:()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+timeout
        while(!condition()&&SystemClock.elapsedRealtime()<deadline){compose.mainClock.advanceTimeByFrame();Thread.sleep(10)}
        assertTrue("整曲未到达预期状态: ${if(ready)controller.ui.value else "controller not created"}",condition())
        compose.mainClock.advanceTimeBy(32);compose.waitForIdle()
    }
    private fun attach(activity:ActivityScenario<MainActivity>,owner:String,song:SongPackage) {
        activity.onActivity{host->
            val catalogue=MusicCatalogue.from((host.application as PilgrimageApplication).services.catalog.value)
            val art=catalogue.works.firstOrNull{it.title==song.title&&it.artist==song.artist}?.artwork
            var immersive by mutableStateOf(false)
            val entry=PerformanceEntry(song,audioDirectory=requireNotNull(PerformanceAudioStore(host).directory(song)))
            assertFalse(entry.preview);assertTrue(entry.audioReady)
            host.setContent{PilgrimageTheme{RoomWall(Modifier.fillMaxSize().then(if(immersive)Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing))) {
                PerformanceFlow(entry,owner,{},art,onFullscreen={immersive=it},onController={if(it!=null){controller=it;ready=true}})
            }}}
        }
        await{compose.onAllNodesWithTag("performance-preparation").fetchSemanticsNodes().isNotEmpty()}
        compose.mainClock.advanceTimeBy(500);compose.waitForIdle()
        compose.onNodeWithTag("performance-enter").performScrollTo().performClick()
        await{ready&&controller.ui.value.countIn==3}
        assertEquals(PlayMode.PLAY,controller.options.mode);assertEquals(2,controller.options.difficulty);assertEquals(1.0,controller.options.speed,0.0)
    }
    private fun capture(name:String) {
        compose.mainClock.advanceTimeBy(250);compose.waitForIdle();instrumentation.waitForIdleSync()
        val folder=File(context.getExternalFilesDir(null),"formal-performance").apply{mkdirs()}
        instrumentation.uiAutomation.takeScreenshot().let{bitmap->File(folder,"$name.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
    }
    @Test fun beforeActualProcessKill() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("fullRecoveryPhase")=="before-kill")
        handoff.delete()
        val song=PerformanceCatalog.load(context).single{it.id=="greatest-works"}
        val owner="test:formal-full:${UUID.randomUUID()}"
        ActivityScenario.launch(MainActivity::class.java).use{activity->
            await{(context.applicationContext as PilgrimageApplication).services.catalog.value.places.isNotEmpty()}
            attach(activity,owner,song)
            val tap=controller.targets.first{!it.hold}
            await{controller.ui.value.countIn==0&&controller.ui.value.positionUs>=tap.onsetUs-70_000}
            compose.onNodeWithTag("performance-lane-${tap.lane}").performTouchInput{click(center)}
            assertTrue(controller.ui.value.judgments[tap.id] in listOf(Judgment.PERFECT,Judgment.GOOD))
            val preferences=AppPreferences(context)
            val wasMuted=runBlocking{preferences.read().muted}
            try {
                runBlocking{preferences.setMuted(false)}
                await{(controller.audioOutputPeak() ?: 0f)>.001f}
            } finally {runBlocking{preferences.setMuted(wasMuted)}}
            val hold=controller.targets.first{it.hold}
            await{controller.ui.value.positionUs>=hold.onsetUs-70_000}
            compose.onNodeWithTag("performance-lane-${hold.lane}").performTouchInput{down(center)}
            assertEquals(Judgment.HOLDING,controller.ui.value.judgments[hold.id])
            await{controller.ui.value.positionUs>=hold.endUs-40_000}
            compose.onNodeWithTag("performance-lane-${hold.lane}").performTouchInput{up()}
            assertTrue(controller.ui.value.judgments[hold.id] in listOf(Judgment.PERFECT,Judgment.GOOD))
            await{controller.ui.value.positionUs>=maxOf(12_150_000,hold.endUs+2_200_000)}
            capture("full-song-playing")
            val row=runBlocking(Dispatchers.IO){LibraryDatabase.open(context).performance().checkpoint(owner,song.id,song.version,2,"play")}!!
            assertTrue(row.positionUs>=10_000_000)
            assertTrue(row.positionUs>=hold.endUs)
            assertTrue(controller.ui.value.positionUs-row.positionUs<2_500_000)
            handoff.writeText(JSONObject().put("owner",owner).put("pid",Process.myPid()).put("attempt",row.attemptId)
                .put("checkpointUs",row.positionUs).put("liveUs",controller.ui.value.positionUs).put("score",controller.ui.value.score).toString())
            // Keep the native audio session active. The host must terminate this process,
            // rather than close an Activity or recreate its ViewModel.
            Thread.sleep(60000)
            fail("Host did not perform the requested process kill")
        }
    }
    @Test fun afterActualProcessKillResumeWholeRecordingAndSaveFormalResult() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("fullRecoveryPhase")=="after-kill")
        val state=JSONObject(handoff.readText())
        assertNotEquals(state.getInt("pid"),Process.myPid())
        val owner=state.getString("owner")
        val song=PerformanceCatalog.load(context).single{it.id=="greatest-works"}
        val db=LibraryDatabase.open(context)
        val saved=runBlocking(Dispatchers.IO){db.performance().checkpoint(owner,song.id,song.version,2,"play")}!!
        assertEquals(state.getString("attempt"),saved.attemptId)
        assertEquals(state.getLong("checkpointUs"),saved.positionUs)
        try {ActivityScenario.launch(MainActivity::class.java).use{activity->
            await{(context.applicationContext as PilgrimageApplication).services.catalog.value.places.isNotEmpty()}
            attach(activity,owner,song)
            assertEquals(saved.positionUs,controller.ui.value.positionUs)
            assertEquals(state.getInt("score"),controller.ui.value.score)
            capture("full-song-restored-countdown")
            await{controller.ui.value.countIn==0&&controller.ui.value.positionUs>saved.positionUs+50_000}
            val beganAt=SystemClock.elapsedRealtime()
            val sourceAt=controller.ui.value.positionUs
            await(310000){controller.ui.value.state==PlayState.FINISHED&&controller.ui.value.saved}
            val elapsed=SystemClock.elapsedRealtime()-beganAt
            assertTrue("整曲必须按原速播放到底",kotlin.math.abs(elapsed-(song.durationUs-sourceAt)/1000)<3500)
            assertEquals(song.durationUs,controller.ui.value.positionUs)
            assertTrue(controller.ui.value.judgments.all{it !in listOf(Judgment.WAITING,Judgment.HOLDING)})
            compose.onNodeWithTag("performance-result").assertIsDisplayed()
            compose.onNodeWithText("新纪录").assertIsDisplayed()
            compose.onNodeWithText("体验短样",substring=true).assertDoesNotExist()
            val results=runBlocking(Dispatchers.IO){db.performance().results(owner).first()}
            assertEquals(1,results.size)
            assertEquals(2,results.single().difficulty)
            assertEquals(controller.ui.value.score,results.single().score)
            assertTrue(results.single().score>0)
            assertNull(runBlocking(Dispatchers.IO){db.performance().checkpoint(owner,song.id,song.version,2,"play")})
            capture("full-song-formal-result")
            File(context.getExternalFilesDir(null),"full-performance-recovery-result.json").writeText(JSONObject()
                .put("oldPid",state.getInt("pid")).put("newPid",Process.myPid()).put("restoredUs",saved.positionUs)
                .put("durationUs",song.durationUs).put("difficulty",2).put("targetCount",song.standard.size)
                .put("score",results.single().score).put("elapsedAfterResumeMs",elapsed)
                .put("formalResultRows",results.size).put("passed",true).toString())
        }} finally {
            runBlocking(Dispatchers.IO){db.openHelper.writableDatabase.apply {
                execSQL("delete from performance_checkpoints where owner=?",arrayOf(owner))
                execSQL("delete from performance_runs where owner=?",arrayOf(owner))
                execSQL("delete from performance_results where owner=?",arrayOf(owner))
            }}
            context.getSharedPreferences("music-performance-ui",0).edit().remove("$owner:difficulty").commit()
        }
    }
}
