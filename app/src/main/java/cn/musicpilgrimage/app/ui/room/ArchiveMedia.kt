package cn.musicpilgrimage.app.ui.room

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.musicpilgrimage.app.data.*
import cn.musicpilgrimage.app.ui.AppMotionDialog
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal data class ArchivePlaceLink(
    val id: String,
    val name: String,
    val location: String,
    val relations: String,
    val photo: ArchivePhoto?,
)

internal class SwissDetailMedia(private val context:Context) {
    private val reviewed by lazy{JSONObject(context.assets.open("design/swiss-details/media-index.json").bufferedReader().use{it.readText()})}
    val fallbacks=JSONObject()
    fun artwork(art:ReleaseArtwork?):String {
        if(art?.approved!=true)return ""
        return reviewed.optString(art.id).ifBlank{BundledCovers.model(context,art.id,art.imageUrl).orEmpty()}
    }
    fun scene(scene:Scene?, preferReleaseArtwork:Boolean=true):String {
        if(scene?.imageApproved!=true)return ""
        reviewed.optString(scene.id).takeIf{it.isNotBlank()}?.let{return it}
        if(preferReleaseArtwork&&scene.referenceKind=="release_cover")scene.displayCover?.takeIf{it.approved}?.let{return artwork(it)}
        val source=scene.imageUrl.orEmpty()
        BundledCovers.model(context,"reference-${scene.id}",source)?.takeIf{it!=source}?.let{return it}
        return source
    }
    fun archivePhoto(place: Place): ArchivePhoto? = ArchivePhotography.photo(context, place)?.also {
        fallbacks.put(it.asset, it.url)
    }

    private fun archivePlaceIdentity(place: Place): JSONObject {
        val name=PlaceEditorialCopy.name(context,place)
        val country=canonicalRegionName(place.country)
        val parts=geographicParts(place.city,place.country)
        val mergedRegion=unifiedDestinationRegion(country)
        val header=if(mergedRegion!=null)parts.joinToString(" · ")
            else (listOf(country)+parts.filterNot{it==country}).filter(String::isNotBlank).joinToString(" / ")
        return JSONObject().put("id",place.id).put("name",name).put("city",place.city).put("country",country)
            .put("cityGroup",mergedRegion?:place.city)
            .put("location",parts.joinToString(" · "))
            .put("headerLocation",header.takeUnless{it==name}.orEmpty())
            .put("listLocation",parts.filterNot{it==name}.joinToString(" · "))
    }

    fun archiveLink(link: MusicPlaceLink): ArchivePlaceLink {
        val place = link.place
        val identity = archivePlaceIdentity(place)
        return ArchivePlaceLink(
            place.id, identity.getString("name"), identity.getString("listLocation"),
            link.scenes.map { it.archiveConnection().label }.distinct().joinToString(" · "),
            ArchivePhotography.photo(context, place),
        )
    }

    private fun connectionImage(scene: Scene): JSONObject? {
        if (!scene.imageApproved) return null
        val label = when (scene.referenceKind) {
            "mv_frame" -> "MV 截图"
            "release_cover" -> "发行封面"
            "album_photo" -> "专辑内页"
            "film_preview", "film_frame", "soundtrack_mv_frame" -> scene.archiveMediaLabel()
            else -> return null
        }
        val original = scene.imageUrl.orEmpty()
        val isMv = scene.referenceKind == "mv_frame"
        val image = if (isMv) {
            reviewed.optString(scene.id).ifBlank {
                val thumbnail = scene.thumbnailUrl?.takeIf(String::isNotBlank) ?: original
                BundledCovers.model(context, "reference-${scene.id}", thumbnail).orEmpty()
            }
        } else scene(scene, preferReleaseArtwork = false)
        if (image.isBlank()) return null
        if (image != original && original.startsWith("https://")) fallbacks.put(image, original)
        // The bundled covers index can resolve to a low-resolution copy of the thumbnail.
        // Reviewed detail overrides and catalog repair URLs are approved original frames.
        val fullImage = if (isMv) reviewed.optString(scene.id).ifBlank { original } else null
        return JSONObject().put("image", image).put("fullImage", fullImage).put("label", label).put("isMv", isMv)
            .put("originalWidth", scene.imageWidth.takeIf { isMv }).put("originalHeight", scene.imageHeight.takeIf { isMv })
            .put("showLabel", scene.kind == "movie_soundtrack")
            .put("timestamp", scene.timestampSeconds.takeIf { isMv })
            .put("credit", scene.imageCredit.orEmpty())
    }

