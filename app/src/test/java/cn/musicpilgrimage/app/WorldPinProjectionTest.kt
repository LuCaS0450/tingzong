package cn.musicpilgrimage.app

import cn.musicpilgrimage.app.ui.room.WorldMapMath
import cn.musicpilgrimage.app.ui.room.WorldTilePipeline
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class WorldPinProjectionTest {

    @Test
    fun globeZoom_remainsWithinNativeMapLibreBounds() {
        for (lat in listOf(-75.0, -33.87, 0.0, 24.0, 48.85, 75.0)) {
            val z = WorldMapMath.globeZoom(lat, 360.0, 560.0)
            assertTrue("globeZoom($lat)=$z must be >= 0.0 for MapLibre Native", z >= 0.0)
            assertTrue("globeZoom($lat)=$z must fit overview scale", z <= 3.0)
        }
    }

    @Test
    fun equatorialZoom_preservesConstantGlobeDiameterAcrossLatitudes() {
        val eqZoom = WorldMapMath.globeEquatorialZoom(360.0, 640.0)
        val maxLat = WorldMapMath.maxConstantSizeGlobeLatitude(eqZoom)
        assertTrue("maxConstantSizeGlobeLatitude($eqZoom)=$maxLat must cover 60+ deg", maxLat > 60.0)

        val expectedRadius = WorldMapMath.globeBounds(
            zoom = eqZoom,
            latitude = 0.0,
            centerX = 180f,
            centerY = 320f,
            stageHeightPx = 1280f,
            density = 2f,
        ).radius
        assertEquals(648f, expectedRadius * 2f, 0.05f)

        for (lat in listOf(-55.0, -24.0, 0.0, 24.0, 55.0)) {
            val z = WorldMapMath.zoomForEquatorialZoom(eqZoom, lat)
            assertTrue("zoomForEquatorialZoom($lat)=$z must be >= 0", z >= 0.0)
            assertEquals(eqZoom, WorldMapMath.equatorialZoom(z, lat), 1e-6)
            assertEquals(eqZoom, WorldMapMath.visualZoom(z, lat), 1e-6)
            val r = WorldMapMath.globeBounds(
                zoom = z,
                latitude = lat,
                centerX = 180f,
                centerY = 320f,
                stageHeightPx = 1280f,
                density = 2f,
            ).radius
            assertEquals("Sphere screen radius at lat=$lat must stay constant", expectedRadius, r, 0.05f)
        }
        assertEquals(15.0, WorldMapMath.visualZoom(15.0, 55.0), 1e-6)
        assertEquals(15.0, WorldMapMath.visualZoom(15.0, 0.0), 1e-6)
    }

    @Test
    fun overviewGlobeFitsInAShortViewport() {
        val zoom = WorldMapMath.globeEquatorialZoom(640.0, 300.0)
        val diameter = 2f * WorldMapMath.globeBounds(zoom, 0.0, 320f, 150f, 300f, 1f).radius
        assertEquals(264f, diameter, 0.05f)
    }

    @Test
    fun measureGlobeRadiusFromProjection_recoversTrueRenderedRadius() {
        val bigR = 460.0
        val d = 1400.0
        val theta1 = Math.toRadians(22.0)
        val theta2 = Math.toRadians(44.0)
        val s1 = bigR * sin(theta1) / (1.0 + (bigR / d) * (1.0 - cos(theta1)))
        val s2 = bigR * sin(theta2) / (1.0 + (bigR / d) * (1.0 - cos(theta2)))
        val expectedRadius = bigR / sqrt(1.0 + 2.0 * bigR / d)

        val recovered = WorldMapMath.measureGlobeRadiusFromProjection(s1, theta1, s2, theta2)
        assertNotNull(recovered)
        assertEquals(expectedRadius, recovered!!, 0.05)
    }

    @Test
    fun gradePixels_bilinearSamplesOceanAtlasWithoutDarkEdgeHalo() {
        // 2x1 atlas: left pixel is deep ocean (alpha=255), right pixel is transparent land (alpha=0, rgb=0).
        val atlasPixels = intArrayOf(
            (0xFF shl 24) or (30 shl 16) or (80 shl 8) or 110,
            0x00000000,
        )
        val tileWidth = 8
        val tileHeight = 1
        val pixels = IntArray(tileWidth * tileHeight) {
            // Pure dark blue water input so water=1 across row.
            (0xFF shl 24) or (10 shl 16) or (40 shl 8) or 95
        }
        WorldTilePipeline.gradePixels(
            pixels = pixels,
            width = tileWidth,
            height = tileHeight,
            z = 0,
            tileX = 0,
            tileY = 0,
            atlasWidth = 2,
            atlasHeight = 1,
            atlasPixels = atlasPixels,
            oceanMask = null,
        )
        // Smooth monotonic transition across the boundary; no sudden black band from transparent RGB=0.
        for (i in pixels.indices) {
            val r = (pixels[i] ushr 16) and 0xFF
            val g = (pixels[i] ushr 8) and 0xFF
            val b = pixels[i] and 0xFF
            assertTrue("Pixel $i should remain blue-tinted water without black halo: ($r,$g,$b)", b > r && g > 40)
        }
        val bLeft = pixels[1] and 0xFF
        val bMid = pixels[3] and 0xFF
        val bRight = pixels[5] and 0xFF
        assertTrue("Bilinear blend should vary smoothly across atlas boundary", bLeft != bMid && bMid != bRight)
    }

    @Test
    fun decodeOceanMask_parsesSyntheticMvtOceanAndIgnoresNonOcean() {
        val oceanTile = buildSyntheticWaterTile(className = "ocean")
        val mask = WorldTilePipeline.decodeOceanMask(oceanTile, 16, 16)
        assertNotNull(mask)
        val nonZeroCount = mask!!.count { (it.toInt() and 0xFF) > 0 }
        assertTrue("Synthetic ocean half-tile should cover roughly half of pixels, got $nonZeroCount", nonZeroCount in 90..160)
        assertEquals(0xFF, mask[4 * 16 + 4].toInt() and 0xFF)
        assertEquals(0, mask[4 * 16 + 12].toInt() and 0xFF)

        val lakeTile = buildSyntheticWaterTile(className = "lake")
        val lakeMask = WorldTilePipeline.decodeOceanMask(lakeTile, 16, 16)
        assertNotNull(lakeMask)
        assertTrue("Non-ocean water polygon must produce an empty mask", lakeMask!!.all { it.toInt() == 0 })
    }

    @Test
    fun wrapLongitude_andClampLatitude_matchGeographicBounds() {
        assertEquals(-170.0, WorldMapMath.wrapLongitude(190.0), 1e-6)
        assertEquals(170.0, WorldMapMath.wrapLongitude(-190.0), 1e-6)
        assertEquals(80.0, WorldMapMath.clampLatitude(89.5), 1e-6)
        assertEquals(-80.0, WorldMapMath.clampLatitude(-85.0), 1e-6)
    }

    @Test
    fun scaleBarMetric_reflectsZoom() {
        val (globeLabel, globeWidth) = WorldMapMath.scaleBarMetric(1.0, 24.0, 2.0f)
        val (streetLabel, streetWidth) = WorldMapMath.scaleBarMetric(15.0, 48.85, 2.0f)
        assertTrue(globeLabel.endsWith("km"))
        assertTrue(streetLabel.endsWith("m"))
        assertTrue(globeWidth > 0f && streetWidth > 0f)
    }

    @Test
    fun buildWorldStyleJson_supportsOfflineAndRemoteKeyModes() {
        val offlineStyle = JSONObject(WorldTilePipeline.buildWorldStyleJson(rawKey = "", includeStreets = false))
        assertEquals("globe", offlineStyle.getJSONObject("projection").getString("name"))
        val offlineSources = offlineStyle.getJSONObject("sources")
        assertTrue(offlineSources.has("earth-base"))
        assertTrue(offlineSources.has("earth"))
        assertFalse(offlineSources.has("globe-graticule"))
        assertFalse(offlineSources.has("earth-dem"))
        assertFalse(offlineSources.has("streets"))
        assertFalse(offlineSources.has("earth-hd"))
        val offlineLayers = offlineStyle.getJSONArray("layers")
        var hasPinShadowLayer = false
        for (i in 0 until offlineLayers.length()) {
            val layer = offlineLayers.getJSONObject(i)
            if (layer.getString("id") == "world-place-pin-shadows") {
                hasPinShadowLayer = true
                assertEquals("map", layer.getJSONObject("layout").getString("icon-pitch-alignment"))
            }
            if (layer.getString("type") == "raster") {
                assertFalse(layer.has("maxzoom"))
                assertEquals(1, layer.getJSONObject("paint").getInt("raster-opacity"))
            }
        }
        assertTrue("Globe style must include tangent-plane pin contact shadow layer", hasPinShadowLayer)

        val keyedStyle = JSONObject(WorldTilePipeline.buildWorldStyleJson(rawKey = "test-key"))
        val keyedSources = keyedStyle.getJSONObject("sources")
        assertTrue(keyedSources.has("earth-base"))
        assertTrue(keyedSources.has("earth"))
        assertTrue(keyedSources.has("earth-hd"))
        assertFalse(keyedSources.has("earth-dem"))
        assertFalse(keyedSources.has("globe-graticule"))
        assertTrue(keyedSources.has("streets"))
        assertEquals("mapbox://mapbox.mapbox-streets-v8", keyedSources.getJSONObject("streets").getString("url"))
        assertTrue(
            keyedSources.getJSONObject("earth-hd").getJSONArray("tiles").getString(0).contains("/v2/"),
        )
    }

    private fun buildSyntheticWaterTile(className: String): ByteArray {
        fun encodeVarint(out: ByteArrayOutputStream, value: Int) {
            var v = value
            while (true) {
                if ((v and 0x7F.inv()) == 0) {
                    out.write(v)
                    return
                }
                out.write((v and 0x7F) or 0x80)
                v = v ushr 7
            }
        }
        fun zigzag(n: Int): Int = (n shl 1) xor (n shr 31)
        fun writeBytesField(out: ByteArrayOutputStream, field: Int, data: ByteArray) {
            encodeVarint(out, (field shl 3) or 2)
            encodeVarint(out, data.size)
            out.write(data)
        }
        fun writeVarintField(out: ByteArrayOutputStream, field: Int, value: Int) {
            encodeVarint(out, (field shl 3) or 0)
            encodeVarint(out, value)
        }

        // Left half of tile [0..2048] x [0..4096]
        val geom = ByteArrayOutputStream().apply {
            encodeVarint(this, (1 shl 3) or 1) // MoveTo(1)
            encodeVarint(this, zigzag(0))
            encodeVarint(this, zigzag(0))
            encodeVarint(this, (3 shl 3) or 2) // LineTo(3)
            encodeVarint(this, zigzag(2048)); encodeVarint(this, zigzag(0))
            encodeVarint(this, zigzag(0)); encodeVarint(this, zigzag(4096))
            encodeVarint(this, zigzag(-2048)); encodeVarint(this, zigzag(0))
            encodeVarint(this, (1 shl 3) or 7) // ClosePath(1)
        }.toByteArray()

        val tags = ByteArrayOutputStream().apply {
            encodeVarint(this, 0)
            encodeVarint(this, 0)
        }.toByteArray()

        val feature = ByteArrayOutputStream().apply {
            writeBytesField(this, 2, tags)
            writeVarintField(this, 3, 3) // POLYGON
            writeBytesField(this, 4, geom)
        }.toByteArray()

        val valueMsg = ByteArrayOutputStream().apply {
            writeBytesField(this, 1, className.toByteArray(Charsets.UTF_8))
        }.toByteArray()

        val layer = ByteArrayOutputStream().apply {
            writeBytesField(this, 1, "water".toByteArray(Charsets.UTF_8))
            writeBytesField(this, 2, feature)
            writeBytesField(this, 3, "class".toByteArray(Charsets.UTF_8))
            writeBytesField(this, 4, valueMsg)
            writeVarintField(this, 5, 4096)
        }.toByteArray()

        return ByteArrayOutputStream().apply {
            writeBytesField(this, 3, layer)
        }.toByteArray()
    }
}
