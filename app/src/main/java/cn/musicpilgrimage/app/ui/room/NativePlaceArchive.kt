package cn.musicpilgrimage.app.ui.room

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.musicpilgrimage.app.data.Place
import cn.musicpilgrimage.app.ui.LocalDetailZoom
import cn.musicpilgrimage.app.ui.LocalRouteEntryId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.cos

@Composable
internal fun NativePlaceArchive(
    place: Place,
    archive: JSONObject,
    photo: ArchivePhoto?,
    targets: Map<String, MusicArchiveTarget>,
    fallbacks: JSONObject,
    favorite: Boolean,
    favoriteBusy: Boolean,
    onFavorite: () -> Unit,
    onAdd: () -> Unit,
    onMap: () -> Unit,
    onConnection: (MusicArchiveTarget) -> Unit,
    onSources: () -> Unit,
) {
    val name = archive.optString("name")
    val entryId = LocalRouteEntryId.current
    val transitionThumbnail = LocalDetailZoom.current?.thumbnailFor(entryId, photo?.asset)
    var useLocator by remember(place.id, photo) { mutableStateOf(photo == null) }
    val locator = archive.optString("locator").takeIf { it.isNotBlank() }
    Column(Modifier.fillMaxWidth().testTag("native-place-archive")) {
        if (useLocator || photo == null) {
            ArchiveLocator(place, name, locator)
        } else {
            val ratio = photo.width.toFloat().div(photo.height.coerceAtLeast(1)).coerceIn(.35f, 3f)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val height = (maxWidth / ratio).coerceAtMost(330.dp)
                ArchiveImage(
                    "$entryId:place-photo:${place.id}", photo.asset, photo.description,
                    Modifier.align(Alignment.Center).width((height * ratio).coerceAtMost(maxWidth)).height(height)
                        .testTag("place-archive-photo"),
                    fallback = photo.url, credit = "${photo.author} · ${photo.license}",
                    onSources = onSources, onUnavailable = { useLocator = true },
                    thumbnail = transitionThumbnail,
                )
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 25.dp).padding(top = 20.dp, bottom = 10.dp)) {
            val location = archive.optString("headerLocation")
            if (location.isNotBlank()) SwissEyebrow(location)
            Text(
                name, Modifier.padding(top = 7.dp, bottom = 5.dp).testTag("place-archive-title"),
                fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium, color = RoomColors.Ink,
            )
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = RoomColors.Line)
            Row(Modifier.fillMaxWidth()) {
                SwissTextButton(onFavorite, Modifier.weight(1f).testTag("place-archive-favorite"), enabled = !favoriteBusy,
                    contentPadding = PaddingValues(horizontal = 4.dp)) {
                    ArchiveFavoriteIcon(favorite, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (favorite) "已收藏" else "收藏", color = RoomColors.Ink)
                }
                SwissTextButton(onAdd, Modifier.weight(1.25f).testTag("place-archive-add-trip"), contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(18.dp), tint = RoomColors.Ink)
                    Spacer(Modifier.width(4.dp))
                    Text("加入行程", color = RoomColors.Ink)
                }
                SwissTextButton(onMap, Modifier.weight(1f).testTag("place-archive-map"), contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Icon(Icons.Outlined.LocationOn, null, Modifier.size(18.dp), tint = RoomColors.Ink)
                    Spacer(Modifier.width(4.dp))
                    Text("地图", color = RoomColors.Ink)
                }
            }
            HorizontalDivider(color = RoomColors.Line)
            ReleaseSectionTitle("地点与音乐", "THE CONNECTION")
            val groups = archive.optJSONArray("connections")
            if (groups != null) for (groupIndex in 0 until groups.length()) {
                val group = groups.getJSONObject(groupIndex)
                val rows = group.getJSONArray("rows")
                if (groupIndex > 0) HorizontalDivider(color = RoomColors.Line)
                Text(group.optString("label"), Modifier.padding(top = 12.dp, bottom = 5.dp),
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = RoomColors.Red)
                for (rowIndex in 0 until rows.length()) {
                    val row = rows.getJSONObject(rowIndex)
                    val target = targets[row.optString("actionId")]
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        val work = row.optString("work")
                        val media = row.optJSONObject("media")
                        if (media != null) {
                            val image = media.optString("image")
                            val label = media.optString("label")
                            val seconds = media.optInt("timestamp", -1)
                            ArchiveImage(
                                "$entryId:connection:${place.id}:$groupIndex:$rowIndex", image, "《$work》$label",
                                Modifier.width(88.dp).aspectRatio(if (media.optBoolean("isMv") || media.optBoolean("showLabel")) 16f / 9f else 1.32f)
                                    .testTag("place-archive-connection-image"),
                                fallback = fallbacks.optString(image), credit = media.optString("credit").replace(Regex("https?://\\S+"), "").trim().takeIf(String::isNotBlank),
                                timestamp = seconds.takeIf { it >= 0 }?.let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" },
                                scale = if (media.optBoolean("isMv")) ContentScale.Crop else ContentScale.Fit,
                                onSources = onSources,
                                fullImage = media.optString("fullImage").takeIf(String::isNotBlank),
                                fullImageSize = IntSize(media.optInt("originalWidth"), media.optInt("originalHeight")),
                            )
                        }
                        Row(
                            Modifier.weight(1f).heightIn(min = 48.dp)
                                .then(if (target != null) Modifier.clickable(role = Role.Button) { onConnection(target) } else Modifier),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(work, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = RoomColors.Ink)
                                Text(row.optString("artist"), fontSize = 12.sp, color = RoomColors.Muted)
                                if (media?.optBoolean("showLabel") == true) Text(media.optString("label"), fontSize = 12.sp, color = RoomColors.Muted)
                            }
                            if (target != null) Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = RoomColors.Muted)
                        }
                    }
                }
            }
            SwissTextButton(onSources) { Text("资料来源 ↗", color = RoomColors.Muted) }
        }
    }
}

