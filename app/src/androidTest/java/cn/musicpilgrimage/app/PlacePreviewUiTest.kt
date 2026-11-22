package cn.musicpilgrimage.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.musicpilgrimage.app.data.BundledCatalogCorrections
import cn.musicpilgrimage.app.data.BundledCatalogMedia
import cn.musicpilgrimage.app.data.ContentItem
import cn.musicpilgrimage.app.data.MusicCatalogue
import cn.musicpilgrimage.app.data.catalogFromJson
import cn.musicpilgrimage.app.ui.PilgrimageTheme
import cn.musicpilgrimage.app.ui.room.ArchivePhotography
import cn.musicpilgrimage.app.ui.room.PlacePhotoThumbnail
import cn.musicpilgrimage.app.ui.room.RoomFootprints
import cn.musicpilgrimage.app.ui.room.RoomPlaceFavorites
import cn.musicpilgrimage.app.ui.room.SwissDetailMedia
import cn.musicpilgrimage.app.ui.room.WorldPlaceSummary
import cn.musicpilgrimage.app.ui.room.archiveScope
import cn.musicpilgrimage.app.ui.room.worldPlaceEntry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@ExtendedDeviceTest
class PlacePreviewUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun allPackagedArchiveLinksRetainTheirReviewedPhoto() {
        val raw = context.assets.open("catalog.json").bufferedReader().use { JSONObject(it.readText()) }
        val catalog = BundledCatalogMedia(context).apply(BundledCatalogCorrections(context).apply(catalogFromJson(raw)))
        val music = MusicCatalogue.from(catalog)
        val media = SwissDetailMedia(context)
        val releaseLinks = music.releases.flatMap { it.places }
        val workLinks = music.works.flatMap { it.places }
        for (link in releaseLinks + workLinks) {
            val reviewed = ArchivePhotography.photo(context, link.place)
            assertEquals(link.place.id, reviewed, media.archiveLink(link).photo)
        }
        val formerlyHidden = releaseLinks.map { it.place }.distinctBy { it.id }
            .filter { it.archiveScope() != "地点级" && ArchivePhotography.photo(context, it) != null }
        assertNotNull(formerlyHidden.find { it.id == "swiss-alps-reference" })
        val audit = "地点 ${catalog.places.size}，发行地点行 ${releaseLinks.size}，作品地点行 ${workLinks.size}，旧规则漏图地点 ${formerlyHidden.size}"
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
            putString("stream", "\n$audit\n")
        })
    }

    @Test
    fun personalPlaceCardsAndWorldShowPhotosWhenOriginalPreviewIsUnavailable() {
        val services = (context.applicationContext as PilgrimageApplication).services
        val place = services.catalog.value.places.first { it.id == "swiss-alps-reference" }
        val photo = requireNotNull(ArchivePhotography.photo(context, place))
        val missingImage = "file:///android_asset/nonexistent-place-preview-for-test.webp"
        val world = worldPlaceEntry(context, place)
        val scene = requireNotNull(world.scene)
        val entry = world.copy(scene = scene.copy(
            id = "unavailable-world-preview-fixture", imageUrl = missingImage, thumbnailUrl = null,
        ))
        val owner = "place-preview-fixture"
        val record = ContentItem(JSONObject().put("id", "preview-fixture-visit").put("kind", "visit")
            .put("user_id", owner).put("location_id", place.id).put("location_name", place.name)
            .put("created_at", "2026-11-22T10:00:00Z"))
        val archive = MusicArchiveState(owner = owner, places = listOf(place.id), visits = listOf(record))
        var page by mutableStateOf("fallback")
        compose.setContent {
            PilgrimageTheme {
                when (page) {
                    "fallback" -> PlacePhotoThumbnail(
                        "fallback", photo.copy(asset = missingImage, url = photo.asset),
                    )
                    "favorites" -> RoomPlaceFavorites(archive, listOf(place), {}, {}, {}, {})
                    "visits" -> RoomFootprints(archive, listOf(place), "visits", {}, {}, {}, {}, { _, _ -> }, services) {}
                    "world" -> WorldPlaceSummary(
                        entry, Modifier.fillMaxSize(), emptySet(), favoriteBusy = false, interactive = true,
                        onDismiss = {}, onReopen = {}, onMeasure = { _, _ -> }, onFavorite = {}, onAround = {},
                        onAdd = {}, onDetail = {},
                    )
                    else -> Unit
                }
            }
        }
        fun assertDecoded(tag: String) {
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
        }
        assertDecoded("place-photo-preview-fallback")
        for (destination in listOf("favorites", "visits")) {
            compose.runOnIdle { page = destination }
            assertDecoded("place-photo-preview-${place.id}")
        }
        compose.runOnIdle { page = "world" }
        assertDecoded("world-summary-place-photo")
        compose.onNodeWithTag("world-summary-media-credit").assertTextEquals(scene.artistName)
    }
}
