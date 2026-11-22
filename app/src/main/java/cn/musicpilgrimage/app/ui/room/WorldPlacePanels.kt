package cn.musicpilgrimage.app.ui.room

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.musicpilgrimage.app.data.Place
import cn.musicpilgrimage.app.data.Scene
import cn.musicpilgrimage.app.data.geographicParts
import cn.musicpilgrimage.app.data.matchesMusicQuery
import cn.musicpilgrimage.app.data.musicQueryWords
import cn.musicpilgrimage.app.data.mapScene
import cn.musicpilgrimage.app.data.musicTerm
import cn.musicpilgrimage.app.ui.AppBottomSheet
import cn.musicpilgrimage.app.ui.LocalPhysicalMotion
import cn.musicpilgrimage.app.ui.appMotionEasing
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal enum class WorldFocusKind { Pin, List, Street }
internal data class WorldFocus(val placeId: String, val kind: WorldFocusKind, val token: Long)
internal data class WorldSelection(val placeId: String, val summaryOpen: Boolean)

internal data class WorldPlaceEntry(
    val place: Place,
    val name: String,
    val location: String,
    val scene: Scene?,
    val terms: List<String>,
)

internal fun worldPlaceEntry(context: Context, place: Place): WorldPlaceEntry {
    val name = PlaceEditorialCopy.name(context, place)
    val parts = geographicParts(place.city, place.country)
    val scene = place.mapScene()
    val terms = listOf(name, place.name, place.city, place.country) + parts +
        place.scenes.filter(Scene::publiclyVisible).flatMap { listOf(it.work, it.artistName) }
    return WorldPlaceEntry(place, name, parts.filterNot { it == name }.joinToString(" · "),
        scene, terms.distinct().map(::musicTerm))
}

@Composable
internal fun WorldPlaceChooser(
    title: String,
    entries: List<WorldPlaceEntry>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    var query by rememberSaveable(title) { mutableStateOf("") }
    val words = remember(query) { musicQueryWords(query) }
    val matches = remember(entries, words) {
        if (words.isEmpty()) entries else entries.filter { matchesMusicQuery(words, it.terms) }
    }
    val list = rememberLazyListState()
    LaunchedEffect(words) { if (matches.isNotEmpty()) list.scrollToItem(0) }
    AppBottomSheet(visible = true, busy = false, onDismissRequest = onDismiss) { _, _ ->
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        fun closeKeyboard() {
            focus.clearFocus(force = true)
            keyboard?.hide()
        }
        Column(Modifier.fillMaxWidth().testTag("world-place-chooser").padding(horizontal = 23.dp).padding(bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), fontSize = 23.sp, fontWeight = FontWeight.Medium, color = RoomColors.Ink)
                IconButton({
                    closeKeyboard()
                    onDismiss()
                }, Modifier.testTag("world-chooser-close")) {
                    Icon(Icons.Outlined.Close, "关闭地点列表", tint = RoomColors.Ink)
                }
            }
            OutlinedTextField(
                query, { query = it.take(100) },
                Modifier.fillMaxWidth().testTag("world-place-query"),
                singleLine = true, label = { Text("搜索地点、城市、作品") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { closeKeyboard() }),
                trailingIcon = if (query.isNotEmpty()) ({
                    IconButton({ query = "" }) { Icon(Icons.Outlined.Close, "清空搜索", tint = RoomColors.Muted) }
                }) else null,
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), state = list) {
                if (matches.isEmpty()) item {
                    Text(if (query.isBlank()) "暂时没有可浏览的地点" else "没有找到匹配的地点",
                        Modifier.padding(vertical = 20.dp), color = RoomColors.Muted, fontSize = 14.sp)
                }
                items(matches, key = { it.place.id }) { entry ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("world-place-option-${entry.place.id}")
                            .clickable(role = Role.Button) {
                                closeKeyboard()
                                onSelect(entry.place.id)
                            }.padding(vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(entry.name, color = RoomColors.Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            val subtitle = listOf(entry.location, entry.scene?.work.orEmpty()).filter(String::isNotBlank).joinToString(" · ")
                            if (subtitle.isNotBlank()) Text(subtitle, color = RoomColors.Muted, fontSize = 12.sp)
                        }
                        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(17.dp), tint = RoomColors.Muted)
                    }
                    HorizontalDivider(color = RoomColors.Line)
                }
            }
        }
    }
}

