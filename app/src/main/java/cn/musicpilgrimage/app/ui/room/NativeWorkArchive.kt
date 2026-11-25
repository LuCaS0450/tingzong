package cn.musicpilgrimage.app.ui.room

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.musicpilgrimage.app.data.MusicWork
import cn.musicpilgrimage.app.ui.LocalDetailZoom
import cn.musicpilgrimage.app.ui.LocalRouteEntryId
import cn.musicpilgrimage.app.ui.RELEASE_COVER_SIDE_INSET

@Composable
internal fun NativeWorkArchive(
    work: MusicWork,
    releaseTitle: String,
    favorite: Boolean,
    onFavorite: () -> Unit,
    onPlace: (String) -> Unit,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onPerform: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val media = remember(context) { SwissDetailMedia(context) }
    val entryId = LocalRouteEntryId.current
    val art = work.artwork
    val transitionThumbnail = LocalDetailZoom.current?.thumbnailFor(entryId, art?.id)
    val cover = remember(media, art) { media.artwork(art) }
    val reason = remember(work.places, context) { archiveAdmissionReason(work.places) { PlaceEditorialCopy.name(context, it) } }
    val places = remember(work.places, media) { work.places.map(media::archiveLink) }
    var sources by rememberSaveable(work.id) { mutableStateOf(false) }
    val scroll = rememberScrollState()

    RoomWall(Modifier.fillMaxSize().testTag("native-work-archive")) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val unit = maxWidth / 360
            val inset = unit * RELEASE_COVER_SIDE_INSET
            val ratio = (art?.aspectRatio ?: 1f).coerceIn(.35f, 3f)
            val coverHeight = if (cover.isBlank()) 0.dp else unit * 16 + (maxWidth - inset * 2) / ratio
            val threshold = with(LocalDensity.current) { (coverHeight + 105.dp).roundToPx() }
            val collapsed by remember(scroll, threshold) { derivedStateOf { scroll.value >= threshold } }
            Column(Modifier.fillMaxSize()) {
                Spacer(Modifier.height(unit * 32))
                RoomHeader(if (collapsed) work.title else "作品档案", onBack = onBack, onSpace = {}, modifier = Modifier.height(unit * 56))
                Column(Modifier.fillMaxSize().verticalScroll(scroll).testTag("work-archive-scroll")) {
                    if (cover.isNotBlank()) {
                        ArchiveImage(
                            "$entryId:work:${work.id}", cover, "作品发行封面",
                            Modifier.padding(start = inset, top = unit * 16, end = inset)
                                .fillMaxWidth().aspectRatio(ratio).testTag("work-archive-cover"),
                            fallback = art?.imageUrl, credit = art?.imageCredit?.replace(Regex("https?://\\S+"), "")?.trim(' ', '·'),
                            onSources = { sources = true },
                            thumbnail = transitionThumbnail,
                            sleeve = true,
                        )
                    }
                    Column(Modifier.fillMaxWidth().padding(horizontal = unit * 22).padding(top = 22.dp, bottom = 30.dp)) {
                        SwissEyebrow("WORK / MUSIC & PLACES")
                        Text(
                            work.title, Modifier.padding(top = 8.dp, bottom = 4.dp).testTag("work-archive-title"),
                            fontSize = 28.sp, lineHeight = 37.sp, fontWeight = FontWeight.SemiBold, color = RoomColors.Ink,
                        )
                        val artistLine = listOf(work.artist, releaseTitle).filter(String::isNotBlank).joinToString(" · ")
                        if (artistLine.isNotEmpty()) Text(artistLine, fontSize = 12.sp, color = RoomColors.Muted)
                        if (reason.isNotBlank()) {
                            Column(
                                Modifier.fillMaxWidth().padding(top = 18.dp).drawBehind {
                                    drawRect(RoomColors.Red, size = Size(2.dp.toPx(), size.height))
                                }.padding(start = 12.dp, top = 3.dp, bottom = 3.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                SwissEyebrow("收录原因")
                                Text(reason, fontSize = 14.sp, lineHeight = 22.sp, color = RoomColors.Ink)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth()) {
                            ArchiveFavoriteAction(favorite, onFavorite, Modifier.testTag("work-archive-favorite"))
                            ArchiveSearchAction(onSearch, Modifier.testTag("work-archive-search"))
                        }
                        if (onPerform != null) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp)
                                    .height(46.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Color(0xFF1A1E1C))
                                    .drawBehind {
                                        drawRect(RoomColors.Red, size = Size(3.dp.toPx(), size.height))
                                    }
                                    .clickable(role = Role.Button, onClickLabel = "演奏${work.title}", onClick = onPerform)
                                    .padding(start = 16.dp, end = 14.dp)
                                    .testTag("work-archive-perform"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "开始演奏",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFFF8F6F0),
                                )
                                Text(
                                    text = "PLAY →",
                                    fontSize = 11.sp,
                                    letterSpacing = 1.6.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFFC9C3B8),
                                )
                            }
                        }
                        ReleaseSectionTitle("关联地点", "PLACES")
                        places.forEach { place ->
                            ReleaseArchiveRow(place.name, place.relations, { onPlace(place.id) }) {
                                place.photo?.let { PlacePhotoThumbnail(place.id, it, Modifier.size(46.dp).coverShadow()) }
                            }
                        }
                        SwissTextButton({ sources = true }) { Text("资料来源 ↗", color = RoomColors.Muted) }
                    }
                }
            }
        }
    }
    if (sources) SwissArtworkSources(art, work.title, work.places) { sources = false }
}
