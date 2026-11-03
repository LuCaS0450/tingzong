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

    @Test fun publishedPreviewStillDoesNotInventAPlaybackTime() {
        val still = reference.copy(timestampSeconds = null, referenceKind = "film_preview")
        val result = catalog().withMediaRepairs(listOf(repair.copy(reference = still))).places.single().scenes.single()
        assertNull(result.timestampSeconds)
        assertEquals("film_preview", result.referenceKind)
    }

    @Test fun databaseOrderingDoesNotMoveThePreviouslyVisibleShelfAlbums() {
        val first = catalog().places.single().copy(scenes = listOf(scene, scene.copy(id = "second", work = "第二首")))
        val last = first.copy(id = "last", scenes = listOf(scene.copy(id = "third", work = "第三首")))
        val bundled = Catalog(listOf(first, last))
        val remote = bundled.copy(places = listOf(last, first.copy(scenes = first.scenes.reversed())))
        val arranged = remote.inBundledOrder(bundled)
        assertEquals(MusicCatalogue.from(bundled).releases.map { it.id }, MusicCatalogue.from(arranged).releases.map { it.id })
    }

    @Test fun stableOrderingDoesNotRestoreWithdrawnRowsOrDropNewRows() {
        val bundled = catalog()
        val newPlace = bundled.places.single().copy(id = "new", scenes = listOf(scene.copy(id = "new-scene")))
        val remote = Catalog(listOf(newPlace))
        assertEquals(remote, remote.inBundledOrder(bundled))
    }

    @Test fun pendingScenesAndExplicitArtworkDecisionsStayAuthoritative() {
        val pending = scene.copy(verificationStatus = "pending")
        assertEquals(catalog(pending), catalog(pending).withMediaRepairs(listOf(repair)))
        val existing = art.copy(id = "cloud-art", imageStatus = "rejected")
        val fixed = catalog(scene.copy(displayArtwork = existing)).withMediaRepairs(listOf(repair)).places.single().scenes.single()
        assertEquals(existing, fixed.displayArtwork)
    }
}
