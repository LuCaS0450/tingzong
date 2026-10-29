package cn.musicpilgrimage.app.data

import java.text.Normalizer
import java.util.Locale

/** A work is independent from both its release cover and its individual place evidence. */
data class MusicPlaceLink(val place: Place, val scenes: List<Scene>)
data class MusicWork(
    val id: String, val title: String, val artistId: String, val artist: String,
    val releaseId: String, val artwork: ReleaseArtwork?, val places: List<MusicPlaceLink>,
    val aliases: List<String>
)
data class MusicRelease(
    val id: String, val title: String, val artist: String, val artwork: ReleaseArtwork?,
    val works: List<MusicWork>, val places: List<MusicPlaceLink>
) {
    val directWork: MusicWork? get() = works.singleOrNull()?.takeIf { artwork?.releaseType == "single" }
}
data class MusicCatalogue(val releases: List<MusicRelease>, val works: List<MusicWork>) {
    fun search(query: String): List<MusicWork> {
        val words = musicTerm(query).split(Regex("\\s+")).filter(String::isNotEmpty)
        if (words.isEmpty()) return emptyList()
        val releaseNames = releases.associate { it.id to it.title }
        return works.filter { work ->
            val terms = (listOf(work.title, work.artist, releaseNames[work.releaseId].orEmpty()) + work.aliases).map(::musicTerm)
            words.all { word -> terms.any { word in it } }
        }
    }

    companion object {
        fun from(catalog: Catalog): MusicCatalogue {
            val visible = catalog.publicOnly()
            val entries = visible.places.flatMap { place -> place.scenes.map { place to it } }
            val workGroups = entries.groupBy { (_, scene) -> "${scene.artistId}|${musicTerm(scene.work)}" }
            val works = workGroups.mapNotNull { (id, group) ->
                val scene = group.first().second
                val art = group.firstNotNullOfOrNull { it.second.displayCover?.takeIf(ReleaseArtwork::approved) }
                // An album cover association alone does not invent a song with the album's name.
                if (group.all { it.second.kind == "album" && it.second.referenceKind == "release_cover" } && art?.releaseType == "album") return@mapNotNull null
                val releaseId = "${scene.artistId}|${art?.id ?: "work:${musicTerm(scene.work)}"}"
                MusicWork(id, scene.work, scene.artistId, scene.artistName, releaseId, art,
                    group.groupBy { it.first.id }.values.map { rows -> MusicPlaceLink(rows.first().first, rows.map { it.second }.distinctBy(Scene::id)) },
                    (group.flatMap { it.second.aliases } + visible.artists.find { it.id == scene.artistId }?.aliases.orEmpty()).distinct())
            }
            val releaseGroups = entries.groupBy { (_, scene) -> "${scene.artistId}|${scene.displayCover?.takeIf(ReleaseArtwork::approved)?.id ?: "work:${musicTerm(scene.work)}"}" }
            val releases = releaseGroups.map { (id, group) ->
                val scene = group.first().second
                val art = group.firstNotNullOfOrNull { it.second.displayCover?.takeIf(ReleaseArtwork::approved) }
                MusicRelease(id, art?.releaseTitle ?: scene.work, scene.artistName, art,
                    works.filter { it.releaseId == id },
                    group.groupBy { it.first.id }.values.map { rows -> MusicPlaceLink(rows.first().first, rows.map { it.second }.distinctBy(Scene::id)) })
            }
            return MusicCatalogue(releases, works)
        }
    }
}

private fun musicTerm(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT).replace("《", "").replace("》", "").trim()