@Composable
internal fun WorldPlaceSummary(
    entry: WorldPlaceEntry?,
    modifier: Modifier,
    favorites: Set<String>,
    favoriteBusy: Boolean,
    interactive: Boolean,
    onDismiss: () -> Unit,
    onReopen: (String) -> Unit,
    onMeasure: (String, Int) -> Unit,
    onFavorite: (String) -> Unit,
    onAround: (String) -> Unit,
    onAdd: (Place) -> Unit,
    onDetail: (String) -> Unit,
) {
    val motion = LocalPhysicalMotion.current
    val density = LocalDensity.current
    val startingOffset = with(density) { 18.dp.toPx() }
    val closeThreshold = with(density) { 72.dp.toPx() }
    val closeSpeed = with(density) { 550.dp.toPx() }
    val scope = rememberCoroutineScope()
    var retained by remember { mutableStateOf(entry) }
    var height by remember { mutableIntStateOf(0) }
    var offset by remember { mutableFloatStateOf(if (entry != null && motion) startingOffset else 0f) }
    var dragging by remember { mutableStateOf(false) }
    var animation by remember { mutableStateOf<Job?>(null) }
    var velocity by remember { mutableFloatStateOf(0f) }
    val selectedId = entry?.place?.id
    val active = selectedId != null
    val actionsEnabled = active && interactive
    val dismiss by rememberUpdatedState(onDismiss)
    val reopen by rememberUpdatedState(onReopen)
    LaunchedEffect(selectedId, motion) {
        animation?.cancel()
        if (entry != null) {
            if (retained == null && motion) offset = startingOffset
            retained = entry
            if (!dragging) {
                animation = scope.launch {
                    if (motion) Animatable(offset).animateTo(0f, spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow)) {
                        offset = value.coerceAtLeast(0f)
                    } else offset = 0f
                }
            }
        } else if (retained != null && !dragging) {
            animation = scope.launch {
                if (motion) Animatable(offset).animateTo(height.toFloat().coerceAtLeast(startingOffset),
                    tween(if (velocity > closeSpeed) 120 else 165, easing = appMotionEasing)) { offset = value }
                retained = null
                velocity = 0f
            }
        }
    }
    val presented = entry ?: retained?.takeIf { motion } ?: return
    val drag = rememberDraggableState { delta ->
        offset = (offset + delta).coerceIn(0f, height.coerceAtLeast(1).toFloat())
    }
    Column(
        modifier.fillMaxWidth()
            .graphicsLayer { translationY = if (motion || dragging) offset else 0f }
            .then(if (active) Modifier.testTag("world-place-summary") else Modifier.clearAndSetSemantics {})
            .draggable(
                drag, Orientation.Vertical, enabled = interactive,
                startDragImmediately = !active && animation?.isActive == true,
                onDragStarted = {
                    animation?.cancel()
                    dragging = true
                    velocity = 0f
                },
                onDragStopped = { speed ->
                    dragging = false
                    velocity = speed
                    val shouldClose = (offset >= closeThreshold.coerceAtMost(height * .35f) && speed >= -closeSpeed) || speed > closeSpeed
                    if (shouldClose && active) dismiss()
                    else if (!shouldClose && !active) reopen(presented.place.id)
                    else {
                        animation?.cancel()
                        animation = scope.launch {
                            if (motion) Animatable(offset).animateTo(
                                if (shouldClose) height.toFloat() else 0f,
                                spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow),
                                initialVelocity = speed,
                            ) { offset = value.coerceAtLeast(0f) } else offset = 0f
                            if (shouldClose) { retained = null; velocity = 0f }
                        }
                    }
                },
            ).background(RoomColors.Paper),
    ) {
        key(presented.place.id) {
            Column(Modifier.fillMaxWidth().onSizeChanged {
                height = it.height
                if (active) onMeasure(presented.place.id, it.height)
            }) {
                HorizontalDivider(color = RoomColors.Line)
                Column(Modifier.fillMaxWidth().padding(horizontal = 23.dp).padding(bottom = 13.dp)) {
                    Box(Modifier.fillMaxWidth().height(16.dp).testTag("world-summary-handle"), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(width = 32.dp, height = 3.dp).background(RoomColors.Muted.copy(alpha = .34f)))
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            SwissEyebrow(presented.scene?.kindLabel ?: "音乐地点")
                            Text(presented.name, Modifier.testTag("world-summary-title"),
                                color = RoomColors.Ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, lineHeight = 30.sp)
                            if (presented.location.isNotBlank()) Text(presented.location, color = RoomColors.Muted, fontSize = 14.sp)
                        }
                        IconButton(onDismiss, Modifier.testTag("world-summary-close"), enabled = actionsEnabled) {
                            Icon(Icons.Outlined.Close, "收起地点摘要", tint = RoomColors.Muted)
                        }
                    }
                    WorldSummaryPreview(presented)
                    HorizontalDivider(color = RoomColors.Line)
                    Spacer(Modifier.height(10.dp))
                    val id = presented.place.id
                    val favorite = id in favorites
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ onFavorite(id) }, Modifier.size(48.dp).border(1.dp, RoomColors.Line)
                            .testTag("world-summary-favorite").semantics { stateDescription = if (favorite) "已收藏" else "未收藏" },
                            enabled = actionsEnabled && !favoriteBusy) {
                            ArchiveFavoriteIcon(favorite,
                                if (favorite) "取消收藏地点" else "收藏地点",
                                Modifier.size(19.dp))
                        }
                        WorldSummaryAction("查看周边", Modifier.weight(1f).testTag("world-summary-around"), actionsEnabled) { onAround(id) }
                        WorldSummaryAction("＋ 加入行程", Modifier.weight(1.1f).testTag("world-summary-add"), actionsEnabled) { onAdd(presented.place) }
                        WorldSummaryAction("查看详情", Modifier.weight(1.1f).testTag("world-summary-detail"), actionsEnabled, primary = true) { onDetail(id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorldSummaryPreview(entry: WorldPlaceEntry) {
    val context = LocalContext.current
    val scene = entry.scene
    val sceneSources = remember(context, scene) {
        if (scene?.imageApproved == true) {
            listOfNotNull(SwissDetailMedia(context).scene(scene), scene.thumbnailUrl, scene.imageUrl)
                .filter(String::isNotBlank).distinct()
        } else emptyList()
    }
    var sceneUnavailable by rememberSaveable(scene?.id, sceneSources) { mutableStateOf(false) }
    val useScene = sceneSources.isNotEmpty() && !sceneUnavailable
    val photo = remember(context, entry.place, useScene) {
        if (useScene) null else ArchivePhotography.photo(context, entry.place)
    }
    var photoUnavailable by rememberSaveable(photo?.asset) { mutableStateOf(false) }
    val sources = if (useScene) sceneSources else photo?.takeUnless { photoUnavailable }?.previewSources.orEmpty()
    if (scene == null && sources.isEmpty()) return

    val initialRatio = scene?.aspectRatio ?: photo?.let { it.width.toFloat() / it.height.coerceAtLeast(1) } ?: 1f
    var ratio by rememberSaveable(scene?.id, sceneSources) { mutableFloatStateOf(initialRatio.coerceIn(.65f, 1.85f)) }
    val timestamp = scene?.timestampSeconds?.let { "${(it / 60).toString().padStart(2, '0')}:${(it % 60).toString().padStart(2, '0')}" }
    val credit = listOfNotNull(scene?.artistName, scene?.archiveMediaLabel()?.takeIf { useScene }, timestamp?.takeIf { useScene })
        .filter(String::isNotBlank).joinToString(" · ")
    HorizontalDivider(Modifier.padding(top = 8.dp), color = RoomColors.Line)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (sources.isNotEmpty()) {
            val width = (78f * ratio).coerceAtMost(120f)
            val height = (width / ratio).coerceAtLeast(56f)
            ArchivePreviewImage(
                sources, Modifier.size(width.dp, height.dp).clipToBounds().background(RoomColors.Wall),
                ContentScale.Fit,
                loadedTag = if (useScene) "world-summary-art" else "world-summary-place-photo",
                onLoaded = { drawable ->
                    if (useScene && drawable.intrinsicWidth > 1 && drawable.intrinsicHeight > 1) {
                        ratio = (drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight).coerceIn(.65f, 1.85f)
                    }
                },
                onUnavailable = { if (useScene) sceneUnavailable = true else photoUnavailable = true },
            )
        }
        scene?.let {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (it.work.isNotBlank()) Text("《${it.work}》", color = RoomColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                if (credit.isNotBlank()) Text(credit, Modifier.testTag("world-summary-media-credit"), color = RoomColors.Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun WorldSummaryAction(label: String, modifier: Modifier, enabled: Boolean, primary: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier.heightIn(min = 48.dp)
            .then(if (primary) Modifier.background(RoomColors.Ink) else Modifier.border(1.dp, RoomColors.Line))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        val color = if (primary) RoomColors.Paper else RoomColors.Ink
        Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        if (primary) Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.padding(start = 2.dp).size(15.dp), tint = color)
    }
}
