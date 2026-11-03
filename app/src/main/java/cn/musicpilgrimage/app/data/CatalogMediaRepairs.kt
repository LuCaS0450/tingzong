package cn.musicpilgrimage.app.data

import android.content.Context
import org.json.JSONObject

/** Reviewed local media repairs; cloud visibility and geographic/business data stay authoritative. */
internal data class CatalogMediaRepair(
    val sceneId: String, val artistId: String, val work: String, val originalImageUrl: String,
    val artwork: ReleaseArtwork?, val reference: SceneReferenceRepair? = null
)

internal data class SceneReferenceRepair(
    val imageUrl: String, val width: Int, val height: Int,
    val sourceUrl: String, val timestampSeconds: Int?, val credit: String, val referenceKind: String = "mv_frame"
)

internal fun Catalog.withMediaRepairs(repairs: List<CatalogMediaRepair>): Catalog {
    val byId = repairs.associateBy { it.sceneId }
    return copy(places = places.map { place ->
        place.copy(scenes = place.scenes.map { scene ->
            val repair = byId[scene.id]
            if (repair == null || !scene.publiclyVisible || repair.artistId != scene.artistId ||
                repair.work != scene.work || repair.originalImageUrl != scene.imageUrl) scene
            else {
                // Preserve saved release/favorite/navigation identities when filling a previously absent cover.
                val artwork = repair.artwork?.takeIf { it.approved }?.copy(browsingKey =
                    scene.displayCover?.takeIf { it.approved }?.id ?: "work:${musicTerm(scene.work)}")
                val fixed = scene.copy(displayArtwork = scene.displayArtwork ?: artwork)
                repair.reference?.let { ref ->
                    fixed.copy(imageUrl = ref.imageUrl, thumbnailUrl = ref.imageUrl,
                        imageWidth = ref.width, imageHeight = ref.height, sourceUrl = ref.sourceUrl,
                        timestampSeconds = ref.timestampSeconds, imageCredit = ref.credit, referenceKind = ref.referenceKind)
                } ?: fixed
            }
        })
    })
}

internal class BundledCatalogMedia(context: Context) {
    private val repairs = runCatching {
        val rows = JSONObject(context.assets.open("design/music-space/catalog-media-repairs.json").bufferedReader().use { it.readText() })
            .getJSONArray("repairs")
        rows.objects().map { row ->
            val reference = row.optJSONObject("reference")?.takeIf { it.optString("status") == "verified" }?.let { ref ->
                val file = ref.getString("file")
                require(Regex("[A-Za-z0-9_-]+\\.(png|jpg|webp)").matches(file))
                val path = "design/music-space/covers/$file"
                context.assets.open(path).use { }
                SceneReferenceRepair("file:///android_asset/$path", ref.getInt("width"), ref.getInt("height"),
                    ref.getString("sourceUrl"), if(ref.isNull("timestampSeconds"))null else ref.getInt("timestampSeconds"), ref.getString("credit"), ref.optString("referenceKind", "mv_frame"))
                    .also { require(it.width > 0 && it.height > 0 && (it.timestampSeconds == null || it.timestampSeconds >= 0) && it.sourceUrl.startsWith("https://")) }
            }
            CatalogMediaRepair(row.getString("id"), row.getString("artistId"), row.getString("work"),
                row.getString("originalImageUrl"), row.optJSONObject("artwork")?.let(::releaseArtworkFromJson), reference)
        }
    }.getOrDefault(emptyList())

    fun apply(catalog: Catalog) = catalog.withMediaRepairs(repairs)
}