    private fun locatorAsset(place: Place): String? {
        val name = String.format(Locale.ROOT, "%s_%.6f_%.6f.webp", place.id, place.latitude, place.longitude)
        val path = "design/swiss-details/media/place-locators/$name"
        return runCatching { context.assets.open(path).use { "file:///android_asset/$path" } }.getOrNull()
    }

    fun placeArchive(place: Place, photo: ArchivePhoto?, entryScenes: Set<String>,
        targets: Map<String, MusicArchiveTarget>) = archivePlaceIdentity(place)
        .put("photo", photo?.json() ?: JSONObject.NULL).put("scope", place.archiveScope())
        .put("latitude", place.latitude).put("longitude", place.longitude)
        .put("locator", if (photo == null) locatorAsset(place) else null)
        .put("connections", place.archiveConnections(entryScenes, targets, ::connectionImage))

    fun place(place:Place,scene:Scene?=place.mapScene()):JSONObject=archivePlaceIdentity(place)
        .put("description",PlaceEditorialCopy.intro(context,place,scene)).put("image",scene(scene)).put("work",scene?.work.orEmpty())
        .put("artist",scene?.artistName.orEmpty()).put("kind",scene?.kind.orEmpty()).put("associationLabel",scene?.kindLabel.orEmpty())
        .put("source",scene?.sourceUrl.orEmpty()).put("credit",scene?.imageCredit.orEmpty())
        .put("timestamp",scene?.timestampSeconds?:JSONObject.NULL).put("arrivalNote","")
        .put("mediaLabel",scene?.archiveMediaLabel().orEmpty())
}

internal fun Scene.archiveMediaLabel():String=when(referenceKind) {
    "film_preview"->"电影片段公开预览图"
    "film_frame"->"电影原片"
    "soundtrack_mv_frame"->"原声曲目 MV · 非此地取景"
    "lyric_mv_frame"->"歌词关联 · MV 画面"
    "release_cover"->"正式发行封面"
    "album_photo"->"专辑内页"
    else->if(kindLabel.startsWith("官方现场"))"官方现场画面" else "MV 画面"
}

@Composable internal fun SwissArtworkSources(art:ReleaseArtwork?,title:String,links:List<MusicPlaceLink> = emptyList(),onClose:()->Unit) {
    val context=LocalContext.current
    AppMotionDialog(onClose,properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)) {
        RoomWall(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {Column {
            RoomHeader("资料来源",onBack=onClose,onSpace={})
            LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(25.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                item { Text(title,fontSize=25.sp,lineHeight=34.sp,color=RoomColors.Ink) }
                if(art!=null)item {
                    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        val credit=art.imageCredit.replace(Regex("https?://\\S+"),"").trim(' ','·')
                        if(credit.isNotBlank())Text(credit,fontSize=14.sp,lineHeight=24.sp,color=RoomColors.Muted)
                        if(art.sourceUrl.startsWith("http"))RoomSourceLink("查看原始发行资料",art.sourceUrl)
                        else Text("这张图片暂无来源信息。",fontSize=13.sp,color=RoomColors.Muted)
                    }
                }
                if(links.isNotEmpty())item { Text("收录依据",fontSize=18.sp,color=RoomColors.Ink) }
                items(links,key={it.place.id}) { link ->
                    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        HorizontalDivider(color=RoomColors.Line)
                        Text(PlaceEditorialCopy.name(context,link.place),fontSize=16.sp,color=RoomColors.Ink)
                        link.scenes.distinctBy{it.archiveConnection() to (it.associationSourceUrl.ifBlank{it.sourceUrl})}.forEach { scene ->
                            val label=scene.archiveConnection().label
                            val source=scene.associationSourceUrl.ifBlank{scene.sourceUrl.ifBlank{scene.canonicalWorkUrl}}
                            if(source.startsWith("http"))RoomSourceLink("查看${label}依据",source)
                            else Text(label,fontSize=13.sp,color=RoomColors.Muted)
                        }
                    }
                }
            }
        }}
    }
}
