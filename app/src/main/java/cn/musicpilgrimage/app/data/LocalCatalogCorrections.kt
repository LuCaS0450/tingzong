package cn.musicpilgrimage.app.data

import android.content.Context
import org.json.JSONObject

/** Local, reviewed amendments. Never writes catalog or personal data to the server. */
internal data class LocalCatalogCorrection(val before: Place, val after: Place) {
    init {
        require(before.id == after.id && before.scenes.size == 1 && after.scenes.size == 1)
        val old = before.scenes.single()
        val revised = after.scenes.single()
        require(old.id == revised.id && old.artistId == revised.artistId && old.work == revised.work)
        require(old.imageUrl == revised.imageUrl && revised.publiclyVisible && !revised.overlayEligible)
        require(after.latitude.isFinite() && after.latitude in -90.0..90.0)
        require(after.longitude.isFinite() && after.longitude in -180.0..180.0)
        require(before.radiusMeters == after.radiusMeters)
    }
}

internal fun Catalog.withLocalCorrections(corrections: List<LocalCatalogCorrection>, fromCloud: Boolean): Catalog {
    val byId = corrections.associateBy { it.before.id }
    return copy(places = places.map { place ->
        val correction = byId[place.id] ?: return@map place
        val before = correction.before
        val after = correction.after
        val old = before.scenes.single()
        val revised = after.scenes.single()
        val scene = place.scenes.find { it.id == old.id } ?: return@map place
        // A different cloud location, work version or moderation decision must not be overwritten.
        val originalPosition = place.latitude == before.latitude && place.longitude == before.longitude
        val correctedPosition = place.latitude == after.latitude && place.longitude == after.longitude
        if (!place.verified || !scene.publiclyVisible || (!originalPosition && !correctedPosition) ||
            scene.artistId != old.artistId || scene.work != old.work || scene.imageUrl != old.imageUrl ||
            scene.kind !in setOf(old.kind, revised.kind) || scene.overlayEligible != old.overlayEligible ||
            scene.canonicalWorkUrl !in setOf(old.canonicalWorkUrl, revised.canonicalWorkUrl)) return@map place
        val moved = before.latitude != after.latitude || before.longitude != after.longitude
        place.copy(name = after.name, latitude = after.latitude, longitude = after.longitude,
            precision = after.precision, description = after.description, coordinateSource = after.coordinateSource,
            arrivalLocationPendingSync = moved && !(fromCloud && correctedPosition), localCorrectionApplied = true,
            scenes = place.scenes.map { current -> if (current.id != old.id) current else current.copy(
                kind = revised.kind, referenceKind = revised.referenceKind, note = revised.note,
                sourceUrl = revised.sourceUrl, canonicalWorkUrl = revised.canonicalWorkUrl,
                timestampSeconds = revised.timestampSeconds, imageCredit = revised.imageCredit,
                associationPrecision = revised.associationPrecision, arrivalAnchorNote = revised.arrivalAnchorNote,
                associationExcerpt = revised.associationExcerpt, associationSourceUrl = revised.associationSourceUrl, overlayEligible = false,
                aliases = (current.aliases + before.name + revised.aliases).distinct()) })
    })
}

internal class BundledCatalogCorrections(context: Context) {
    private val corrections = runCatching {
        val data = JSONObject(context.assets.open("design/music-space/catalog-local-corrections.json")
            .bufferedReader().use { it.readText() })
        data.getJSONArray("corrections").objects().map {
            LocalCatalogCorrection(placeFromJson(it.getJSONObject("before")), placeFromJson(it.getJSONObject("after")))
        }
    }.getOrDefault(emptyList())

    fun apply(catalog: Catalog, fromCloud: Boolean = false) = catalog.withLocalCorrections(corrections, fromCloud)
}
