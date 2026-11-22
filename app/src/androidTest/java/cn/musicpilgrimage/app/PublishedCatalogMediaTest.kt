package cn.musicpilgrimage.app

import android.content.Context
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import cn.musicpilgrimage.app.data.*
import cn.musicpilgrimage.app.ui.room.SwissDetailMedia
import cn.musicpilgrimage.app.ui.room.archiveConnections
import cn.musicpilgrimage.app.ui.room.placeArchiveTargets
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

@ExtendedDeviceTest
class PublishedCatalogMediaTest {
    private fun bundledPublicCatalog(context: Context): Catalog {
        val raw=context.assets.open("catalog.json").bufferedReader().use{catalogFromJson(JSONObject(it.readText()))}
        return BundledCatalogMedia(context).apply(BundledCatalogCorrections(context).apply(raw)).publicOnly()
    }

    @Test fun bundledArchiveLinksResolveToTheirOwnRecords() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val catalog = bundledPublicCatalog(context)
        val music = MusicCatalogue.from(catalog)
        val places = catalog.places.associateBy(Place::id)
        val releases = music.releases.associateBy(MusicRelease::id)
        val works = music.works.associateBy(MusicWork::id)
        assertEquals(catalog.places.size, places.size)
        assertEquals(music.releases.size, releases.size)
        assertEquals(music.works.size, works.size)

        fun checkPlaceLinks(source: String, links: List<MusicPlaceLink>) {
            assertEquals(source, links.size, links.distinctBy { it.place.id }.size)
            for (link in links) {
                val place = places.getValue(link.place.id)
                assertEquals("$source → ${link.place.id}", place, link.place)
                assertTrue(place.scenes.containsAll(link.scenes))
            }
        }
        for (release in releases.values) {
            checkPlaceLinks(release.id, release.places)
            for (work in release.works) {
                assertEquals(work, works.getValue(work.id))
                assertEquals(release.id, work.releaseId)
            }
        }
        for (work in works.values) {
            checkPlaceLinks(work.id, work.places)
            assertTrue(releases.getValue(work.releaseId).works.any { it.id == work.id })
        }

        var linksFromPlaces = 0
        for (place in places.values) {
            val targets = music.placeArchiveTargets(place.id)
            val groups = place.archiveConnections(emptySet(), targets) { null }.objects()
            val rows = groups.flatMap { it.getJSONArray("rows").objects() }
            for (row in rows) {
                val action = row.optString("actionId")
                if (action.isBlank()) continue
                val target = targets.getValue(action)
                val links = if (target.isRelease) releases.getValue(target.id).places else works.getValue(target.id).places
                assertTrue("${place.id} → ${target.id}", links.any { link ->
                    link.place.id == place.id && link.scenes.any { it.id == action }
                })
                assertEquals(target.title, row.getString("work"))
                linksFromPlaces++
            }
        }
        Log.i("ArchiveNavigationAudit", "${places.size} places, ${releases.size} releases, ${works.size} works, " +
            "${releases.values.sumOf { it.places.size }} release–place links, " +
            "${works.values.sumOf { it.places.size }} work–place links, $linksFromPlaces clickable links from places")
    }

    @Test fun archiveReusesTheReviewedSoundtrackImagesFromWorld() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val catalog=bundledPublicCatalog(context)
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
