package cn.musicpilgrimage.app

import cn.musicpilgrimage.app.ui.room.WorldMapMath
import cn.musicpilgrimage.app.ui.room.WorldTilePipeline
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun wrapLongitude_andClampLatitude_matchGeographicBounds() {
        assertEquals(-170.0, WorldMapMath.wrapLongitude(190.0), 1e-6)
        assertEquals(170.0, WorldMapMath.wrapLongitude(-190.0), 1e-6)
        assertEquals(80.0, WorldMapMath.clampLatitude(89.5), 1e-6)
        assertEquals(-80.0, WorldMapMath.clampLatitude(-85.0), 1e-6)
    }

    @Test
    fun levelLabel_andScaleBarMetric_reflectZoomStages() {
        assertEquals("地球", WorldMapMath.levelLabel(0.9))
        assertEquals("国家或地区", WorldMapMath.levelLabel(5.0))
        assertEquals("城市", WorldMapMath.levelLabel(11.0))
        assertEquals("街道", WorldMapMath.levelLabel(15.0))

        val (globeLabel, globeWidth) = WorldMapMath.scaleBarMetric(1.0, 24.0, 2.0f)
        val (streetLabel, streetWidth) = WorldMapMath.scaleBarMetric(15.0, 48.85, 2.0f)
        assertTrue(globeLabel.endsWith("km"))
        assertTrue(streetLabel.endsWith("m"))
        assertTrue(globeWidth > 0f && streetWidth > 0f)
    }

    @Test
    fun buildWorldStyleJson_supportsOfflineAndRemoteKeyModes() {
        val offlineStyle = JSONObject(WorldTilePipeline.buildWorldStyleJson(rawKey = ""))
        assertEquals("globe", offlineStyle.getJSONObject("projection").getString("type"))
        val offlineSources = offlineStyle.getJSONObject("sources")
        assertTrue(offlineSources.has("earth-base"))
        assertTrue(offlineSources.has("earth"))
        assertFalse(offlineSources.has("streets"))
        assertFalse(offlineSources.has("earth-hd"))

        val keyedStyle = JSONObject(WorldTilePipeline.buildWorldStyleJson(rawKey = "test-key"))
        val keyedSources = keyedStyle.getJSONObject("sources")
        assertTrue(keyedSources.has("earth-base"))
        assertTrue(keyedSources.has("earth"))
        assertTrue(keyedSources.has("earth-hd"))
        assertTrue(keyedSources.has("streets"))
        assertTrue(keyedStyle.getString("glyphs").contains("test-key"))
    }
}
