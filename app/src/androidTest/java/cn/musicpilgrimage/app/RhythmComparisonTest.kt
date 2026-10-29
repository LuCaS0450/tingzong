package cn.musicpilgrimage.app

import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.geometry.Offset
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cn.musicpilgrimage.app.performance.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Local original audio is supplied outside the APK by the developer/private user. */
class RhythmComparisonTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val owner="preview:rhythm-comparison"
    private fun cleanRuns()=runBlocking(Dispatchers.IO) {
        LibraryDatabase.open(context).openHelper.writableDatabase.apply {
            execSQL("delete from performance_checkpoints where owner=?",arrayOf(owner))
            execSQL("delete from performance_runs where owner=?",arrayOf(owner))
            execSQL("delete from performance_results where owner=?",arrayOf(owner))
        }
    }
    @Before fun prepare() {
        compose.mainClock.autoAdvance=false
        cleanRuns()
        val bundle=File(context.getExternalFilesDir(null),"comparison-local.zip")
        if(bundle.isFile)bundle.inputStream().use{ComparisonBundle.import(context,it)}
        else requireNotNull(ComparisonBundle.current(context)){"请先通过独立试玩入口准备本地短样，不要把原唱放入测试 APK"}
    }
    @After fun clean(){cleanRuns()}
    private fun await(condition:()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+45000
        while(!condition() && SystemClock.elapsedRealtime()<deadline){compose.mainClock.advanceTimeByFrame();Thread.sleep(10)}
        assertTrue("节奏体验未到达预期状态",condition())
        compose.mainClock.advanceTimeBy(32);compose.waitForIdle()
    }
    private fun capture(name:String) {
        File(context.getExternalFilesDir(null),name).outputStream().use {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
        }
    }
    @Test fun failedUnsafeImportKeepsTheExistingLocalMusic() {
        val previous=ComparisonBundle.current(context)!!
        val bytes=ByteArrayOutputStream().also{out->ZipOutputStream(out).use{zip->
            zip.putNextEntry(ZipEntry("../escape.pcmz"));zip.write(byteArrayOf(1));zip.closeEntry()
        }}.toByteArray()
        val error=assertThrows(Exception::class.java){ComparisonBundle.import(context,bytes.inputStream())}
        // New Android versions reject traversal before our whitelist is even reached.
        assertTrue(error is IllegalArgumentException || error is java.util.zip.ZipException)
        assertEquals(previous,ComparisonBundle.current(context));assertTrue(File(previous,"original.pcmz").isFile)
        assertFalse(File(context.filesDir,"escape.pcmz").exists())
    }
    @Test fun originalSpeedTapHoldDriftPauseReleaseAndCompletion() {
        lateinit var controller:PerformanceController
        ActivityScenario.launch(RhythmComparisonActivity::class.java).use {scenario->
            compose.mainClock.advanceTimeBy(100);compose.waitForIdle()
            compose.onNodeWithTag("comparison-speed-0.9").assertDoesNotExist()
            compose.onNodeWithTag("performance-difficulty-1").assertDoesNotExist()
            compose.onNodeWithTag("performance-difficulty-2").assertDoesNotExist()
            compose.mainClock.advanceTimeBy(400);compose.waitForIdle()
            capture("comparison-entry.png")
            compose.onNodeWithTag("performance-enter").performScrollTo().performClick()
            await{var ready=false;scenario.onActivity{host->host.activeController?.let{controller=it;ready=controller.ui.value.state in listOf(PlayState.READY,PlayState.STARTING,PlayState.PLAYING)}};ready}
            assertEquals(1.0,controller.options.speed,0.0)
            assertEquals(2,controller.options.difficulty)
            assertEquals(26,controller.targets.size)
            assertEquals(4,controller.targets.count{it.hold})
            val easy=controller.song.targets(1)
            assertTrue(easy.size<controller.targets.size)
            assertTrue(easy.all{a->controller.targets.any{b->a.onsetUs==b.onsetUs && a.lane==b.lane && a.durationUs==b.durationUs}})
            val first=controller.targets.first()
            await{controller.ui.value.countIn==0 && controller.ui.value.positionUs>=first.onsetUs-60_000}
            compose.onNodeWithTag("performance-lane-${first.lane}").performTouchInput{click(center)}
            assertTrue(controller.ui.value.judgments[0] in listOf(Judgment.PERFECT,Judgment.GOOD))
            compose.mainClock.advanceTimeByFrame();compose.waitForIdle()
            capture("comparison-gameplay.png")
            val second=controller.targets[1]
            await{controller.ui.value.positionUs>second.onsetUs+220_000}
            assertEquals(Judgment.MISSED,controller.ui.value.judgments[1]);assertEquals(PlayState.PLAYING,controller.ui.value.state)
            val hold=controller.targets.first{it.hold}
            await{controller.ui.value.positionUs>=hold.onsetUs-50_000}
            val lane=compose.onNodeWithTag("performance-lane-${hold.lane}")
            lane.performTouchInput{down(center)}
            assertEquals(Judgment.HOLDING,controller.ui.value.judgments[hold.id])
            await{controller.ui.value.positionUs>=hold.onsetUs+350_000}
            lane.performTouchInput{moveTo(center+Offset(16f,-12f))}
            assertEquals(Judgment.HOLDING,controller.ui.value.judgments[hold.id])
            compose.onNodeWithTag("performance-hold-${hold.lane}").assertIsDisplayed()
            capture("comparison-hold.png")
            // Finger 0 is still holding the lane. A second finger presses Pause.
            compose.onNodeWithTag("performance-pause").performTouchInput{down(1,center);up(1)}
            lane.performTouchInput{up()}
            await{controller.ui.value.state==PlayState.PAUSED && controller.ui.value.saved}
            // The current interruption rule rewinds an unfinished hold for replay.
            assertEquals(Judgment.WAITING,controller.ui.value.judgments[hold.id])
            val saved=controller.ui.value.positionUs;assertTrue(saved<hold.onsetUs)
            Thread.sleep(150);assertEquals(saved,controller.ui.value.positionUs)
            compose.onNodeWithTag("performance-start").performClick()
            await{controller.ui.value.countIn==0 && controller.ui.value.positionUs>=hold.onsetUs-50_000}
            lane.performTouchInput{down(center)}
            assertEquals(Judgment.HOLDING,controller.ui.value.judgments[hold.id])
            await{controller.ui.value.countIn==0 && controller.ui.value.positionUs>=hold.endUs+20_000}
            assertTrue(controller.ui.value.judgments[hold.id] in listOf(Judgment.PERFECT,Judgment.GOOD))
            assertTrue(controller.ui.value.feedbacks.any{it.kind==FeedbackKind.HOLD_COMPLETE})
            lane.performTouchInput{up()}
            val nextHold=controller.targets.filter{it.hold}[1]
            await{controller.ui.value.positionUs>=nextHold.onsetUs-50_000}
            val nextLane=compose.onNodeWithTag("performance-lane-${nextHold.lane}")
            nextLane.performTouchInput{down(center)}
            assertEquals(Judgment.HOLDING,controller.ui.value.judgments[nextHold.id])
            await{controller.ui.value.positionUs>=nextHold.onsetUs+250_000}
            nextLane.performTouchInput{up()}
            assertEquals(Judgment.PARTIAL,controller.ui.value.judgments[nextHold.id])
            assertTrue(controller.ui.value.feedbacks.any{it.kind==FeedbackKind.HOLD_BREAK})
            await{controller.ui.value.state==PlayState.FINISHED && controller.ui.value.saved}
            compose.onNodeWithTag("performance-result").assertIsDisplayed()
            assertTrue(controller.ui.value.score>0)
            capture("comparison-result.png")
            assertTrue(runBlocking{LibraryDatabase.open(context).performance().results(owner).first()}.isEmpty())
        }
    }
}
