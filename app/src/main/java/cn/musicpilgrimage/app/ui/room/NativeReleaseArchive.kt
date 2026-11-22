package cn.musicpilgrimage.app.ui.room

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.musicpilgrimage.app.data.MusicRelease
import cn.musicpilgrimage.app.data.MusicWork
import cn.musicpilgrimage.app.ui.CoverTransitionCoordinator
import cn.musicpilgrimage.app.ui.InPlacePhotoViewerStandalone
import cn.musicpilgrimage.app.ui.LocalCoverCoordinator
import cn.musicpilgrimage.app.ui.LocalPhotoViewerCoordinator
import cn.musicpilgrimage.app.ui.LocalRouteEntryId
import cn.musicpilgrimage.app.ui.LocalRouteIsCurrent
import cn.musicpilgrimage.app.ui.PhotoViewerRequest
import cn.musicpilgrimage.app.ui.RELEASE_COVER_SIDE_INSET

@Composable
internal fun NativeReleaseArchive(
    release: MusicRelease,
    favorite: Boolean,
    onFavorite: () -> Unit,
    onWork: (MusicWork) -> Unit,
    onPlace: (String) -> Unit,
    onBack: () -> Unit,
    onSearch: () -> Unit,
) {
    val context = LocalContext.current
    val entryId = LocalRouteEntryId.current
    val isCurrent by rememberUpdatedState(LocalRouteIsCurrent.current)
    val coordinator = LocalCoverCoordinator.current
    val viewer = LocalPhotoViewerCoordinator.current
    val orientation = LocalConfiguration.current.orientation
    val art = release.artwork
    val model = remember(context, art) { releaseCoverModel(context, art) }
    val coverKey = "release:${release.id}"
    val route = "album/${Uri.encode(release.id)}"
    val identity = "album:${release.id}"
    val coverId = "$entryId:$coverKey"
    val ratio = (art?.aspectRatio ?: 1f).coerceIn(.35f, 3f)
    val scroll = rememberScrollState()
    val entering = coordinator?.activeMorph?.takeIf {
        it.mode == CoverTransitionCoordinator.Mode.ENTERING && it.key == coverKey &&
            (it.targetNavEntryId == null || it.targetNavEntryId == entryId)
    }
    // Retain the exact decoded artwork through handoff, even if the shelf is disposed.
    var pinnedCover by remember(entryId, model) {
        mutableStateOf(entering?.takeIf { it.model == model }?.coverBitmap)
    }
    var coverBounds by remember(entryId) { mutableStateOf<Rect?>(null) }
    var imageReady by remember(model) { mutableStateOf(pinnedCover != null) }
    var retry by remember(model) { mutableIntStateOf(0) }
    var failed by remember(model, retry) { mutableStateOf(false) }
    var sources by rememberSaveable(release.id) { mutableStateOf(false) }
    var localPhoto by remember { mutableStateOf<PhotoViewerRequest?>(null) }
    val media = remember(context) { SwissDetailMedia(context) }
    val places = remember(release.places, media) { release.places.map(media::archiveLink) }
    val atTop by remember(scroll) { derivedStateOf { scroll.value == 0 } }
    val hidden = coordinator?.shouldHoldHeroHidden(coverKey, route, entryId) == true || viewer?.hiddenPhotoId == coverId

    LaunchedEffect(entering?.generation, coverBounds, imageReady, failed, atTop, orientation) {
        if (!isCurrent()) return@LaunchedEffect
        coordinator?.reportDestinationHero(
            route = route,
            key = coverKey,
            identity = identity,
            navEntryId = entryId,
            heroRectInRoot = coverBounds.takeIf { atTop || entering != null },
            orientation = orientation,
            firstPaint = imageReady,
            failed = failed,
            expectedGeneration = entering?.generation,
            nativeDestination = true,
        )
    }

    RoomWall(Modifier.fillMaxSize().testTag("native-release-archive")) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val unit = maxWidth / 360
            val inset = unit * RELEASE_COVER_SIDE_INSET
            val collapseAfter = with(LocalDensity.current) {
                (if (model == null) 60.dp else (maxWidth - inset * 2) / ratio + 110.dp).roundToPx()
            }
            val collapsed by remember(scroll,collapseAfter) { derivedStateOf { scroll.value >= collapseAfter } }
            Column(Modifier.fillMaxSize()) {
                Spacer(Modifier.height(unit * 32))
                RoomHeader(
                    if (collapsed) release.title else "发行档案",
                    onBack = onBack,
                    onSpace = {},
                    modifier = Modifier.height(unit * 56).testTag("release-archive-header"),
                )
                Column(Modifier.fillMaxSize().verticalScroll(scroll).testTag("release-archive-scroll")) {
                    if (model != null) {
                        Spacer(Modifier.height(unit * 16))
                        Box(Modifier.padding(horizontal = inset).fillMaxWidth().aspectRatio(ratio)) {
                            key(retry) {
                                RoomCover(
                                    artwork = art,
                                    title = release.title,
                                    modifier = Modifier.fillMaxSize()
                                        .onGloballyPositioned { coverBounds = it.boundsInRoot() }
                                        .drawWithContent {
                                            if (coordinator?.shouldHoldHeroHidden(coverKey, route, entryId) != true &&
                                                viewer?.hiddenPhotoId != coverId
                                            ) drawContent()
                                        }
                                        .testTag("release-archive-cover")
                                        .clickable(
                                            enabled = imageReady && !hidden,
                                            role = Role.Button,
                                            onClickLabel = "查看完整发行封面",
                                        ) {
                                            val request = PhotoViewerRequest(
                                                id = coverId,
                                                model = model,
                                                description = release.title,
                                                credit = art?.imageCredit?.replace(Regex("https?://\\S+"), "")?.trim(),
                                                sourceBoundsInRoot = coverBounds.takeIf { atTop },
                                                sourceAspectRatio = ratio,
                                                sourceIsCroppedCover = false,
                                                sourceOrientation = orientation,
                                                onSources = { sources = true },
                                                onClosed = { localPhoto = null },
                                            )
                                            if (viewer != null) viewer.open(request) else localPhoto = request
                                        },
                                    sleeve = true,
                                    archiveResolution = true,
                                    pinnedCover = pinnedCover,
                                    onLoadFailure = { failed = true },
                                ) { loaded, _, bitmap ->
                                    imageReady = loaded
                                    if (bitmap != null) pinnedCover = bitmap
                                }
                            }
                            if (failed) TextButton(
                                onClick = { retry++ },
                                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                            ) { Text("重试封面", color = RoomColors.Ink) }
                        }
                    }
                    Column(Modifier.fillMaxWidth().padding(horizontal = unit * 22).padding(top = 22.dp, bottom = 30.dp)) {
                        val year = art?.releaseYear?.toString().orEmpty()
                        SwissEyebrow(if (year.isBlank()) "RELEASE" else "RELEASE / $year")
                        Text(
                            release.title,
                            Modifier.padding(top = 8.dp,bottom = 4.dp).testTag("release-archive-title"),
                            fontSize = 28.sp, lineHeight = 37.sp, fontWeight = FontWeight.SemiBold, color = RoomColors.Ink,
                        )
                        Text("${release.artist} · 发行封面",fontSize = 12.sp,color = RoomColors.Muted)
                        Spacer(Modifier.height(16.dp))
                        Row(Modifier.fillMaxWidth()) {
                            TextButton(onFavorite, Modifier.weight(1f)) {
                                ArchiveFavoriteIcon(favorite, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(if(favorite) "已收藏" else "收藏",color = RoomColors.Ink)
                            }
                            TextButton(onSearch, Modifier.weight(1f)) {
                                Icon(Icons.Outlined.Search,null,Modifier.size(20.dp),tint = RoomColors.Ink)
                                Spacer(Modifier.width(8.dp))
                                Text("搜索",color = RoomColors.Ink)
                            }
                        }
                        ReleaseSectionTitle("收录作品", "WORKS")
                        release.works.forEachIndexed { index, work ->
                            ReleaseArchiveRow(work.title, "${work.places.size} 处音乐地点", { onWork(work) }) {
                                SwissEyebrow((index + 1).toString().padStart(2, '0'))
                            }
                        }
                        if (release.works.isEmpty()) Text("这张发行从封面出发，收录了下方地点。",color = RoomColors.Muted,fontSize = 13.sp)
                        ReleaseSectionTitle("关联地点", "PLACES")
                        places.forEach { place ->
                            ReleaseArchiveRow(place.name, place.location, { onPlace(place.id) }) {
                                place.photo?.let { PlacePhotoThumbnail(place.id, it, Modifier.size(46.dp).coverShadow()) }
                            }
                        }
                        TextButton({ sources = true }) { Text("资料来源 ↗",color = RoomColors.Muted) }
                    }
                }
            }
        }
    }
    if (sources) SwissArtworkSources(art, release.title) { sources = false }
    localPhoto?.let { InPlacePhotoViewerStandalone(it) }
}

@Composable
internal fun ReleaseSectionTitle(title: String, label: String) {
    Row(Modifier.fillMaxWidth().padding(top = 24.dp,bottom = 10.dp),verticalAlignment = Alignment.CenterVertically) {
        Text(title,Modifier.weight(1f),fontSize = 20.sp,color = RoomColors.Ink)
        SwissEyebrow(label)
    }
    HorizontalDivider(color = RoomColors.Line)
}

@Composable
internal fun ReleaseArchiveRow(title: String, detail: String, onClick: () -> Unit, leading: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button,onClick = onClick).padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Column(Modifier.weight(1f),verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title,fontSize = 15.sp,color = RoomColors.Ink,fontWeight = FontWeight.Medium)
            if (detail.isNotBlank()) Text(detail,fontSize = 12.sp,color = RoomColors.Muted)
        }
        Icon(Icons.Outlined.ChevronRight,null,Modifier.size(17.dp),tint = RoomColors.Muted)
    }
    HorizontalDivider(color = RoomColors.Line)
}
