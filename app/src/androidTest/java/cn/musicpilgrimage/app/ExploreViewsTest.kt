package cn.musicpilgrimage.app

import androidx.compose.ui.test.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*

class ExploreViewsTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun viewsAreExclusiveAndMapOpensDetailThenRestoresCamera() {
        Assume.assumeTrue(BuildConfig.APPLICATION_ID.endsWith(".review"))
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val prefs=context.getSharedPreferences("tingzong-explore",0)
        val before=prefs.all
        prefs.edit().clear().putString("view","works").commit()
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitUntil(15000){compose.onAllNodesWithTag("work-grid").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithTag("place-list").assertDoesNotExist()
                compose.onNodeWithTag("explore-map-viewport").assertDoesNotExist()
                compose.onNodeWithTag("explore-list").performClick()
                compose.onNodeWithTag("place-list").assertExists()
                compose.onNodeWithTag("work-grid").assertDoesNotExist()
                compose.onNode(hasSetTextAction()).performTextReplacement("莎玛丽丹")
                compose.onNodeWithTag("explore-map").performClick()
                compose.onNodeWithTag("place-list").assertDoesNotExist()
                compose.onNodeWithTag("work-grid").assertDoesNotExist()
                val marker="map-reference-samaritaine"
                try {
                    compose.waitUntil(45000){compose.onAllNodesWithTag(marker).fetchSemanticsNodes().isNotEmpty()}
                } catch(error:Throwable) {
                    compose.onRoot(useUnmergedTree=true).printToLog("ExploreFailure")
                    java.io.File(context.getExternalFilesDir(null),"explore-test-failure.png").outputStream().use {
                        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
                    }
                    throw error
                }
                val initial=compose.onNodeWithTag(marker).fetchSemanticsNode().boundsInRoot
                // Native MapView needs real Android input times, rather than Compose's virtual clock.
                val viewport=compose.onNodeWithTag("explore-map-viewport").fetchSemanticsNode().boundsInWindow
                val start=Offset(viewport.left+viewport.width*.25f,viewport.top+viewport.height*.75f)
                val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
                val downTime=android.os.SystemClock.uptimeMillis()
                fun input(action:Int,x:Float,y:Float) {
                    val event=android.view.MotionEvent.obtain(downTime,android.os.SystemClock.uptimeMillis(),action,x,y,0)
                    event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
                    try {assertTrue(automation.injectInputEvent(event,true))} finally {event.recycle()}
                }
                input(android.view.MotionEvent.ACTION_DOWN,start.x,start.y)
                for(step in 1..12){Thread.sleep(35);input(android.view.MotionEvent.ACTION_MOVE,start.x+step*8f,start.y+step*6f)}
                input(android.view.MotionEvent.ACTION_UP,start.x+96,start.y+72)
                compose.waitUntil(5000){
                    val point=compose.onNodeWithTag(marker).fetchSemanticsNode().boundsInRoot.center
                    kotlin.math.abs(point.x-initial.center.x)>20f
                }
                var previous=compose.onNodeWithTag(marker).fetchSemanticsNode().boundsInRoot.center
                var stableSince=android.os.SystemClock.elapsedRealtime()
                compose.waitUntil(5000){
                    val point=compose.onNodeWithTag(marker).fetchSemanticsNode().boundsInRoot.center
                    val now=android.os.SystemClock.elapsedRealtime()
                    if((point-previous).getDistance()>1f)stableSince=now
                    previous=point
                    now-stableSince>=250
                }
                compose.waitForIdle()
                val first=compose.onNodeWithTag(marker).fetchSemanticsNode().boundsInRoot
                compose.onNodeWithTag(marker).performClick()
                compose.waitUntil(15000){compose.onAllNodesWithContentDescription("收藏地点").fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithContentDescription("取消收藏").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithTag("explore-map-viewport").assertDoesNotExist()
                pressBack()
                compose.waitUntil(45000){compose.onAllNodesWithTag(marker).fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithTag("place-list").assertDoesNotExist()
                val after=compose.onNodeWithTag(marker).fetchSemanticsNode().boundsInRoot
                assertEquals(first.center.x,after.center.x,8f)
                assertEquals(first.center.y,after.center.y,8f)
            }
        } finally {
            val edit=prefs.edit().clear()
            before.forEach{(k,v)->when(v){is String->edit.putString(k,v);is Boolean->edit.putBoolean(k,v);is Int->edit.putInt(k,v)}}
            edit.commit()
        }
    }
}
