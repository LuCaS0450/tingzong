package cn.musicpilgrimage.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.view.TextureView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.view.descendants
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.musicpilgrimage.app.ui.room.NativeWorldController
import cn.musicpilgrimage.app.ui.room.WorldTestRegistry
import com.mapbox.maps.CameraState
import com.mapbox.maps.RenderedQueryGeometry
import com.mapbox.maps.RenderedQueryOptions
import com.mapbox.maps.ScreenBox
import com.mapbox.maps.ScreenCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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
        val controller = openWorld()
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

    @Test
    fun leavingWorldForOtherTabsRestoresPaintedGlobeAndStreetAtTheSameCamera() {
        var controller = openWorld()
        val output = File(instrumentation.targetContext.filesDir, "world-tab-review").apply { mkdirs() }
        val globe = compose.runOnUiThread {
            controller.jumpTo(19.0, 42.0, 1.15)
            controller.diagnostics()
        }
        captureInitialGlobe(controller, output, "globe-before")
        val globeTexture = mapTexture(controller)
        for (tab in listOf("wall-tab-0", "wall-tab-2")) {
            leaveWorld(tab)
            controller = openWorld()
            captureInitialGlobe(controller, output, "globe-after-$tab")
            assertSame("Returning from $tab recreated the globe drawing surface", globeTexture, mapTexture(controller))
            val restored = compose.runOnUiThread { controller.diagnostics() }
            assertEquals(globe.longitude, restored.longitude, 0.01)
            assertEquals(globe.latitude, restored.latitude, 0.01)
            assertEquals(globe.zoom, restored.zoom, 0.005)
        }

        val street = compose.runOnUiThread {
            controller.jumpTo(52.3731, 4.89, 15.0)
            controller.diagnostics()
        }
        captureStreet(controller, output, "street-before")
        val streetTexture = mapTexture(controller)
        leaveWorld("wall-tab-2")
        controller = openWorld()
        captureStreet(controller, output, "street-after")
        assertSame("Returning to the street map recreated its drawing surface", streetTexture, mapTexture(controller))
        val restored = compose.runOnUiThread { controller.diagnostics() }
        assertEquals(street.longitude, restored.longitude, 0.01)
        assertEquals(street.latitude, restored.latitude, 0.01)
        assertEquals(street.zoom, restored.zoom, 0.005)
    }

    @Test
    fun returningFromPlaceDetailKeepsTheGlobeCameraAndSummary() {
        val controller = openWorld()
        val map = controller.mapView.mapboxMap
        compose.onNodeWithTag("world-place-list").performClick()
        compose.onNodeWithTag("world-place-query").performTextInput("府中竞马")
        compose.onNodeWithTag("world-place-option-fuchukeiba-seimon-mae-station").performClick()
        compose.waitUntil(8_000) {
            compose.runOnUiThread {
                controller.diagnostics().let { it.summaryOpen && it.zoom >= 10.9 && !it.moving }
            }
        }
        val summary = compose.onNodeWithTag("world-summary-title")
        val selectedTitle = summary.fetchSemanticsNode().config[SemanticsProperties.Text].single().text
        val openDetail = requireNotNull(compose.onNodeWithTag("world-summary-detail")
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        val before = compose.runOnUiThread {
            controller.jumpTo(29.0, 122.0, 2.2)
            map.cameraState
        }
        assertTrue("The summary must have a measured camera inset", before.padding.bottom > 0.0)
        val changed = mutableListOf<CameraState>()
        val subscription = compose.runOnUiThread { map.subscribeCameraChanged { changed.add(it.cameraState) } }
        try {
            compose.runOnUiThread {
                controller.zoomTo(3.5, durationMs = 600)
                assertTrue(controller.hasPendingCameraTarget())
                assertTrue(openDetail())
                assertFalse(controller.hasPendingCameraTarget())
            }
            compose.onNodeWithTag("room-place-detail").assertIsDisplayed()
            compose.waitUntil(5_000) { WorldTestRegistry.activeController == null }
            Espresso.pressBack()
            compose.onNodeWithTag("world-place-summary").assertIsDisplayed()
            summary.assertTextEquals(selectedTitle)
            // Include the full window in which summary measurement can restart camera padding.
            SystemClock.sleep(350)
            val frames = compose.runOnUiThread { changed.toList() + map.cameraState }
            frames.forEachIndexed { index, frame ->
                assertEquals("Camera longitude at frame $index", before.center.longitude(), frame.center.longitude(), .00001)
                assertEquals("Camera latitude at frame $index", before.center.latitude(), frame.center.latitude(), .00001)
                assertEquals("Camera zoom at frame $index", before.zoom, frame.zoom, .00001)
                assertEquals("Camera direction at frame $index", before.bearing, frame.bearing, .00001)
                assertEquals("Camera tilt at frame $index", before.pitch, frame.pitch, .00001)
                assertEquals("Camera padding at frame $index", before.padding.bottom, frame.padding.bottom, .5)
            }
        } finally {
            compose.runOnUiThread { subscription.cancel() }
        }
    }

    private fun mapTexture(controller: NativeWorldController): SurfaceTexture = compose.runOnUiThread {
        requireNotNull(controller.mapView.descendants.filterIsInstance<TextureView>().single().surfaceTexture)
    }

    private fun leaveWorld(tab: String) {
        compose.onNodeWithTag(tab).performClick()
        compose.waitUntil(8_000) {
            WorldTestRegistry.activeController == null &&
                compose.onAllNodes(hasTestTag("world-wall")).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun openWorld(): NativeWorldController {
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasTestTag("wall-tab-1")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("wall-tab-1").performClick()
        compose.waitUntil(20_000) {
            compose.runOnUiThread {
                WorldTestRegistry.activeController?.diagnostics()?.let { it.hasVisual && it.visiblePinsCount > 0 } == true
            }
        }
        return requireNotNull(WorldTestRegistry.activeController)
    }

    private fun captureInitialGlobe(controller: NativeWorldController, output: File, name: String = "globe") {
        val location = IntArray(2)
        val (centerX, centerY, sampleOffset) = compose.runOnUiThread {
            val view = controller.mapView
            view.getLocationOnScreen(location)
            val camera = view.mapboxMap.cameraState
            Triple(location[0] + view.width / 2, location[1] + ((view.height - camera.padding.bottom) / 2).toInt(), view.width / 7)
        }
        capturePainted(output, name) { bitmap ->
            listOf(-1, 1).all { vertical ->
                (-1..1).any { horizontal ->
                    val pixel = bitmap.getPixel(centerX + horizontal * sampleOffset, centerY + vertical * sampleOffset)
                    val channels = listOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                    channels.max() - channels.min() >= 30
                }
            }
        }
    }

    private fun captureStreet(controller: NativeWorldController, output: File, name: String) {
        compose.waitUntil(20_000) { streetSegments(controller).let { it.size == 4 && it.all { count -> count > 0 } } }
        val location = IntArray(2)
        val (width, height) = compose.runOnUiThread {
            val view = controller.mapView
            view.getLocationOnScreen(location)
            view.width to view.height
        }
        capturePainted(output, name) { bitmap ->
            // This view has Amsterdam's canals, rendered pale blue by the street style.
            (height / 8 until height * 5 / 8 step 14).any { y ->
                (width / 8 until width * 7 / 8 step 14).any { x ->
                    val color = bitmap.getPixel(location[0] + x, location[1] + y)
                    Color.red(color) in 170..201 && Color.green(color) in 200..225 && Color.blue(color) in 207..232
                }
            }
        }
    }

    private fun capturePainted(output: File, name: String, isPainted: (Bitmap) -> Boolean) {
        compose.waitUntil(10_000) {
            val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return@waitUntil false
            try {
                val painted = isPainted(bitmap)
                if (painted) save(bitmap, output, name)
                painted
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
