package cn.musicpilgrimage.app.data
import org.json.JSONArray
import org.json.JSONObject
fun JSONArray.objects():List<JSONObject> = (0 until length()).map { getJSONObject(it) }
fun JSONArray.strings():List<String> = (0 until length()).map { getString(it) }
fun JSONObject.stringOrNull(key:String):String? = if(isNull(key)||!has(key)) null else optString(key).takeIf { it.isNotBlank() }
fun artistFromJson(o:JSONObject)=Artist(o.getString("id"),o.getString("name"),o.optJSONArray("aliases")?.strings() ?: emptyList(),o.optString("color","#C82444"))
fun releaseArtworkFromJson(o:JSONObject)=ReleaseArtwork(o.getString("id"),o.getString("releaseTitle"),o.nullableInt("releaseYear"),o.optString("releaseType","album"),
 o.stringOrNull("imageUrl"),o.stringOrNull("thumbnailUrl"),o.optString("imageStatus","missing"),o.nullableInt("imageWidth"),o.nullableInt("imageHeight"),o.optString("sourceUrl"),o.optString("imageCredit"))
fun sceneFromJson(o:JSONObject)=Scene(o.getString("id"),o.getString("artistId"),o.getString("artistName"),o.getString("work"),o.optString("kind","mv"),o.nullableInt("timestampSeconds"),o.optString("sourceUrl"),o.stringOrNull("imageUrl"),o.stringOrNull("imageCredit"),o.optString("imageStatus","missing"),o.optString("note"),
 o.optString("referenceKind",if(o.optString("kind")=="album")"album_photo" else "mv_frame"),o.nullableInt("releaseYear"),o.stringOrNull("releaseType"),o.optString("associationPrecision","unverified"),o.optString("arrivalAnchorNote"),o.optString("associationExcerpt"),o.optString("canonicalWorkUrl"),o.optString("verificationStatus","pending"),o.optBoolean("overlayEligible",false),o.nullableInt("imageWidth"),o.nullableInt("imageHeight"),o.stringOrNull("thumbnailUrl"),o.optJSONArray("aliases")?.strings() ?: emptyList(),o.optJSONObject("displayArtwork")?.let(::releaseArtworkFromJson),o.optJSONObject("imageProvenance")?.optString("versionTitle").orEmpty().ifBlank{o.optString("sourceVersionTitle")})
private fun JSONObject.nullableInt(key:String):Int?=if(!has(key)||isNull(key))null else getInt(key)
fun placeFromJson(o:JSONObject)=Place(o.getString("id"),o.getString("name"),o.getString("city"),o.getString("country"),o.getDouble("latitude"),o.getDouble("longitude"),o.optInt("radiusMeters",200),o.optString("precision","landmark"),o.optString("description"),o.optString("coordinateSource"),o.optBoolean("verified",true),o.optJSONArray("scenes")?.objects()?.map(::sceneFromJson) ?: emptyList(),o.optString("address"))
fun catalogFromJson(o:JSONObject)=Catalog(o.getJSONArray("places").objects().map(::placeFromJson),o.getJSONArray("artists").objects().map(::artistFromJson)).publicOnly()

