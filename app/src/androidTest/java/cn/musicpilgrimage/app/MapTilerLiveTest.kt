package cn.musicpilgrimage.app

import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.style.layers.LineLayer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Opt-in live rendering check; normal offline tests do not spend map requests. */
class MapTilerLiveTest {
    private fun findMap(view: View): MapView? {
        if (view is MapView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findMap(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    @Test fun configuredMapRendersWithRestrictedAppUserAgent() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("maptilerLive") == "true")
        assertTrue("The live test requires the configured map key", BuildConfig.MAPTILER_KEY.isNotBlank())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val userAgents = CopyOnWriteArrayList<String>()
        val statuses = CopyOnWriteArrayList<Int>()
        instrumentation.runOnMainSync { MapLibre.getInstance(context) }
        HttpRequestUtil.setPrintRequestUrlOnFailure(false)
        HttpRequestUtil.setOkHttpClient(OkHttpClient.Builder().addNetworkInterceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            if (request.url.host == "api.maptiler.com") {
                userAgents.add(request.header("User-Agent").orEmpty())
                statuses.add(response.code)
            }
            response
        }.build())
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val view = AtomicReference<MapView?>()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(35)
                while (view.get() == null && System.nanoTime() < deadline) {
                    scenario.onActivity { view.set(findMap(it.window.decorView)) }
                    if (view.get() == null) Thread.sleep(100)
                }
                val mapView = requireNotNull(view.get()) { "The configured home map was not created" }
                val nativeMap = AtomicReference<MapLibreMap>()
                val initialRender = CountDownLatch(1)
                val initialListener = MapView.OnDidFinishRenderingMapListener { fully ->
                    if (fully) initialRender.countDown()
                }
                scenario.onActivity {
                    mapView.addOnDidFinishRenderingMapListener(initialListener)
                    mapView.getMapAsync { nativeMap.set(it) }
                }
                assertTrue("Initial home map did not render", initialRender.await(60, TimeUnit.SECONDS))
                instrumentation.waitForIdleSync()
                scenario.onActivity { mapView.removeOnDidFinishRenderingMapListener(initialListener) }
                val map = requireNotNull(nativeMap.get())
                for ((name, location) in listOf(
                    "taipei" to LatLng(25.037, 121.565),
                    "paris" to LatLng(48.859, 2.342),
                    "tokyo" to LatLng(35.6762, 139.6503)
                )) {
                    val rendered = CountDownLatch(1)
                    val frameState = AtomicReference("no render callback")
                    val listener = MapView.OnDidFinishRenderingFrameListener { fully, _, _ ->
                        val camera = map.cameraPosition
                        val target = camera.target
                        frameState.set("fully=$fully lat=${target?.latitude} lon=${target?.longitude} zoom=${camera.zoom} distance=${target?.distanceTo(location)}")
                        if (fully && target != null && target.distanceTo(location) < 10 &&
                            kotlin.math.abs(camera.zoom - 12.0) < 0.01) {
                            val lineLayers = map.style?.layers?.filterIsInstance<LineLayer>()?.map { it.id }.orEmpty()
                            if (lineLayers.isNotEmpty() && map.queryRenderedFeatures(
                                RectF(0f, 0f, mapView.width.toFloat(), mapView.height.toFloat()),
                                *lineLayers.toTypedArray()
                            ).isNotEmpty()) rendered.countDown()
                        }
                    }
                    scenario.onActivity {
                        mapView.addOnDidFinishRenderingFrameListener(listener)
                        map.cancelTransitions()
                        map.moveCamera(CameraUpdateFactory.newLatLngZoom(location, 12.0))
                    }
                    val completed = rendered.await(90, TimeUnit.SECONDS)
                    if (!completed) {
                        val folder = java.io.File(context.getExternalFilesDir(null), "maptiler-check").apply { mkdirs() }
                        java.io.File(folder, "failure.json").writeText(org.json.JSONObject()
                            .put("city", name).put("frame", frameState.get())
                            .put("requests", statuses.size).put("statuses", org.json.JSONArray(statuses))
                            .put("userAgent", userAgents.firstOrNull()).toString(2))
                    }
                    assertTrue("Online map did not fully render: $name ${frameState.get()}", completed)
                    // Native completion precedes the SurfaceView buffer reaching the display compositor.
                    instrumentation.waitForIdleSync()
                    Thread.sleep(700)
                    scenario.onActivity {
                        mapView.removeOnDidFinishRenderingFrameListener(listener)
                        assertTrue("Camera must be at $name", requireNotNull(map.cameraPosition.target).distanceTo(location) < 10)
                        assertTrue("Rendered map must contain geographic features", map.queryRenderedFeatures(
                            RectF(0f, 0f, mapView.width.toFloat(), mapView.height.toFloat())
                        ).isNotEmpty())
                    }
                    val folder = java.io.File(context.getExternalFilesDir(null), "maptiler-check").apply { mkdirs() }
                    val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                    java.io.File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                assertTrue("The SDK must make real MapTiler requests", userAgents.isNotEmpty())
                assertTrue("Native requests must match the key restriction", userAgents.all { it.contains("cn.musicpilgrimage.app/") })
                assertFalse("Native requests must not be denied", statuses.any { it == 401 || it == 403 || it == 429 })
                val report = org.json.JSONObject().put("requests", statuses.size)
                    .put("userAgent", userAgents.first()).put("statuses", org.json.JSONArray(statuses))
                    .put("rendered", org.json.JSONArray(listOf("taipei", "paris", "tokyo")))
                    .put("scope", "Android emulator using the configured home map; not physical-device or proxy-free mainland acceptance")
                java.io.File(context.getExternalFilesDir(null), "maptiler-check/result.json").writeText(report.toString(2))
            }
        } finally {
            HttpRequestUtil.setOkHttpClient(null)
        }
    }
}
