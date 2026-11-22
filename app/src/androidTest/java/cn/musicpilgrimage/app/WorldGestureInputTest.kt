package cn.musicpilgrimage.app

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.musicpilgrimage.app.ui.room.NativeWorldController
import cn.musicpilgrimage.app.ui.room.WorldDiagnosticsSnapshot
import cn.musicpilgrimage.app.ui.room.WorldMapMath
import cn.musicpilgrimage.app.ui.room.WorldTestRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.hypot

/** Physical Android multi-pointer input against the native globe and street map. */
@RunWith(AndroidJUnit4::class)
@ExtendedDeviceTest
class WorldGestureInputTest {
    @get:Rule
    val compose = createAndroidComposeRule<MusicSpacePreviewActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun controller(): NativeWorldController =
        checkNotNull(WorldTestRegistry.activeController) { "NativeWorldController was not active" }

    private fun diag(ctrl: NativeWorldController): WorldDiagnosticsSnapshot {
        lateinit var snap: WorldDiagnosticsSnapshot
        instrumentation.runOnMainSync { snap = ctrl.diagnostics() }
        return snap
    }

    private fun idle(ctrl: NativeWorldController): WorldDiagnosticsSnapshot {
        SystemClock.sleep(180)
        var d = diag(ctrl)
        repeat(80) {
            if (!d.moving) return d
            SystemClock.sleep(30)
            d = diag(ctrl)
        }
        error("World camera did not settle")
    }

