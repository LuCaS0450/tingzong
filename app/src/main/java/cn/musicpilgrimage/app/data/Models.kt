package cn.musicpilgrimage.app.data
import org.json.JSONObject

data class Artist(val id:String,val name:String,val aliases:List<String> = emptyList(),val color:String = "#C82444")
/** Release identity artwork is independent of evidence that a place appeared in a work. */
data class ReleaseArtwork(val id:String,val releaseTitle:String,val releaseYear:Int?,val releaseType:String,
 val imageUrl:String?,val thumbnailUrl:String? = null,val imageStatus:String = "missing",
 val imageWidth:Int? = null,val imageHeight:Int? = null,val sourceUrl:String = "",val imageCredit:String = "") {
 val approved get()=imageStatus=="approved" && !imageUrl.isNullOrBlank() && imageWidth!=null && imageHeight!=null && imageWidth>0 && imageHeight>0
 val aspectRatio get()=if(imageWidth!=null&&imageHeight!=null&&imageWidth>0&&imageHeight>0)imageWidth.toFloat()/imageHeight else 1f
}
data class Scene(val id:String,val artistId:String,val artistName:String,val work:String,val kind:String = "mv",val timestampSeconds:Int? = null,val sourceUrl:String = "",val imageUrl:String? = null,val imageCredit:String? = null,val imageStatus:String = "missing",val note:String = "",
 val referenceKind:String = "mv_frame",val releaseYear:Int? = null,val releaseType:String? = null,
 val associationPrecision:String = "unverified",val arrivalAnchorNote:String = "",val associationExcerpt:String = "",
 val canonicalWorkUrl:String = "",val verificationStatus:String = "pending",val overlayEligible:Boolean = false,
 val imageWidth:Int? = null,val imageHeight:Int? = null,val thumbnailUrl:String? = null,val aliases:List<String> = emptyList(),val displayArtwork:ReleaseArtwork? = null,val sourceVersionTitle:String = "") {
 val imageApproved get()=imageStatus=="approved" && !imageUrl.isNullOrBlank() && imageWidth!=null && imageHeight!=null && imageWidth>0 && imageHeight>0
 val publiclyVisible get()=verificationStatus=="verified" && imageStatus=="approved"
 val canRecreate get()=imageApproved && verificationStatus=="verified" && overlayEligible && kind in setOf("mv","album") && associationPrecision !in setOf("city","region","unverified")
 val aspectRatio get()=if(imageWidth!=null&&imageHeight!=null&&imageWidth>0&&imageHeight>0)imageWidth.toFloat()/imageHeight else if(referenceKind=="release_cover")1f else 16f/9f
 val kindLabel get()=(if(kind=="mv" && Regex("\\bLIVE\\b|现场|現場|演唱会|演唱會",RegexOption.IGNORE_CASE).containsMatchIn(sourceVersionTitle))"官方现场" else associationLabel(kind)) +
  when(associationPrecision){"city"->" · 城市参考";"region"->" · 区域参考";else->""}
 // Older catalogs already carry approved covers as reference images. Preserve those
 // during migration, but never use an MV frame as a release-artwork fallback.
 val displayCover get()=displayArtwork ?: if(referenceKind=="release_cover" && imageApproved)
  ReleaseArtwork("reference-cover-$id",work,releaseYear,releaseType?:"album",imageUrl,thumbnailUrl,imageStatus,imageWidth,imageHeight,sourceUrl,imageCredit.orEmpty()) else null
}
fun associationLabel(kind:String)=when(kind){"mv"->"MV取景";"album"->"封面 / 专辑取景";"song_title"->"歌名关联";"lyric"->"歌词提及";else->"关联类型待核实"}
fun Place.primaryScene():Scene?=scenes.filter {it.publiclyVisible && it.imageApproved}.sortedBy {if(it.referenceKind=="release_cover")0 else 1}.firstOrNull()
fun Place.displayScene():Scene?=scenes.filter {it.publiclyVisible && it.displayCover?.approved==true}.sortedBy {if(it.referenceKind=="release_cover")0 else 1}.firstOrNull()
fun Place.displayCover():ReleaseArtwork?=displayScene()?.displayCover
fun Place.associationLabels()=scenes.filter {it.publiclyVisible}.map{it.kindLabel}.distinct().joinToString(" · ")
data class Place(val id:String,val name:String,val city:String,val country:String,val latitude:Double,val longitude:Double,val radiusMeters:Int = 200,val precision:String = "landmark",val description:String = "",val coordinateSource:String = "",val verified:Boolean = true,val scenes:List<Scene> = emptyList(),val address:String = "")
data class LocationFix(val latitude:Double,val longitude:Double,val accuracy:Float,val capturedAt:Long,val ageMillis:Long,val isMock:Boolean = false) {
 fun json() = JSONObject().put("latitude",latitude).put("longitude",longitude).put("accuracy",accuracy.toDouble())
  .put("capturedAt",java.time.Instant.ofEpochMilli(capturedAt).toString()).put("ageMillis",ageMillis).put("mock",isMock)
}
data class Catalog(val places:List<Place> = emptyList(),val artists:List<Artist> = emptyList())
/** Apply the same association-level gate to cloud responses and the bundled catalog. */
fun Catalog.publicOnly()=copy(places=places.filter {it.verified}.map {p ->
 p.copy(scenes=p.scenes.filter {it.publiclyVisible})
}.filter {it.scenes.isNotEmpty()})
data class ContentItem(val raw:JSONObject) {
 val id get()=raw.optString("id")
 val kind get()=raw.optString("kind")
 val owner get()=raw.stringOrNull("user_id").orEmpty()
 val nickname get()=raw.optString("nickname","听众")
 val body get()=raw.optString("body")
 val song get()=raw.optString("song")
 val artist get()=raw.optString("artist")
 val location get()=raw.stringOrNull("location_id").orEmpty()
 val placeName get()=raw.optString("location_name",raw.optString("place_name"))
 val sceneId get()=raw.stringOrNull("scene_id").orEmpty()
 val mediaId get()=raw.stringOrNull("media_id")
 val visibility get()=raw.optString("visibility","private")
 val liked get()=raw.optBoolean("liked")
 val likes get()=raw.optInt("likes")
 val hidden get()=raw.optBoolean("is_hidden")
 val date get()=raw.optString("created_at").take(10)
}
