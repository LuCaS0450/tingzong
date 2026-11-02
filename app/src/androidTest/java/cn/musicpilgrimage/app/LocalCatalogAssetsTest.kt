package cn.musicpilgrimage.app

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import cn.musicpilgrimage.app.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

/** Exercises the real private asset pipeline without network calls or personal-store writes. */
class LocalCatalogAssetsTest {
    @Test fun reviewedCorrectionsAndAllReferenceImagesLoadWithoutChangingSavedIdentities() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun json(path: String) = JSONObject(context.assets.open(path).bufferedReader().use { it.readText() })
        val source = catalogFromJson(json("catalog.json"))
        val manifest = json("design/music-space/catalog-local-corrections.json").getJSONArray("corrections").objects()
        assertEquals(5, manifest.size)
        val oldById = manifest.associate { row -> placeFromJson(row.getJSONObject("before")).let { it.id to it } }
        val stale = source.copy(places = source.places.map { oldById[it.id] ?: it })
        val corrections = BundledCatalogCorrections(context)
        val media = BundledCatalogMedia(context)
        val bundled = media.apply(corrections.apply(source))
        val cloud = media.apply(corrections.apply(stale, fromCloud = true))
        val corrected = bundled.places.filter { it.id in oldById }
        assertEquals(5, corrected.size)
        assertEquals(corrected, cloud.places.filter { it.id in oldById })
        assertEquals(3, corrected.count { it.arrivalLocationPendingSync })
        assertTrue(corrected.all { it.localCorrectionApplied && !it.scenes.single().canRecreate })
        val oldMusic = MusicCatalogue.from(media.apply(stale))
        val newMusic = MusicCatalogue.from(bundled)
        assertEquals(oldMusic.releases.map { it.id }.toSet(), newMusic.releases.map { it.id }.toSet())
        assertEquals(oldMusic.works.map { it.id }.toSet(), newMusic.works.map { it.id }.toSet())
        val date = LocalDate.of(2026, 11, 3)
        val trip = MusicTrip("saved-trip", "旧行程", date, date.plusDays(2),
            oldById.values.map { MusicTripCity(it.city, it.country) }.distinct(),
            oldById.values.mapIndexed { index, place -> MusicTripStop("saved-stop-$index", place.id, place.name, place.city, place.country, 1, "原备注") })
        val savedTrip = MusicTripCodec.encode(listOf(trip)).toString()
        val restored = MusicTripCodec.decode(JSONObject(savedTrip)).single()
        assertEquals(trip, restored)
        assertTrue(restored.stops.all { stop -> bundled.places.any { it.id == stop.placeId } })
        assertEquals(savedTrip, MusicTripCodec.encode(listOf(restored)).toString())
        val repairs = json("design/music-space/catalog-media-repairs.json").getJSONArray("repairs").objects()
        assertEquals(22, repairs.size)
        repairs.forEach { row ->
            val scene = bundled.places.flatMap { it.scenes }.single { it.id == row.getString("id") }
            assertTrue(scene.imageApproved)
            assertTrue(scene.displayCover?.approved == true)
            assertNotEquals(scene.imageUrl, scene.displayCover?.imageUrl)
            val file = row.getJSONObject("reference").getString("file")
            val bitmap = context.assets.open("design/music-space/covers/$file").use { BitmapFactory.decodeStream(it) }
            assertNotNull(file, bitmap)
            assertEquals(scene.imageWidth, bitmap.width)
            assertEquals(scene.imageHeight, bitmap.height)
            bitmap.recycle()
        }
    }
}
