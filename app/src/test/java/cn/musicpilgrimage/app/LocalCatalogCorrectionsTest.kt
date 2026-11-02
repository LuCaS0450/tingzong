package cn.musicpilgrimage.app

import cn.musicpilgrimage.app.data.*
import org.junit.Assert.*
import org.junit.Test

class LocalCatalogCorrectionsTest {
    private val scene = Scene("scene", "artist", "歌手", "歌曲", imageUrl = "https://example.org/original.jpg",
        canonicalWorkUrl = "https://example.org/work", imageStatus = "approved", verificationStatus = "verified",
        imageWidth = 1280, imageHeight = 720, associationPrecision = "landmark")
    private val before = Place("saved-place", "旧地点", "城市", "国家", 1.0, 2.0, scenes = listOf(scene))
    private val after = before.copy(name = "新地点", latitude = 3.0, longitude = 4.0,
        scenes = listOf(scene.copy(kind = "movie_soundtrack", referenceKind = "soundtrack_mv_frame", note = "电影原声关联")))
    private val amendment = LocalCatalogCorrection(before, after)
    private fun corrected(place: Place, cloud: Boolean = false) = Catalog(listOf(place))
        .withLocalCorrections(listOf(amendment), cloud).places.single()

    @Test fun savedPlaceReleaseAndWorkIdentitiesSurviveCorrection() {
        val result = corrected(before)
        assertEquals(after.name, result.name)
        assertEquals(after.latitude, result.latitude, 0.0)
        assertEquals(before.id, result.id)
        assertEquals(scene.id, result.scenes.single().id)
        assertEquals(before.radiusMeters, result.radiusMeters)
        val oldMusic = MusicCatalogue.from(Catalog(listOf(before)))
        val newMusic = MusicCatalogue.from(Catalog(listOf(result)))
        assertEquals(oldMusic.releases.map { it.id }, newMusic.releases.map { it.id })
        assertEquals(oldMusic.works.map { it.id }, newMusic.works.map { it.id })
        assertEquals(before.id, CatalogSearch.filter(listOf(result), emptyList(), before.name).single().id)
    }

    @Test fun correctedBundleAndStaleCloudCannotCertifyNewLocation() {
        listOf(corrected(after), corrected(before, cloud = true)).forEach { place ->
            assertTrue(place.arrivalLocationPendingSync)
            assertNotNull(ArrivalRules.rejection(place, LocationFix(3.0, 4.0, 10f, 1000, 0)))
        }
        val synchronized = corrected(after, cloud = true)
        assertFalse(synchronized.arrivalLocationPendingSync)
        assertNull(ArrivalRules.rejection(synchronized, LocationFix(3.0, 4.0, 10f, 1000, 0)))
    }

    @Test fun unrelatedCloudChangesAndModerationDecisionsAreNotOverwritten() {
        listOf(before.copy(latitude = 5.0), before.copy(verified = false),
            before.copy(scenes = listOf(scene.copy(verificationStatus = "pending"))),
            before.copy(scenes = listOf(scene.copy(imageStatus = "rejected"))),
            before.copy(scenes = listOf(scene.copy(imageUrl = "https://example.org/new-version.jpg"))),
            before.copy(scenes = listOf(scene.copy(artistId = "another"))),
            before.copy(scenes = listOf(scene.copy(overlayEligible = true))),
            before.copy(scenes = listOf(scene.copy(canonicalWorkUrl = "https://example.org/new-work"))))
            .forEach { assertEquals(it, corrected(it, cloud = true)) }
    }

    @Test fun associationOnlyCorrectionDoesNotBlockArrivalOrEnableOverlay() {
        val amendment = LocalCatalogCorrection(before, after.copy(latitude = before.latitude, longitude = before.longitude))
        val result = Catalog(listOf(before)).withLocalCorrections(listOf(amendment), false).places.single()
        assertFalse(result.arrivalLocationPendingSync)
        assertFalse(result.scenes.single().canRecreate)
        assertEquals("电影原声关联", result.scenes.single().kindLabel)
        assertEquals("电影原声关联", scene.copy(kind = "movie_soundtrack", referenceKind = "film_frame").kindLabel)
        assertEquals("歌词提及", scene.copy(kind = "lyric", referenceKind = "lyric_mv_frame").kindLabel)
    }
}
