package cn.musicpilgrimage.app.ui.room

import android.content.Context
import cn.musicpilgrimage.app.data.MusicCatalogue
import cn.musicpilgrimage.app.data.MusicPlaceLink
import cn.musicpilgrimage.app.data.Place
import cn.musicpilgrimage.app.data.Scene
import cn.musicpilgrimage.app.data.musicTerm
import org.json.JSONArray
import org.json.JSONObject

internal enum class ArchiveConnection(val label: String) {
    MusicVideo("MV 取景"),
    Live("官方现场"),
    Title("歌名关联"),
    Lyrics("歌词提及"),
    ReleasePhoto("封面与发行摄影"),
    Soundtrack("电影原声关联"),
    Other("其他音乐关联");

    fun reason(places: String) = when (this) {
        MusicVideo -> "音乐影像的取景记录涉及$places"
        Live -> "官方现场影像与${places}有关"
        Title -> "歌名提到$places"
        Lyrics -> "歌词提到$places"
        ReleasePhoto -> "发行封面或内页的摄影记录涉及$places"
        Soundtrack -> "通过电影原声与${places}关联"
        Other -> "与${places}有经核实的音乐关联"
    }
}

internal fun Scene.archiveConnection(): ArchiveConnection = when (kind) {
    "mv" -> if (kindLabel.startsWith("官方现场")) ArchiveConnection.Live else ArchiveConnection.MusicVideo
    "song_title" -> ArchiveConnection.Title
    "lyric" -> ArchiveConnection.Lyrics
    "album" -> ArchiveConnection.ReleasePhoto
    "movie_soundtrack" -> ArchiveConnection.Soundtrack
    else -> ArchiveConnection.Other
}

internal fun Place.archiveScope(): String {
    val scopes = scenes.filter(Scene::publiclyVisible).map(Scene::associationPrecision)
    return when {
        scopes.any { it in setOf("landmark", "building", "street", "exact", "camera") } -> "地点级"
        "region" in scopes -> "区域级"
        "city" in scopes -> "城市级"
        else -> ""
    }
}

internal fun archiveAdmissionReason(links: List<MusicPlaceLink>, name: (Place) -> String): String {
    val byType = links.flatMap { link -> link.scenes.map { it.archiveConnection() to link.place } }
        .groupBy({ it.first }, { it.second })
    return ArchiveConnection.entries.mapNotNull { type ->
        val places = byType[type]?.distinctBy(Place::id) ?: return@mapNotNull null
        val example = places.take(2).map(name).distinct().joinToString("、")
        type.reason(if (places.size > 2) "${example}等${places.size}处地点" else example)
    }.joinToString("；").takeIf(String::isNotBlank)?.plus("。").orEmpty()
}

internal data class MusicArchiveTarget(val id: String, val title: String, val isRelease: Boolean)

internal fun MusicCatalogue.placeArchiveTargets(placeId: String): Map<String, MusicArchiveTarget> = buildMap {
    for (work in works) {
        work.places.find { it.place.id == placeId }?.scenes?.forEach { scene ->
            if (scene.archiveConnection() != ArchiveConnection.ReleasePhoto) {
                put(scene.id, MusicArchiveTarget(work.id, work.title, isRelease = false))
            }
        }
    }
    for (release in releases) {
        release.places.find { it.place.id == placeId }?.scenes?.forEach { scene ->
            if (scene.archiveConnection() == ArchiveConnection.ReleasePhoto) {
                put(scene.id, MusicArchiveTarget(release.id, release.title, isRelease = true))
            }
        }
    }
}

internal fun Place.archiveConnections(
    entryScenes: Set<String>, targets: Map<String, MusicArchiveTarget>, connectionImage: (Scene) -> JSONObject?,
): JSONArray {
    val scenesByType = scenes.filter(Scene::publiclyVisible).groupBy(Scene::archiveConnection)
    return JSONArray(ArchiveConnection.entries.mapNotNull { type ->
        val rows = scenesByType[type]?.groupBy { targets[it.id]?.id ?: "${it.artistId}|${musicTerm(it.work)}" }
            ?.values?.sortedByDescending { records -> records.any { it.id in entryScenes } }?.map { records ->
                val scene = records.firstOrNull { it.id in entryScenes } ?: records.first()
                val target = targets[scene.id]
                val image = when (type) {
                    ArchiveConnection.MusicVideo, ArchiveConnection.ReleasePhoto ->
                        connectionImage(scene) ?: records.firstNotNullOfOrNull(connectionImage)
                    else -> null
                }
                JSONObject().put("work", target?.title ?: scene.work).put("artist", scene.artistName)
                    .put("actionId", scene.id.takeIf { target != null })
                    .put("media", image)
            } ?: return@mapNotNull null
        JSONObject().put("label", type.label).put("rows", JSONArray(rows))
    })
}

internal data class ArchivePhoto(
    val asset: String, val url: String, val description: String, val width: Int, val height: Int,
    val author: String, val license: String, val licenseUrl: String, val source: String,
) {
    fun json() = JSONObject().put("image", asset).put("description", description)
        .put("width", width).put("height", height).put("credit", "$author · $license")
}

internal object ArchivePhotography {
    private var manifest: JSONObject? = null

    @Synchronized fun photo(context: Context, place: Place): ArchivePhoto? {
        if (place.archiveScope() != "地点级") return null
        val data = manifest ?: JSONObject(context.assets.open("design/swiss-details/place-photos.json")
            .bufferedReader().use { it.readText() }).also { manifest = it }
        val row = data.optJSONObject(place.id) ?: return null
        return ArchivePhoto(
            asset = "file:///android_asset/design/swiss-details/media/place-photography/${place.id}.webp",
            url = row.getString("url"), description = row.getString("description"),
            width = row.getInt("width"), height = row.getInt("height"), author = row.getString("author"),
            license = row.getString("license"), licenseUrl = row.getString("licenseUrl"), source = row.getString("source"),
        )
    }
}
