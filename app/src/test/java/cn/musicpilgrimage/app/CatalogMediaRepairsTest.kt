package cn.musicpilgrimage.app

import cn.musicpilgrimage.app.data.*
import org.junit.Assert.*
import org.junit.Test

class CatalogMediaRepairsTest {
    private val scene = Scene("scene", "artist", "歌手", "歌曲", imageUrl = "https://example.org/missing.jpg",
        imageStatus = "approved", verificationStatus = "verified", imageWidth = 1280, imageHeight = 720)
    private val art = ReleaseArtwork("real-release", "正式专辑", null, "album", "https://example.org/cover.jpg",
        imageStatus = "approved", imageWidth = 800, imageHeight = 800)
    private val reference = SceneReferenceRepair("file:///android_asset/reference.png", 1280, 720,
        "https://example.org/original", 72, "原作发行方")
    private val repair = CatalogMediaRepair(scene.id, scene.artistId, scene.work, scene.imageUrl!!, art, reference)
    private fun catalog(value: Scene = scene) = Catalog(listOf(Place("place", "地点", "城市", "国家", 1.0, 2.0, scenes = listOf(value))))

    @Test fun repairsKeepFavoriteAndNavigationKeysAndGeographicRules() {
        val before = catalog()
        val after = before.withMediaRepairs(listOf(repair))
        val fixed = after.places.single().scenes.single()
        assertEquals(MusicCatalogue.from(before).releases.single().id, MusicCatalogue.from(after).releases.single().id)
        assertEquals(MusicCatalogue.from(before).works.single().id, MusicCatalogue.from(after).works.single().id)
        assertEquals(before.places.single().latitude, after.places.single().latitude, 0.0)
        assertEquals(scene.overlayEligible, fixed.overlayEligible)
        assertEquals(scene.associationPrecision, fixed.associationPrecision)
        assertEquals(reference.imageUrl, fixed.imageUrl)
        assertEquals(art.id, fixed.displayCover?.id)
        assertNotEquals(fixed.imageUrl, fixed.displayCover?.imageUrl)
    }

    @Test fun sourceVersionOrIdentityChangesCannotReceiveAnOldRepair() {
        listOf(scene.copy(artistId = "other"), scene.copy(work = "另一首歌"),
            scene.copy(imageUrl = "https://example.org/new-version.jpg"), scene.copy(id = "other"))
            .forEach { value -> assertEquals(catalog(value), catalog(value).withMediaRepairs(listOf(repair))) }
    }

    @Test fun pendingScenesAndExplicitArtworkDecisionsStayAuthoritative() {
        val pending = scene.copy(verificationStatus = "pending")
        assertEquals(catalog(pending), catalog(pending).withMediaRepairs(listOf(repair)))
        val existing = art.copy(id = "cloud-art", imageStatus = "rejected")
        val fixed = catalog(scene.copy(displayArtwork = existing)).withMediaRepairs(listOf(repair)).places.single().scenes.single()
        assertEquals(existing, fixed.displayArtwork)
    }
}
