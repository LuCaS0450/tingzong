package cn.musicpilgrimage.app

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.musicpilgrimage.app.ui.room.NativeWorldController
import cn.musicpilgrimage.app.ui.room.WorldTestRegistry
import com.mapbox.maps.RenderedQueryGeometry
import com.mapbox.maps.RenderedQueryOptions
import com.mapbox.maps.ScreenBox
import com.mapbox.maps.ScreenCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@ExtendedDeviceTest
class WorldRenderingDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MusicSpacePreviewActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun globeAndLuoyangRenderWithoutZoomingAgain() {
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasTestTag("wall-tab-1")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("wall-tab-1").performClick()
        compose.waitUntil(20_000) {
            compose.runOnUiThread {
                WorldTestRegistry.activeController?.diagnostics()?.let { it.hasVisual && it.visiblePinsCount > 0 } == true
            }
        }
        val controller = requireNotNull(WorldTestRegistry.activeController)
        val output = File(instrumentation.targetContext.filesDir, "world-render-review").apply { mkdirs() }
        compose.waitForIdle()
        captureInitialGlobe(controller, output)

        val globeViews = buildList {
            for (latitude in listOf(-42, 0, 42)) {
                for (longitude in listOf(-179, -120, -60, 0, 60, 120, 179)) add(latitude to longitude)
            }
            add(-79 to 0)
            add(79 to 0)
        }
        for ((latitude, longitude) in globeViews) {
            compose.runOnUiThread {
                controller.jumpTo(latitude.toDouble(), longitude.toDouble(), 0.0)
                assertEquals("Globe latitude at ${latitude}, ${longitude}", latitude.toDouble(), controller.diagnostics().latitude, 0.5)
            }
            compose.waitForIdle()
            SystemClock.sleep(330)
            capture(output, "globe_${latitude}_${longitude}")
        }
        compose.runOnUiThread { controller.jumpTo(32.4279, 53.6880, 5.1) }
        compose.waitForIdle()
        SystemClock.sleep(2500)
        capture(output, "iran")

        compose.onNodeWithTag("world-place-list").performClick()
        compose.onNodeWithTag("world-place-query").performTextInput("洛阳")
        compose.onNode(hasText("洛阳") and !hasTestTag("world-place-query")).performClick()
        compose.waitUntil(8_000) {
            compose.runOnUiThread {
                val diagnostics = controller.diagnostics()
                diagnostics.summaryOpen && diagnostics.zoom >= 10.9 && !diagnostics.moving
            }
        }
        val firstStreetFrame = SystemClock.elapsedRealtime()
        var quadrants = emptyList<Int>()
        compose.waitUntil(20_000) {
            quadrants = streetSegments(controller)
            quadrants.size == 4 && quadrants.all { it > 0 }
        }
        println("World street geometry in the four Luoyang quadrants: $quadrants after ${SystemClock.elapsedRealtime() - firstStreetFrame} ms")
        compose.waitUntil(8_000) {
            compose.runOnUiThread {
                val hud = controller.hudState
                !hud.loadingVisible && !hud.progressVisible && !hud.streetOffline
            }
        }
        compose.waitForIdle()
        SystemClock.sleep(450)
        capture(output, "luoyang")
    }

    private fun captureInitialGlobe(controller: NativeWorldController, output: File) {
        val location = IntArray(2)
        val (centerX, centerY, sampleOffset) = compose.runOnUiThread {
            val view = controller.mapView
            view.getLocationOnScreen(location)
            val camera = view.mapboxMap.cameraState
            Triple(location[0] + view.width / 2, location[1] + ((view.height - camera.padding.bottom) / 2).toInt(), view.width / 7)
        }
        compose.waitUntil(8_000) {
            val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return@waitUntil false
            try {
                val bothHemispheresPainted = listOf(-1, 1).all { vertical ->
                    (-1..1).any { horizontal ->
                        val pixel = bitmap.getPixel(centerX + horizontal * sampleOffset, centerY + vertical * sampleOffset)
                        val channels = listOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                        channels.max() - channels.min() >= 30
                    }
                }
                if (bothHemispheresPainted) save(bitmap, output, "globe")
                bothHemispheresPainted
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun streetSegments(controller: NativeWorldController): List<Int> {
        val latch = CountDownLatch(4)
        val counts = IntArray(4)
        compose.runOnUiThread {
            val view = controller.mapView
            val map = view.mapboxMap
            val width = view.width / 2.0
            val height = (view.height - map.cameraState.padding.bottom) / 2.0
            repeat(4) { index ->
                val x = width * (index % 2)
                val y = height * (index / 2)
                map.queryRenderedFeatures(
                    RenderedQueryGeometry(ScreenBox(ScreenCoordinate(x, y), ScreenCoordinate(x + width, y + height))),
                    RenderedQueryOptions(listOf("world-road"), null),
                ) { result ->
                    counts[index] = result.value.orEmpty().size
                    latch.countDown()
                }
            }
        }
        return if (latch.await(2, TimeUnit.SECONDS)) counts.toList() else emptyList()
    }

    private fun capture(output: File, name: String) {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        save(bitmap, output, name)
        bitmap.recycle()
    }

    private fun save(bitmap: Bitmap, output: File, name: String) {
        File(output, "$name.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
