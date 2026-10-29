package cn.musicpilgrimage.app

import android.graphics.Bitmap
import android.graphics.PointF
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import cn.musicpilgrimage.app.data.catalogFromJson
import cn.musicpilgrimage.app.data.Place
import cn.musicpilgrimage.app.data.primaryScene
import cn.musicpilgrimage.app.ui.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Checks the real projection, gestures and clicks, rather than a synthetic map image. */
class MapReferenceCardTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun findMap(view: View): MapView? {
        if (view is MapView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findMap(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun liveCatalog() = run {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        assumeTrue(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("mapReferenceLive") == "true")
        catalogFromJson(JSONObject(instrumentation.targetContext.assets.open("catalog.json").bufferedReader().use { it.readText() }))
    }

    private fun showMap(places: List<Place>, title: String, clicked: AtomicReference<String?>): Pair<MapView, MapLibreMap> {
        compose.activityRule.scenario.onActivity { activity -> activity.setContent { PilgrimageTheme { Surface(Modifier.fillMaxSize()) {
            Column(Modifier.systemBarsPadding().padding(20.dp)) {
                PageTitle(title, "实际地图 · 参考卡片交互验证")
                MapPanel(places, BuildConfig.MAPTILER_KEY, { clicked.set(it.id) }, Modifier.fillMaxWidth().height(420.dp).testTag("map-host"))
            }
        } } } }
        compose.waitForIdle()
        val view = AtomicReference<MapView?>()
        val map = AtomicReference<MapLibreMap?>()
        compose.waitUntil(60000) {
            var settled = false
            compose.activityRule.scenario.onActivity { activity ->
                findMap(activity.window.decorView)?.let { found -> view.set(found); found.getMapAsync { map.set(it) } }
                settled = map.get()?.cameraPosition?.zoom?.let { it > 1 } == true
            }
            settled
        }
        return requireNotNull(view.get()) to requireNotNull(map.get())
    }

    private fun awaitImage(id: String) = compose.waitUntil(90000) {
        compose.onAllNodesWithTag("map-reference-$id").fetchSemanticsNodes().any {
            it.config.getOrElse(SemanticsProperties.StateDescription) { "" } == "图片已加载"
        }
    }

    private fun capture(name: String) {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync(); Thread.sleep(700)
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(folder, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }

    @Test fun farViewUsesSmallMarkerAndEdgesClipTheUnmovedCover() {
        val havana = liveCatalog().places.first { it.id == "malecon" }
        val clicked = AtomicReference<String?>()
        val (view, map) = showMap(listOf(havana), "地图边缘裁切", clicked)
        val coordinate = LatLng(havana.latitude, havana.longitude)
        awaitImage(havana.id)
        compose.activityRule.scenario.onActivity { map.moveCamera(CameraUpdateFactory.newLatLngZoom(coordinate, 6.0)) }
        compose.waitUntil(30000) { compose.onAllNodesWithTag("map-reference-marker-malecon").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("map-reference-malecon").assertDoesNotExist()
        capture("14-map-reference-far.png")

        fun positionPointAt(x: Float, y: Float) {
            compose.activityRule.scenario.onActivity {
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(coordinate, 13.0))
                repeat(2) {
                    val projected = map.projection.toScreenLocation(coordinate)
                    val cameraCenter = map.projection.toScreenLocation(requireNotNull(map.cameraPosition.target))
                    val center = map.projection.fromScreenLocation(PointF(cameraCenter.x + projected.x - x, cameraCenter.y + projected.y - y))
                    map.moveCamera(CameraUpdateFactory.newLatLng(center))
                }
            }
            awaitImage(havana.id)
            compose.waitForIdle()
            val node = compose.onNodeWithTag("map-reference-malecon").fetchSemanticsNode()
            val host = compose.onNodeWithTag("map-host").fetchSemanticsNode().boundsInRoot
            val tip = node.config[ReferenceTipPosition]
            compose.activityRule.scenario.onActivity {
                val projected = map.projection.toScreenLocation(coordinate)
                assertEquals(x, projected.x, 2f); assertEquals(y, projected.y, 2f)
                assertEquals(projected.x, node.positionInRoot.x + tip.x - host.left, 1f)
                assertEquals(projected.y, node.positionInRoot.y + tip.y - host.top, 1f)
            }
            assertTrue(node.boundsInRoot.left >= host.left - 1f)
            assertTrue(node.boundsInRoot.right <= host.right + 1f)
        }
        positionPointAt(4f, view.height / 2f)
        capture("15-map-reference-edge-left.png")
        compose.onNodeWithTag("map-reference-malecon").assertIsDisplayed().performTouchInput { click(center) }
        assertEquals(havana.id, clicked.get())
        positionPointAt(view.width - 4f, view.height / 2f)
        capture("16-map-reference-edge-right.png")
        positionPointAt(view.width / 2f, 100f)
        capture("17-map-reference-edge-top.png")
    }

    @Test fun clusterZoomsApartAndCoincidentCoordinatesOpenASelectableList() {
        val catalog = liveCatalog()
        val havana = catalog.places.first { it.id == "malecon" }
        // Only UI fixtures move a second card; the actual catalog remains unchanged.
        val street = catalog.places.first { it.id == "lee-tung-street" }.copy(latitude = havana.latitude, longitude = havana.longitude + .002)
        val clicked = AtomicReference<String?>()
        val (_, map) = showMap(listOf(havana, street), "聚合交互验证", clicked)
        compose.activityRule.scenario.onActivity { map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(havana.latitude, havana.longitude + .001), 13.0)) }
        val clusterTag = "map-reference-cluster-lee-tung-street|malecon"
        compose.waitUntil(30000) { compose.onAllNodesWithTag(clusterTag).fetchSemanticsNodes().size == 1 }
        capture("18-map-reference-cluster.png")
        compose.onNodeWithTag(clusterTag).performTouchInput { click(center) }
        compose.waitUntil(30000) { compose.onAllNodesWithTag("map-reference-malecon").fetchSemanticsNodes().size == 1 && compose.onAllNodesWithTag("map-reference-lee-tung-street").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("map-reference-list").assertDoesNotExist()
        awaitImage(havana.id); awaitImage(street.id)
        capture("19-map-reference-cluster-expanded.png")

        val coincident = street.copy(longitude = havana.longitude)
        val (_, coincidentMap) = showMap(listOf(havana, coincident), "重合坐标列表验证", clicked)
        compose.activityRule.scenario.onActivity { coincidentMap.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(havana.latitude, havana.longitude), 13.0)) }
        compose.waitUntil(30000) { compose.onAllNodesWithTag(clusterTag).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag(clusterTag).performTouchInput { click(center) }
        compose.waitUntil(30000) { compose.onAllNodesWithTag("map-reference-list").fetchSemanticsNodes().size == 1 }
        var zoom = 0.0
        compose.activityRule.scenario.onActivity { zoom = coincidentMap.cameraPosition.zoom }
        assertTrue("The group must first zoom toward its coordinates", zoom > 13.5)
        capture("20-map-reference-cluster-list.png")
        compose.onNodeWithText(coincident.name).performClick()
        assertEquals(coincident.id, clicked.get())
        compose.onNodeWithTag("map-reference-list").assertDoesNotExist()
    }

    @Test fun otherCityAndLandmarkKeepTheirOwnAnchors() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        assumeTrue(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("mapReferenceLive") == "true")
        val catalog = catalogFromJson(JSONObject(instrumentation.targetContext.assets.open("catalog.json").bufferedReader().use { it.readText() }))
        val city = catalog.places.first { it.id == "vladivostok" }
        val landmark = catalog.places.first { it.id == "mont-saint-michel" }
        var displayed by mutableStateOf(city)
        val clicked = AtomicReference<String?>()
        compose.activityRule.scenario.onActivity { activity -> activity.setContent { PilgrimageTheme { Surface(Modifier.fillMaxSize()) {
            Column(Modifier.systemBarsPadding().padding(20.dp)) {
                PageTitle("参考卡片定位", displayed.city)
                MapPanel(listOf(displayed), BuildConfig.MAPTILER_KEY, { clicked.set(it.id) }, Modifier.fillMaxWidth().height(420.dp).testTag("map-host"))
            }
        } } } }
        compose.waitForIdle()
        val nativeMap = AtomicReference<MapLibreMap?>()
        compose.waitUntil(30000) {
            compose.activityRule.scenario.onActivity { activity -> findMap(activity.window.decorView)?.getMapAsync { nativeMap.set(it) } }
            var settled = false
            compose.activityRule.scenario.onActivity { settled = nativeMap.get()?.cameraPosition?.zoom?.let { it in 12.99..13.01 } == true }
            settled
        }
        fun awaitImage(id: String) = compose.waitUntil(60000) {
            compose.onAllNodesWithTag("map-reference-$id").fetchSemanticsNodes().any { it.config.getOrElse(SemanticsProperties.StateDescription) { "" } == "图片已加载" }
        }
        awaitImage(city.id)
        compose.onNodeWithTag("map-reference-${city.id}").assertIsDisplayed().performClick()
        assertEquals(city.id, clicked.get())
        compose.runOnIdle { displayed = landmark }
        awaitImage(landmark.id)
        compose.waitUntil(30000) {
            var settled = false
            compose.activityRule.scenario.onActivity { settled = nativeMap.get()?.cameraPosition?.target?.distanceTo(LatLng(landmark.latitude, landmark.longitude))?.let { it < 1 } == true }
            settled
        }
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag("map-reference-${landmark.id}").fetchSemanticsNode().boundsInRoot
        val host = compose.onNodeWithTag("map-host").fetchSemanticsNode().boundsInRoot
        compose.activityRule.scenario.onActivity {
            val point = requireNotNull(nativeMap.get()).projection.toScreenLocation(LatLng(landmark.latitude, landmark.longitude))
            assertEquals(point.x, bounds.center.x - host.left, 1f)
            assertEquals(point.y, bounds.bottom - host.top, 1f)
        }
        compose.onNodeWithTag("map-reference-${landmark.id}").performClick()
        assertEquals(landmark.id, clicked.get())
        compose.activityRule.scenario.onActivity { requireNotNull(nativeMap.get()).moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(48.8566, 2.3522), 13.0)) }
        compose.waitUntil(30000) { compose.onAllNodesWithTag("map-reference-${landmark.id}").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun bubbleTracksCatalogCoordinateWhenPannedAndZoomed() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        assumeTrue(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("mapReferenceLive") == "true")
        assertTrue(BuildConfig.MAPTILER_KEY.isNotBlank())
        val catalog = catalogFromJson(JSONObject(instrumentation.targetContext.assets.open("catalog.json").bufferedReader().use { it.readText() }))
        val havana = catalog.places.first { it.id == "malecon" }
        assertEquals("city", havana.primaryScene()!!.associationPrecision)
        val clicked = AtomicReference<String?>()
        compose.activityRule.scenario.onActivity { activity -> activity.setContent { PilgrimageTheme { Surface(Modifier.fillMaxSize()) {
            Column(Modifier.systemBarsPadding().padding(20.dp)) {
                PageTitle("云端原作地点", "真实导入目录 · 哈瓦那城市参考点")
                MapPanel(listOf(havana), BuildConfig.MAPTILER_KEY, { clicked.set(it.id) }, Modifier.fillMaxWidth().weight(1f).testTag("map-host"))
                PlaceRow(catalog.places.first { it.id == "lee-tung-street" }, onClick = {})
                PlaceRow(catalog.places.first { it.id == "greenwich" }, onClick = {})
            }
        } } } }
        compose.waitForIdle()
        val nativeMap = AtomicReference<MapLibreMap?>()
        compose.waitUntil(30000) {
            compose.activityRule.scenario.onActivity { activity ->
                findMap(activity.window.decorView)?.let { view ->
                    view.getMapAsync { nativeMap.set(it) }
                }
            }
            var settled = false
            compose.activityRule.scenario.onActivity { settled = nativeMap.get()?.cameraPosition?.zoom?.let { it in 12.99..13.01 } == true }
            settled
        }
        val card = compose.onNodeWithTag("map-reference-malecon")
        fun awaitCard() = compose.waitUntil(90000) {
            compose.onAllNodesWithTag("map-reference-malecon").fetchSemanticsNodes().any {
                it.config.getOrElse(SemanticsProperties.StateDescription) { "" } == "图片已加载"
            }
        }
        fun checkCoordinate() {
            card.assertIsDisplayed()
            val body = card.fetchSemanticsNode().boundsInRoot
            val host = compose.onNodeWithTag("map-host").fetchSemanticsNode().boundsInRoot
            compose.activityRule.scenario.onActivity {
                val map = requireNotNull(nativeMap.get())
                val point = map.projection.toScreenLocation(LatLng(havana.latitude, havana.longitude))
                assertEquals("Pointer longitude must use the catalog coordinate", point.x, body.center.x - host.left, 1f)
                assertEquals("Pointer latitude must use the catalog coordinate", point.y, body.bottom - host.top, 1f)
                assertTrue("The cover must not leave a duplicate native marker", map.markers.none { it.title == havana.name })
            }
        }
        fun screenshot(name: String) {
            instrumentation.waitForIdleSync(); Thread.sleep(700)
            val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            File(folder, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        awaitCard(); screenshot("12-map-reference-a.png"); checkCoordinate()
        val initial = card.fetchSemanticsNode().boundsInRoot
        compose.activityRule.scenario.onActivity {
            val map = requireNotNull(nativeMap.get())
            val target = requireNotNull(map.cameraPosition.target)
            map.moveCamera(CameraUpdateFactory.newLatLng(LatLng(target.latitude, target.longitude + .0015)))
        }
        compose.waitUntil(30000) { compose.onAllNodesWithTag("map-reference-malecon").fetchSemanticsNodes().any { kotlin.math.abs(it.boundsInRoot.left - initial.left) > 20 } }
        awaitCard(); checkCoordinate()
        compose.activityRule.scenario.onActivity { requireNotNull(nativeMap.get()).moveCamera(CameraUpdateFactory.zoomTo(14.0)) }
        compose.waitForIdle()
        awaitCard(); checkCoordinate(); screenshot("13-map-reference-a-zoom.png")
        card.performClick()
        assertEquals(havana.id, clicked.get())
    }
}