    private fun event(down: Long, action: Int, points: List<Pair<Float, Float>>, wait: Boolean = true) {
        val props = Array(points.size) { i ->
            MotionEvent.PointerProperties().apply {
                id = i
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(points.size) { i ->
            MotionEvent.PointerCoords().apply {
                x = points[i].first
                y = points[i].second
                pressure = 1f
                size = 0.08f
            }
        }
        val e = MotionEvent.obtain(
            down,
            SystemClock.uptimeMillis(),
            action,
            points.size,
            props,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        )
        check(instrumentation.uiAutomation.injectInputEvent(e, wait)) { "Touch injection failed" }
        e.recycle()
    }

    private fun pinch(x: Float, y: Float, from: Float, to: Float, hold: Boolean = true) {
        val down = SystemClock.uptimeMillis()
        event(down, MotionEvent.ACTION_DOWN, listOf(x - from to y))
        event(
            down,
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            listOf(x - from to y, x + from to y),
        )
        for (i in 1..24) {
            val half = from + (to - from) * i / 24
            event(
                down,
                MotionEvent.ACTION_MOVE,
                listOf(x - half to y, x + half to y),
                wait = hold || i < 21,
            )
            SystemClock.sleep(16)
        }
        if (hold) repeat(5) {
            event(down, MotionEvent.ACTION_MOVE, listOf(x - to to y, x + to to y))
            SystemClock.sleep(20)
        }
        event(
            down,
            MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            listOf(x - to to y, x + to to y),
            wait = hold,
        )
        event(down, MotionEvent.ACTION_UP, listOf(x - to to y), wait = hold)
    }

    private fun panStopsOnRelease(ctrl: NativeWorldController, x: Float, y: Float): JSONObject {
        val down = SystemClock.uptimeMillis()
        event(down, MotionEvent.ACTION_DOWN, listOf(x to y))
        for (i in 1..12) {
            event(down, MotionEvent.ACTION_MOVE, listOf(x + 100f * i / 12f to y + 20f * i / 12f))
            SystemClock.sleep(12)
        }
        event(down, MotionEvent.ACTION_UP, listOf(x + 100f to y + 20f))
        SystemClock.sleep(80)
        val atRelease = diag(ctrl)
        SystemClock.sleep(300)
        val later = diag(ctrl)
        assertFalse("Panning must stop immediately after release", atRelease.moving)
        assertEquals("Pan latitude must not drift after release", atRelease.latitude, later.latitude, 0.05)
        assertEquals("Pan longitude must not drift after release", atRelease.longitude, later.longitude, 0.05)
        return JSONObject()
            .put("atReleaseLat", atRelease.latitude)
            .put("atReleaseLon", atRelease.longitude)
            .put("laterLat", later.latitude)
            .put("laterLon", later.longitude)
    }

    @Test
    fun disconnectedStreetOffersRetryAndReturnToGlobe() {
        compose.waitUntil(25_000) {
            compose.onAllNodes(hasTestTag("wall-tab-1")).fetchSemanticsNodes().isNotEmpty()
        }
        val wifiEnabled = shell("settings get global wifi_on") == "1"
        val dataEnabled = shell("settings get global mobile_data") == "1"
        try {
            shell("svc wifi disable")
            shell("svc data disable")
            compose.onNodeWithTag("wall-tab-1").performClick()
            compose.waitUntil(15_000) { WorldTestRegistry.activeController?.hasVisual == true }
            val ctrl = controller()
            compose.runOnUiThread { ctrl.jumpTo(4.2917, -111.377, 16.0) }
            compose.waitUntil(12_000) {
                compose.onAllNodes(hasTestTag("world-street-offline")).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("world-street-offline").assertIsDisplayed()
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            File(instrumentation.targetContext.filesDir, "world-street-offline.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
            compose.onNodeWithTag("world-street-offline-retry").performClick()
            compose.onNodeWithTag("world-street-offline").assertIsDisplayed()
            compose.onNodeWithTag("world-mini-globe").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasTestTag("world-street-offline")).fetchSemanticsNodes().isEmpty()
            }
        } finally {
            if (dataEnabled) shell("svc data enable")
            if (wifiEnabled) shell("svc wifi enable")
        }
    }

    private fun angularDistance(a: Pair<Double, Double>, b: Pair<Double, Double>) =
        hypot(a.first - b.first, WorldMapMath.wrapLongitude(a.second - b.second))

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText().trim() }

    @Test
    fun zoomRailKeepsItsScreenPositionAcrossLevelLabels() {
        compose.waitUntil(25_000) {
            compose.onAllNodes(hasTestTag("wall-tab-1")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("wall-tab-1").performClick()
        compose.waitUntil(15_000) { WorldTestRegistry.activeController?.let { diag(it).hasVisual } == true }
        val ctrl = controller()
        val zoomIn = compose.onNodeWithTag("world-zoom-in")
        val initialX = zoomIn.fetchSemanticsNode().boundsInRoot.center.x
        for (tag in listOf("world-zoom-region", "world-zoom-street", "world-zoom-globe")) {
            compose.onNodeWithTag(tag).performClick()
            val deadline = SystemClock.uptimeMillis() + 3_000
            do {
                assertEquals("Zoom rail moved at $tag", initialX, zoomIn.fetchSemanticsNode().boundsInRoot.center.x, 0.5f)
            } while (diag(ctrl).moving && SystemClock.uptimeMillis() < deadline)
            assertFalse("Camera did not settle at $tag", diag(ctrl).moving)
        }
    }

    @Test
    fun physicalPinchLocksFocusAndReverseKeepsTheBrowseState() {
        compose.waitUntil(25_000) {
            compose.onAllNodes(hasTestTag("wall-tab-1")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("wall-tab-1").performClick()

        compose.waitUntil(15_000) {
            var ready = false
            instrumentation.runOnMainSync {
                val d = WorldTestRegistry.activeController?.diagnostics()
                ready = d != null && d.ready && d.hasVisual && d.placesCount > 0
            }
            ready
        }
        val ctrl = controller()

        compose.onNodeWithTag("world-place-list").performClick()
        compose.onNodeWithTag("world-place-query").performTextInput("蓝色列车餐厅")
        compose.onNode(hasText("蓝色列车餐厅") and !hasTestTag("world-place-query")).performClick()
        idle(ctrl)
        compose.onNodeWithTag("world-summary-close").performClick()

        compose.onNodeWithTag("world-zoom-globe").performClick()
        idle(ctrl)

        val location = IntArray(2)
        var viewW = 0
        var viewH = 0
        instrumentation.runOnMainSync {
            ctrl.mapView.getLocationOnScreen(location)
            viewW = ctrl.mapView.width
            viewH = ctrl.mapView.height
        }
        val localX = viewW / 2f
        val localY = viewH * 0.55f
        val screenX = location[0] + localX
        val screenY = location[1] + localY

        lateinit var grabbed: Pair<Double, Double>
        instrumentation.runOnMainSync { grabbed = requireNotNull(ctrl.screenToLatLng(localX, localY)) }

        val down = SystemClock.uptimeMillis()
        event(down, MotionEvent.ACTION_DOWN, listOf(screenX to screenY))
        for (i in 1..40) {
            event(down, MotionEvent.ACTION_MOVE, listOf(screenX + 80f * i / 40f to screenY + 12f * i / 40f))
            SystemClock.sleep(16)
        }
        repeat(5) {
            event(down, MotionEvent.ACTION_MOVE, listOf(screenX + 80f to screenY + 12f))
            SystemClock.sleep(20)
        }
        lateinit var underFinger: Pair<Double, Double>
        instrumentation.runOnMainSync {
            underFinger = requireNotNull(ctrl.screenToLatLng(localX + 80f, localY + 12f))
        }
        val dragDrift = angularDistance(grabbed, underFinger)
        event(down, MotionEvent.ACTION_UP, listOf(screenX + 80f to screenY + 12f))
        idle(ctrl)
        assertTrue("The grabbed coordinate must track the finger: $dragDrift degrees", dragDrift < 6.0)

        val before = diag(ctrl)
        assertTrue("Globe control must be operational before gestures", before.zoom < 2.0)
        lateinit var anchor: Pair<Double, Double>
        instrumentation.runOnMainSync { anchor = requireNotNull(ctrl.screenToLatLng(localX, localY)) }

        pinch(screenX, screenY, 45f, 165f)
        val after = idle(ctrl)
        lateinit var afterAnchor: Pair<Double, Double>
        instrumentation.runOnMainSync { afterAnchor = requireNotNull(ctrl.screenToLatLng(localX, localY)) }
        val drift = angularDistance(anchor, afterAnchor)
        val zoomDelta = after.zoom - before.zoom
        assertTrue("Real multi-touch must zoom: delta=$zoomDelta", zoomDelta > 0.6)
        assertTrue("Pinch focus drift: $drift degrees", drift < 7.0)

        var pinches = 1
        var street = after
        repeat(10) {
            if (street.zoom < 14.5) {
                pinch(screenX, screenY, 45f, 165f)
                street = idle(ctrl)
                pinches++
            }
        }
        assertTrue("Continuous pinch must reach street zoom: ${street.zoom}", street.zoom >= 14.5)

        repeat(pinches) {
            pinch(screenX, screenY, 165f, 45f)
            idle(ctrl)
        }
        val reverse = diag(ctrl)
        assertTrue("Inverse pinch must return toward globe: ${reverse.zoom}", reverse.zoom < 2.2)
        assertEquals(before.selected, reverse.selected)

        val record = JSONObject()
            .put("dragFocusDriftDegrees", dragDrift)
            .put("pinchFocusDriftDegrees", drift)
            .put("zoomDelta", zoomDelta)
            .put("globePanStop", panStopsOnRelease(ctrl, screenX, screenY))

        compose.onNodeWithTag("world-zoom-street").performClick()
        idle(ctrl)
        record.put("streetPanStop", panStopsOnRelease(ctrl, screenX, screenY))
        File(instrumentation.targetContext.filesDir, "world-gesture-review.json").writeText(record.toString(2))
    }
}
