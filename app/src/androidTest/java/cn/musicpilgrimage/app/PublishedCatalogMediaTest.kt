package cn.musicpilgrimage.app

import androidx.test.platform.app.InstrumentationRegistry
import cn.musicpilgrimage.app.data.*
import cn.musicpilgrimage.app.ui.room.SwissDetailMedia
import cn.musicpilgrimage.app.ui.room.placeArchiveTargets
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

@ExtendedDeviceTest
class PublishedCatalogMediaTest {
    @Test fun archiveReusesTheReviewedSoundtrackImagesFromWorld() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val raw=context.assets.open("catalog.json").bufferedReader().use{catalogFromJson(JSONObject(it.readText()))}
        val catalog=BundledCatalogMedia(context).apply(BundledCatalogCorrections(context).apply(raw)).publicOnly()
        val music=MusicCatalogue.from(catalog)
        val media=SwissDetailMedia(context)
        val referenceKinds=setOf("film_preview","film_frame","soundtrack_mv_frame")
        val reviewed=catalog.places.flatMap{place->
            place.scenes.filter{it.kind=="movie_soundtrack"&&it.imageApproved&&it.referenceKind in referenceKinds}
                .map{scene->place to scene}
        }
        assertTrue(reviewed.any{(_,scene)->scene.work=="Stay Alive"&&scene.referenceKind=="film_preview"})
        for((place,scene) in reviewed) {
            val world=media.place(place,scene)
            val archive=media.placeArchive(place,null,setOf(scene.id),music.placeArchiveTargets(place.id))
            val soundtrack=archive.getJSONArray("connections").objects().single{it.getString("label")=="电影原声关联"}
            val row=soundtrack.getJSONArray("rows").objects().first{it.optString("actionId")==scene.id||it.getString("work")==scene.work}
            val thumbnail=row.getJSONObject("media")
            assertEquals(world.getString("image"),thumbnail.getString("image"))
            assertEquals(world.getString("mediaLabel"),thumbnail.getString("label"))
            assertFalse(thumbnail.getBoolean("isMv"))
            assertTrue(thumbnail.isNull("timestamp"))
        }
    }

    @Test fun publishedMediaPreservesSavedIdentitiesAndReviewedEvidence() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val encoded = InstrumentationRegistry.getArguments().getString("catalogGzipBase64")
        assumeTrue("Requires the reviewed catalog response", encoded != null)
        val text = GZIPInputStream(ByteArrayInputStream(android.util.Base64.decode(requireNotNull(encoded), 0)))
            .bufferedReader().use { it.readText() }
        fun json(path: String) = JSONObject(context.assets.open(path).bufferedReader().use { it.readText() })
        val repairs = json("design/music-space/catalog-media-repairs.json").getJSONArray("repairs").objects()
        val ids = repairs.map { it.getString("id") }.toSet()
        fun selected(catalog: Catalog) = catalog.copy(places = catalog.places.map { place ->
            place.copy(scenes = place.scenes.filter { it.id in ids })
        }.filter { it.scenes.isNotEmpty() })
        val before = selected(BundledCatalogMedia(context).apply(BundledCatalogCorrections(context).apply(catalogFromJson(json("catalog.json")))))
        val after = selected(catalogFromJson(JSONObject(text)))
        val oldMusic = MusicCatalogue.from(before)
        val newMusic = MusicCatalogue.from(after)
        assertEquals(oldMusic.releases.map { it.id }.toSet(), newMusic.releases.map { it.id }.toSet())
        assertEquals(oldMusic.works.map { it.id }.toSet(), newMusic.works.map { it.id }.toSet())
        assertEquals(before.places.map { it.id }.toSet(), after.places.map { it.id }.toSet())
        val scenes = after.places.flatMap { it.scenes }
        assertEquals(22, scenes.size)
        after.places.forEach { place ->
            val old = before.places.single { it.id == place.id }
            assertEquals(old.latitude, place.latitude, 0.0)
            assertEquals(old.longitude, place.longitude, 0.0)
            assertEquals(old.radiusMeters, place.radiusMeters)
        }
        scenes.forEach { scene ->
            assertTrue(scene.publiclyVisible && scene.imageApproved)
            assertTrue(scene.displayCover?.approved == true)
            assertTrue(scene.imageUrl!!.startsWith("https://"))
            assertNotEquals(scene.imageUrl, scene.displayCover?.imageUrl)
            assertFalse(scene.canRecreate)
            assertTrue(scene.editorialIntro.isNotBlank())
            assertTrue(scene.displayCover?.releaseYear == null || scene.displayCover?.releaseYear in 1900..2200)
        }
        assertEquals("lyric", scenes.single { it.work == "同舟之情" }.kind)
        assertEquals("movie_soundtrack", scenes.single { it.work == "遇见" }.kind)
        val preview = scenes.single { it.work == "Stay Alive" }
        assertEquals("film_preview", preview.referenceKind)
        assertNull(preview.timestampSeconds)
        assertTrue(scenes.single { it.work == "Numb" }.editorialIntro.contains("Numb"))
    }
}