@Composable
private fun ArchiveLocator(place: Place, name: String, locator: String?) {
    val context = LocalContext.current
    var useOutline by remember(locator, place.id) { mutableStateOf(locator == null) }
    val shape = Modifier.fillMaxWidth().aspectRatio(720f / 354f)
    if (!useOutline && locator != null) {
        coil.compose.AsyncImage(locator, "${name}的街区地图，红点标示参考位置", shape,
            contentScale = ContentScale.FillBounds, onError = { useOutline = true })
    } else {
        val areas by produceState(emptyList<List<List<Offset>>>(), place.latitude, place.longitude) {
            value = withContext(Dispatchers.Default) { ArchiveLocatorGeometry.visible(context, place.latitude, place.longitude) }
        }
        Spacer(shape.clipToBounds().semantics { contentDescription = "${name}参考位置" }.drawWithCache {
            val paths = areas.map { area -> Path().apply {
                fillType = PathFillType.EvenOdd
                for (ring in area) {
                    ring.firstOrNull()?.let { moveTo(it.x * size.width, it.y * size.height) }
                    for (point in ring.drop(1)) lineTo(point.x * size.width, point.y * size.height)
                    close()
                }
            } }
            onDrawBehind {
                drawRect(Color(0xFFEFEFE9))
                for (line in 1..5) {
                    val x = size.width * line / 6
                    val y = size.height * line / 6
                    drawLine(RoomColors.Line, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
                    drawLine(RoomColors.Line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                }
                for (path in paths) {
                    drawPath(path, Color(0xFFE9E8DC))
                    drawPath(path, Color(0xFFB4B9AA), style = Stroke(1.dp.toPx()))
                }
                drawCircle(RoomColors.Red.copy(alpha = .13f), radius = 13.dp.toPx())
                drawCircle(RoomColors.Red, radius = 4.dp.toPx())
            }
        })
    }
}

private object ArchiveLocatorGeometry {
    private var countries: org.json.JSONArray? = null

    @Synchronized
    private fun load(context: Context): org.json.JSONArray =
        countries ?: context.assets.open("design/swiss-world/countries.json").bufferedReader().use {
            JSONObject(it.readText()).getJSONArray("countries")
        }.also { countries = it }

    fun visible(context: Context, lat: Double, lon: Double): List<List<List<Offset>>> {
        val countryList = runCatching { load(context) }.getOrNull() ?: return emptyList()
        val halfWidth = 10.0 * 360 / 177 / cos(Math.toRadians(lat)).coerceAtLeast(.28)
        return buildList {
            for (countryIndex in 0 until countryList.length()) {
                val areas = countryList.getJSONObject(countryIndex).getJSONArray("areas")
                for (areaIndex in 0 until areas.length()) {
                    val area = areas.getJSONObject(areaIndex)
                    val bounds = area.getJSONArray("bounds")
                    if (bounds.getDouble(3) < lat - 10 || bounds.getDouble(1) > lat + 10) continue
                    for (shift in listOf(-360, 0, 360)) {
                        if (bounds.getDouble(2) + shift < lon - halfWidth || bounds.getDouble(0) + shift > lon + halfWidth) continue
                        val rings = area.getJSONArray("rings")
                        add(List(rings.length()) { ringIndex ->
                            val ring = rings.getJSONArray(ringIndex)
                            List(ring.length()) { pointIndex ->
                                val point = ring.getJSONArray(pointIndex)
                                Offset(
                                    (.5 + (point.getDouble(0) + shift - lon) / (2 * halfWidth)).toFloat(),
                                    (.5 - (point.getDouble(1) - lat) / 20).toFloat(),
                                )
                            }
                        })
                    }
                }
            }
        }
    }
}
