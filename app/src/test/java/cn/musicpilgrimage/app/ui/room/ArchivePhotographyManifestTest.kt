package cn.musicpilgrimage.app.ui.room

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchivePhotographyManifestTest {

    @Test
    fun mergePhotoManifestsAppliesLocalAndPreviewOverlaysInOrder() {
        val base = JSONObject("""{"p1":{"author":"base"},"p2":{"author":"base"}}""")
        val local = JSONObject("""{"p2":{"author":"local"},"p3":{"author":"local"}}""")
        val preview = JSONObject("""{"p3":{"author":"preview"},"p4":{"author":"preview"}}""")

        val merged = ArchivePhotography.mergePhotoManifests(base, local, preview)

        assertEquals("base", merged.getJSONObject("p1").getString("author"))
        assertEquals("local", merged.getJSONObject("p2").getString("author"))
        assertEquals("preview", merged.getJSONObject("p3").getString("author"))
        assertEquals("preview", merged.getJSONObject("p4").getString("author"))
    }

    @Test
    fun releaseLocalPlacePhotosAreBundledOutsidePublicManifest() {
        val swissDetails = File("src/main/assets/design/swiss-details")
        val publicManifest = JSONObject(File(swissDetails, "place-photos.json").readText(Charsets.UTF_8))
        val localManifestFile = File(swissDetails, "place-photos-local.json")
        assertTrue("Missing place-photos-local.json", localManifestFile.isFile)
        val localManifest = JSONObject(localManifestFile.readText(Charsets.UTF_8))

        val expectedIds = listOf(
            "chalet-schliechte-saas-fee",
            "eagle-rock-plaza-la",
            "familymart-sanfu",
            "lido-yucca-exterior-reference",
            "midorigaoka-railway",
            "onomichi-anago-reference",
            "red-house-steak-reference",
        )
        assertEquals(expectedIds.toSet(), localManifest.keys().asSequence().toSet())

        expectedIds.forEach { id ->
            assertFalse("$id must stay out of public place-photos.json", publicManifest.has(id))
            val entry = localManifest.getJSONObject(id)
            assertTrue("$id missing url", entry.getString("url").startsWith("https://"))
            val webp = File(swissDetails, "media/place-photography/$id.webp")
            assertTrue("Missing bundled photo $webp", webp.isFile && webp.length() > 1000)
        }
    }
}
