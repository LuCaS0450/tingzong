package cn.musicpilgrimage.app.ui.room

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import cn.musicpilgrimage.app.data.musicQueryWords
import cn.musicpilgrimage.app.data.musicTerm
import cn.musicpilgrimage.app.performance.AudioDownloadState
import cn.musicpilgrimage.app.performance.PerformanceCatalogItem
import cn.musicpilgrimage.app.ui.LocalPhysicalMotion
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.ceil
import kotlin.math.roundToInt

private val WhiteKeyHeight = 76.dp
private val BlackKeyHeight = 30.dp
private const val BlackKeyWidthFraction = 0.37f

internal object PianoWallTextures {
    private val cache = mutableMapOf<String, ImageBitmap?>()

    @Synchronized
    fun load(context: Context, path: String): ImageBitmap? = cache.getOrPut(path) {
        runCatching {
            context.assets.open(path).use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
        }.getOrNull()
    }
}

private fun hasBlackKeyBelow(index: Int, totalCount: Int): Boolean {
    if (index < 0 || index >= totalCount - 1) return false
    return (index % 7) in setOf(0, 1, 3, 4, 5)
}

/** Tile a seamless bitmap at a fixed logical density size so textures never stretch across keys. */
internal fun Modifier.tiledMaterial(
    image: ImageBitmap?,
    tileSize: Dp,
    fallback: Color,
    overlay: DrawScope.() -> Unit = {},
): Modifier = drawWithCache {
    val brush = image?.let { bmp ->
        val tilePx = tileSize.toPx().coerceAtLeast(1f)
        val shader = ImageShader(bmp, TileMode.Repeated, TileMode.Repeated)
        shader.setLocalMatrix(Matrix().apply {
            setScale(tilePx / bmp.width.coerceAtLeast(1), tilePx / bmp.height.coerceAtLeast(1))
        })
        ShaderBrush(shader)
    }
    onDrawBehind {
        if (brush != null) drawRect(brush) else drawRect(fallback)
        overlay()
    }
}

/** Three-tier 11 o'clock directional + ambient cast shadow beneath the Bauhaus piano case. */
private fun Modifier.pianoCaseShadow(cornerRadius: Dp): Modifier = drawWithCache {
    val rPx = cornerRadius.toPx()
    val blurMain = 26.dp.toPx()
    val dxMain = 7.dp.toPx()
    val dyMain = 14.dp.toPx()
    val pad = ceil(blurMain + dyMain + 6.dp.toPx()).toInt()
    val w = ceil(size.width).toInt().coerceAtLeast(1)
    val h = ceil(size.height).toInt().coerceAtLeast(1)
    val mask = RoomShadowCache.image("piano-case:$w:$h:$density") {
        val bitmap = Bitmap.createBitmap(w + pad * 2, h + pad * 2, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun layer(dx: Float, dy: Float, blur: Float, color: Int) {
            paint.color = color
            paint.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
            canvas.drawRoundRect(
                pad + dx, pad + dy, pad + dx + size.width, pad + dy + size.height,
                rPx, rPx, paint
            )
        }
        layer(dxMain, dyMain, blurMain, android.graphics.Color.argb(38, 24, 29, 26))
        layer(3.dp.toPx(), 5.dp.toPx(), 8.dp.toPx(), android.graphics.Color.argb(61, 22, 26, 24))
        layer(0f, 2.dp.toPx(), 3.dp.toPx(), android.graphics.Color.argb(36, 26, 31, 28))
        bitmap.asImageBitmap()
    }
    onDrawBehind { drawImage(mask, topLeft = Offset(-pad.toFloat(), -pad.toFloat())) }
}

/** Bauhaus vertical piano keyboard wall matching the approved Play Web prototype. */
@Composable
internal fun RoomBauhausPianoWall(
    entries: List<PerformanceCatalogItem>,
    loaded: Boolean,
    browse: RoomBrowseState,
    downloads: Map<String, AudioDownloadState>,
    onSong: (String) -> Unit,
    onRetry: () -> Unit,
    catalogError: String? = null,
    onRefreshCatalog: () -> Unit = {},
    filterExpanded: Boolean = false,
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    selectedArtist: String? = null,
    onSelectArtist: (String?) -> Unit = {},
) {
    val context = LocalContext.current
    val ivory = remember(context) { PianoWallTextures.load(context, "design/music-space/piano-ivory.jpg") }
    val ebony = remember(context) { PianoWallTextures.load(context, "design/music-space/piano-ebony.jpg") }
    val metal = remember(context) { PianoWallTextures.load(context, "design/materials/aluminum.webp") }
    val listState = rememberLazyListState(browse.playIndex.coerceAtLeast(0), browse.playOffset.coerceAtLeast(0))

    val artists = remember(entries) {
        entries.groupingBy { it.artist }.eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
    }
    val hasActiveFilter = searchQuery.isNotBlank() || selectedArtist != null
    val visibleEntries = remember(entries, searchQuery, selectedArtist) {
        val words = musicQueryWords(searchQuery)
        entries.filter { item ->
            val artistOk = selectedArtist == null || item.artist == selectedArtist
            val queryOk = words.isEmpty() || run {
                val terms = listOf(musicTerm(item.title), musicTerm(item.artist))
                words.all { word -> terms.any { it.contains(word) } }
            }
            artistOk && queryOk
        }
    }

    LaunchedEffect(searchQuery, selectedArtist) {
        if (hasActiveFilter) {
            listState.scrollToItem(0, 0)
        }
    }

    LaunchedEffect(listState, hasActiveFilter) {
        if (!hasActiveFilter) {
            snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                .distinctUntilChanged()
                .collect { (index, offset) -> browse.play(index, offset) }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 15.dp, top = 2.dp, end = 15.dp, bottom = 14.dp)
    ) {
        AnimatedVisibility(
            visible = filterExpanded || hasActiveFilter,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            BauhausPlayFilterTray(
                query = searchQuery,
                onQueryChange = onSearchQueryChange,
                artists = artists,
                selectedArtist = selectedArtist,
                onSelectArtist = onSelectArtist,
                matchedCount = visibleEntries.size,
                totalCount = entries.size,
                hasActiveFilter = hasActiveFilter,
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .pianoCaseShadow(2.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0xFF1A1E1C))
        ) {
            BauhausTopLid(ebony)

            Row(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                SimpleCharcoalCheek(ebony, isLeft = true, Modifier.width(11.dp).fillMaxHeight())

                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clipToBounds()
                        .background(Color(0xFFA4A094))
                ) {
                    LazyColumn(
                        Modifier
                            .fillMaxSize()
                            .testTag("performance-list"),
                        state = listState,
                    ) {
                        if (!loaded && entries.isEmpty()) {
                            item("loading") {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(140.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(Modifier.size(24.dp), color = RoomColors.Ink)
                                }
                            }
                        } else if (loaded && entries.isEmpty()) {
                            item("catalog-retry") {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(76.dp)
                                        .tiledMaterial(ivory, 260.dp, Color(0xFFF8F6F0))
                                        .testTag("performance-catalog-retry")
                                        .clickable(role = Role.Button, onClick = onRefreshCatalog)
                                        .padding(horizontal = 20.dp),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    Text(
                                        text = catalogError ?: "曲库暂未就绪，点此重试",
                                        color = RoomColors.Red,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                        } else if (entries.isNotEmpty() && visibleEntries.isEmpty()) {
                            item("filter-empty") {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(76.dp)
                                        .tiledMaterial(ivory, 260.dp, Color(0xFFF8F6F0))
                                        .clickable(role = Role.Button) {
                                            onSearchQueryChange("")
                                            onSelectArtist(null)
                                        }
                                        .padding(horizontal = 20.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = "未找到匹配曲目",
                                        color = SwissWallMuted,
                                        fontSize = 14.sp,
                                    )
                                    Text(
                                        text = "清除筛选",
                                        color = RoomColors.Red,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                        }
                        itemsIndexed(visibleEntries, key = { _, entry -> entry.id }) { _, entry ->
                            BauhausWhiteKeyRow(
                                entry = entry,
                                ivory = ivory,
                                onClick = { onSong(entry.id) },
                            )
                        }
                        if (downloads.values.any { it.failed }) {
                            item("retry") {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(64.dp)
                                        .tiledMaterial(ivory, 260.dp, Color(0xFFF8F6F0))
                                        .clickable(role = Role.Button, onClick = onRetry)
                                        .padding(horizontal = 20.dp),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    Text("重试未完成曲目", color = RoomColors.Red, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }

                    BlackKeysOverlay(
                        listState = listState,
                        totalEntries = visibleEntries.size,
                        ebony = ebony,
                    )

                    // Stationary recessed cavity shadows cast by the top lid, bottom keyslip and cheek walls.
                    KeybedCavityShadows(Modifier.matchParentSize().zIndex(7f))
                }

                SimpleCharcoalCheek(ebony, isLeft = false, Modifier.width(11.dp).fillMaxHeight())
            }

            BauhausBottomFooter(ebony, metal)
        }
    }
}

@Composable
private fun BauhausPlayFilterTray(
    query: String,
    onQueryChange: (String) -> Unit,
    artists: List<String>,
    selectedArtist: String?,
    onSelectArtist: (String?) -> Unit,
    matchedCount: Int,
    totalCount: Int,
    hasActiveFilter: Boolean,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .testTag("play-wall-filter-tray"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(38.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(RoomColors.Paper)
                .border(1.dp, if (query.isNotEmpty()) RoomColors.Ink else RoomColors.Line, RoundedCornerShape(2.dp))
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Outlined.Search, null, Modifier.size(16.dp), tint = SwissWallMuted)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text("搜索曲名或歌手", fontSize = 13.sp, color = SwissWallMuted, maxLines = 1)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth().testTag("play-wall-search-input"),
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 13.sp, color = RoomColors.Ink),
                    cursorBrush = SolidColor(RoomColors.Red),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                )
            }
            if (hasActiveFilter && totalCount > 0) {
                Text(
                    text = "$matchedCount/$totalCount",
                    fontSize = 11.sp,
                    color = SwissWallMuted,
                    letterSpacing = 0.5.sp,
                )
            }
            if (hasActiveFilter) {
                Box(
                    Modifier
                        .size(24.dp)
                        .clickable(role = Role.Button, onClickLabel = "清除筛选") {
                            onQueryChange("")
                            onSelectArtist(null)
                            keyboard?.hide()
                        }
                        .testTag("play-wall-filter-clear"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Close, "清除筛选", Modifier.size(15.dp), tint = RoomColors.Ink)
                }
            }
        }

        if (artists.size > 1) {
            LazyRow(
                Modifier
                    .fillMaxWidth()
                    .testTag("play-wall-artist-chips"),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item("all") {
                    BauhausArtistChip(
                        label = "全部",
                        selected = selectedArtist == null,
                        onClick = { onSelectArtist(null) },
                    )
                }
                items(artists, key = { it }) { artist ->
                    BauhausArtistChip(
                        label = artist,
                        selected = selectedArtist == artist,
                        onClick = { onSelectArtist(if (selectedArtist == artist) null else artist) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BauhausArtistChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(if (selected) Color(0xFF1A1E1C) else RoomColors.Paper)
            .then(
                if (selected) {
                    Modifier.drawBehind {
                        val barH = 1.5.dp.toPx()
                        drawRect(RoomColors.Red, topLeft = Offset(0f, size.height - barH), size = Size(size.width, barH))
                    }
                } else {
                    Modifier.border(1.dp, RoomColors.Line, RoundedCornerShape(2.dp))
                }
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) Color(0xFFF8F6F0) else SwissWallMuted,
            maxLines = 1,
        )
    }
}

@Composable
private fun BauhausTopLid(ebony: ImageBitmap?) {
    Column(
        Modifier
            .fillMaxWidth()
            .height(42.dp)
            .zIndex(15f)
            .tiledMaterial(ebony, 240.dp, Color(0xFF202422)) {
                drawRect(
                    Brush.linearGradient(
                        0f to Color.White.copy(alpha = 0.13f),
                        0.28f to Color.White.copy(alpha = 0.03f),
                        1f to Color.Black.copy(alpha = 0.28f),
                        start = Offset.Zero,
                        end = Offset(size.width * 0.4f, size.height),
                    )
                )
                val stroke = 1.dp.toPx()
                drawLine(Color.White.copy(alpha = 0.22f), Offset.Zero, Offset(size.width, 0f), stroke)
                drawLine(Color.White.copy(alpha = 0.22f), Offset.Zero, Offset(0f, size.height), stroke)
                drawLine(Color.Black.copy(alpha = 0.50f), Offset(size.width - stroke * 0.5f, 0f), Offset(size.width - stroke * 0.5f, size.height), stroke)
            }
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 10.dp),
        ) {
            val scaleWidth = maxWidth * BlackKeyWidthFraction
            Row(
                Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MicroScrew()
                LidAcousticVent(Modifier.weight(1f).height(22.dp))
                LidScaleIndex(Modifier.width(scaleWidth).height(22.dp))
                MicroScrew()
            }
        }
        BauhausCrimsonSeal(horizontalPadding = 11.dp)
    }
}

/** Recessed dark seal strip with a 1.5dp anodized crimson hairline along the keybed top edge. */
@Composable
internal fun BauhausCrimsonSeal(modifier: Modifier = Modifier, horizontalPadding: Dp = 11.dp) {
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(Color(0xFF141715))
            .drawWithCache {
                val hairline = 1.dp.toPx()
                val crimsonH = 1.5.dp.toPx()
                val padX = horizontalPadding.toPx()
                val crimsonBrush = Brush.horizontalGradient(
                    0f to Color(0xFF742018),
                    0.24f to Color(0xFFBD382B),
                    0.50f to Color(0xFFDC4D3F),
                    0.76f to Color(0xFFBD382B),
                    1f to Color(0xFF742018),
                    startX = padX,
                    endX = (size.width - padX).coerceAtLeast(padX + 1f),
                )
                onDrawBehind {
                    drawLine(Color.White.copy(alpha = 0.07f), Offset.Zero, Offset(size.width, 0f), hairline)
                    drawRect(
                        crimsonBrush,
                        topLeft = Offset(padX, size.height - crimsonH),
                        size = Size((size.width - padX * 2).coerceAtLeast(0f), crimsonH),
                    )
                }
            }
    )
}

@Composable
internal fun LidAcousticVent(modifier: Modifier = Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(1.5.dp))) {
        drawRect(Color(0xFF0E100F).copy(alpha = 0.68f))
        drawRect(
            Brush.verticalGradient(
                listOf(Color.Black.copy(alpha = 0.82f), Color.Transparent),
                startY = 0f,
                endY = 3.5.dp.toPx(),
            )
        )
        drawLine(
            Color.White.copy(alpha = 0.09f),
            Offset(0f, size.height - 0.5.dp.toPx()),
            Offset(size.width, size.height - 0.5.dp.toPx()),
            1.dp.toPx(),
        )
        val padX = 6.dp.toPx()
        val padY = 3.dp.toPx()
        val slotW = (size.width - padX * 2).coerceAtLeast(0f)
        val slotH = 1.5.dp.toPx()
        val lipH = 0.6.dp.toPx()
        val usableH = (size.height - padY * 2 - slotH).coerceAtLeast(1f)
        for (i in 0 until 6) {
            val y = padY + usableH * (i / 5f)
            drawRect(Color(0xFF0A0C0B), topLeft = Offset(padX, y), size = Size(slotW, slotH))
            drawRect(Color(0xFFA4AEAE).copy(alpha = 0.18f), topLeft = Offset(padX, y + slotH), size = Size(slotW, lipH))
        }
    }
}

@Composable
private fun LidScaleIndex(modifier: Modifier = Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(1.5.dp))) {
        drawRect(
            Brush.verticalGradient(
                listOf(Color(0xFF141716).copy(alpha = 0.82f), Color(0xFF1D2220).copy(alpha = 0.72f))
            )
        )
        drawRect(
            Brush.verticalGradient(
                listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent),
                startY = 0f,
                endY = 3.dp.toPx(),
            )
        )
        drawLine(
            Color.White.copy(alpha = 0.09f),
            Offset(0f, size.height - 0.5.dp.toPx()),
            Offset(size.width, size.height - 0.5.dp.toPx()),
            1.dp.toPx(),
        )
        val startX = 8.dp.toPx()
        val jewelDiameter = 3.5.dp.toPx()
        val jewelCenter = Offset(size.width - 4.dp.toPx() - jewelDiameter * 0.5f, size.height * 0.5f)
        val endX = (jewelCenter.x - 7.dp.toPx()).coerceAtLeast(startX + 1f)
        val baselineY = size.height * 0.5f + 4.dp.toPx()
        val tickW = 1.dp.toPx()
        for (i in 0 until 9) {
            val major = i % 4 == 0
            val tickH = (if (major) 7.dp else 4.dp).toPx()
            val x = startX + (endX - startX) * (i / 8f)
            drawRect(
                color = if (major) Color(0xFFE2E8E8).copy(alpha = 0.62f) else Color(0xFFBCC3C4).copy(alpha = 0.34f),
                topLeft = Offset(x, baselineY - tickH),
                size = Size(tickW, tickH),
            )
        }
        drawCircle(Color(0xFFBD382B).copy(alpha = 0.32f), radius = jewelDiameter * 1.15f, center = jewelCenter)
        drawCircle(
            Brush.radialGradient(
                listOf(Color(0xFFE36154), Color(0xFFBD382B), Color(0xFF6E1A12)),
                center = Offset(jewelCenter.x - jewelDiameter * 0.15f, jewelCenter.y - jewelDiameter * 0.15f),
                radius = jewelDiameter * 0.6f,
            ),
            radius = jewelDiameter * 0.5f,
            center = jewelCenter,
        )
    }
}

@Composable
internal fun MicroScrew() {
    Canvas(Modifier.size(5.dp)) {
        val r = size.minDimension * 0.5f
        drawCircle(Color(0xFF0A0C0B).copy(alpha = 0.72f), radius = r + 0.9.dp.toPx())
        drawCircle(
            Brush.radialGradient(
                0f to Color(0xFFEEF2F1),
                0.52f to Color(0xFF8E9798),
                1f to Color(0xFF3A4042),
                center = Offset(size.width * 0.35f, size.height * 0.30f),
                radius = r * 1.15f,
            ),
            radius = r,
        )
        drawLine(
            Color(0xFF141816).copy(alpha = 0.68f),
            Offset(size.width * 0.22f, size.height * 0.68f),
            Offset(size.width * 0.78f, size.height * 0.32f),
            0.8.dp.toPx(),
        )
    }
}

@Composable
internal fun SimpleCharcoalCheek(ebony: ImageBitmap?, isLeft: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .zIndex(8f)
            .tiledMaterial(ebony, 180.dp, Color(0xFF1E2220)) {
                drawRect(
                    if (isLeft) {
                        Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.09f), Color.Black.copy(alpha = 0.24f)))
                    } else {
                        Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.05f), Color.Black.copy(alpha = 0.36f)))
                    }
                )
                val stroke = 1.dp.toPx()
                if (isLeft) {
                    drawLine(Color.White.copy(alpha = 0.20f), Offset(stroke * 0.5f, 0f), Offset(stroke * 0.5f, size.height), stroke)
                    drawLine(Color.Black.copy(alpha = 0.72f), Offset(size.width - stroke * 0.5f, 0f), Offset(size.width - stroke * 0.5f, size.height), stroke)
                } else {
                    drawLine(Color.White.copy(alpha = 0.09f), Offset(stroke * 0.5f, 0f), Offset(stroke * 0.5f, size.height), stroke)
                    drawLine(Color.Black.copy(alpha = 0.60f), Offset(size.width - stroke * 0.5f, 0f), Offset(size.width - stroke * 0.5f, size.height), stroke)
                }
            }
    )
}

@Composable
internal fun KeybedCavityShadows(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val topH = 10.dp.toPx()
        val botH = 8.dp.toPx()
        val leftW = 7.dp.toPx()
        val rightW = 5.dp.toPx()
        drawRect(
            Brush.verticalGradient(
                0f to Color(0xFF0E110F).copy(alpha = 0.36f),
                0.55f to Color(0xFF0E110F).copy(alpha = 0.10f),
                1f to Color.Transparent,
                startY = 0f,
                endY = topH,
            ),
            size = Size(size.width, topH),
        )
        drawRect(
            Brush.verticalGradient(
                listOf(Color.Transparent, Color(0xFF0E110F).copy(alpha = 0.28f)),
                startY = size.height - botH,
                endY = size.height,
            ),
            topLeft = Offset(0f, size.height - botH),
            size = Size(size.width, botH),
        )
        drawRect(
            Brush.horizontalGradient(
                0f to Color(0xFF0E1210).copy(alpha = 0.34f),
                0.55f to Color(0xFF0E1210).copy(alpha = 0.08f),
                1f to Color.Transparent,
                startX = 0f,
                endX = leftW,
            ),
            size = Size(leftW, size.height),
        )
        drawRect(
            Brush.horizontalGradient(
                listOf(Color.Transparent, Color(0xFF0E1210).copy(alpha = 0.24f)),
                startX = size.width - rightW,
                endX = size.width,
            ),
            topLeft = Offset(size.width - rightW, 0f),
            size = Size(rightW, size.height),
        )
    }
}

@Composable
private fun BauhausWhiteKeyRow(
    entry: PerformanceCatalogItem,
    ivory: ImageBitmap?,
    onClick: () -> Unit,
) {
    val motion = LocalPhysicalMotion.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val dipPx = with(LocalDensity.current) { 1.5.dp.toPx() }

    Box(
        Modifier
            .fillMaxWidth()
            .height(WhiteKeyHeight)
            .testTag("performance-song-${entry.id}")
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClickLabel = "演奏${entry.title}",
                onClick = onClick,
            )
            .background(Color(0xFF9D988B))
            .drawWithContent {
                drawContent()
                if (pressed) {
                    val sigW = 2.dp.toPx()
                    val padY = 2.dp.toPx()
                    drawRect(
                        Brush.horizontalGradient(
                            listOf(Color(0xFFBD382B).copy(alpha = 0.42f), Color.Transparent),
                            startX = sigW,
                            endX = sigW + 6.dp.toPx(),
                        ),
                        topLeft = Offset(sigW, padY),
                        size = Size(6.dp.toPx(), (size.height - padY * 2).coerceAtLeast(0f)),
                    )
                    drawRect(
                        Color(0xFFBD382B),
                        topLeft = Offset(0f, padY),
                        size = Size(sigW, (size.height - padY * 2).coerceAtLeast(0f)),
                    )
                }
            }
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (pressed && motion) {
                        translationX = dipPx
                        translationY = dipPx
                    }
                }
                .tiledMaterial(ivory, 260.dp, Color(0xFFF8F6F0)) {
                    drawRect(
                        Brush.horizontalGradient(
                            0f to Color(0xFFE2DED2).copy(alpha = 0.35f),
                            0.14f to Color.White.copy(alpha = 0.44f),
                            0.68f to Color.Transparent,
                            1f to Color(0xFFD0CABC).copy(alpha = 0.38f),
                        )
                    )
                    val topBevel = 2.dp.toPx()
                    val bottomBevel = 3.dp.toPx()
                    drawRect(
                        Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.92f), Color.White.copy(alpha = 0.18f)),
                            startY = 0f,
                            endY = topBevel,
                        ),
                        size = Size(size.width, topBevel),
                    )
                    drawRect(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color(0xFF847F73).copy(alpha = 0.42f)),
                            startY = size.height - bottomBevel,
                            endY = size.height,
                        ),
                        topLeft = Offset(0f, size.height - bottomBevel),
                        size = Size(size.width, bottomBevel),
                    )
                    val seam = 1.dp.toPx()
                    drawRect(Color(0xFF9D988B), topLeft = Offset(0f, size.height - seam), size = Size(size.width, seam))
                    drawRect(Color.White.copy(alpha = 0.65f), size = Size(seam, size.height))
                    drawRect(
                        Color(0xFFBCB6A6).copy(alpha = 0.55f),
                        topLeft = Offset(size.width - 2.dp.toPx(), 0f),
                        size = Size(2.dp.toPx(), size.height),
                    )
                    if (pressed) {
                        drawRect(Color.Black.copy(alpha = 0.06f))
                        drawRect(
                            Brush.horizontalGradient(
                                listOf(Color(0xFF181C19).copy(alpha = 0.18f), Color.Transparent),
                                startX = 0f,
                                endX = 6.dp.toPx(),
                            ),
                            size = Size(6.dp.toPx(), size.height),
                        )
                        drawRect(
                            Brush.verticalGradient(
                                listOf(Color(0xFF181C19).copy(alpha = 0.16f), Color.Transparent),
                                startY = 0f,
                                endY = 5.dp.toPx(),
                            ),
                            size = Size(size.width, 5.dp.toPx()),
                        )
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Column(
                Modifier
                    .fillMaxWidth(1f - BlackKeyWidthFraction - 0.06f)
                    .padding(start = 20.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    entry.title,
                    fontSize = 16.5.sp,
                    lineHeight = 20.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.25).sp,
                    color = RoomColors.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    entry.artist,
                    fontSize = 12.sp,
                    lineHeight = 15.5.sp,
                    color = SwissWallMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private data class VisibleBlackKey(val seamIndex: Int, val centerY: Int)

@Composable
private fun BlackKeysOverlay(
    listState: LazyListState,
    totalEntries: Int,
    ebony: ImageBitmap?,
) {
    if (totalEntries <= 1) return
    val density = LocalDensity.current
    val motion = LocalPhysicalMotion.current
    val halfHeightPx = with(density) { (BlackKeyHeight * 0.5f).roundToPx() }
    val heightPx = with(density) { BlackKeyHeight.roundToPx() }
    val dipPx = with(density) { 1.5.dp.toPx() }
    var pressedSeam by remember { mutableStateOf<Int?>(null) }

    val visibleKeys by remember(listState, totalEntries) {
        derivedStateOf {
            val items = listState.layoutInfo.visibleItemsInfo
            if (items.isEmpty()) emptyList()
            else buildList {
                val first = items.first()
                if (first.index > 0 && hasBlackKeyBelow(first.index - 1, totalEntries)) {
                    add(VisibleBlackKey(first.index - 1, first.offset))
                }
                for (item in items) {
                    if (hasBlackKeyBelow(item.index, totalEntries)) {
                        add(VisibleBlackKey(item.index, item.offset + item.size))
                    }
                }
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().zIndex(4f)) {
        val keybedW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val keyW = (keybedW * BlackKeyWidthFraction).roundToInt().coerceAtLeast(1)
        val keyX = (keybedW - keyW).roundToInt()

        Canvas(Modifier.fillMaxSize()) {
            for (key in visibleKeys) {
                val pressed = pressedSeam == key.seamIndex
                val shift = if (pressed && motion) dipPx else 0f
                val left = keyX.toFloat() + shift
                val top = (key.centerY - halfHeightPx).toFloat() + shift
                drawBlackKeyWithShadow(
                    ebony = ebony,
                    left = left,
                    top = top,
                    keyWidth = keyW.toFloat(),
                    keyHeight = heightPx.toFloat(),
                    pressed = pressed,
                )
            }
        }

        // Invisible tap targets over each visible black key so tapping a black key depresses only itself.
        visibleKeys.forEach { key ->
            key(key.seamIndex) {
                val interaction = remember { MutableInteractionSource() }
                val isPressed by interaction.collectIsPressedAsState()
                LaunchedEffect(isPressed) {
                    if (isPressed) pressedSeam = key.seamIndex
                    else if (pressedSeam == key.seamIndex) pressedSeam = null
                }
                Box(
                    Modifier
                        .offset { IntOffset(keyX, key.centerY - halfHeightPx) }
                        .size(with(density) { keyW.toDp() }, BlackKeyHeight)
                        .clearAndSetSemantics {}
                        .clickable(interactionSource = interaction, indication = null) {}
                )
            }
        }
    }
}

private fun DrawScope.drawBlackKeyWithShadow(
    ebony: ImageBitmap?,
    left: Float,
    top: Float,
    keyWidth: Float,
    keyHeight: Float,
    pressed: Boolean,
) {
    val radius = 2.5.dp.toPx()
    val blurFar = (if (pressed) 6.dp else 12.dp).toPx()
    val dxFar = (if (pressed) -3.dp else -6.dp).toPx()
    val dyFar = (if (pressed) 3.dp else 7.dp).toPx()
    val blurNear = (if (pressed) 2.dp else 3.dp).toPx()
    val dxNear = (if (pressed) -1.dp else -2.dp).toPx()
    val dyNear = (if (pressed) 1.dp else 2.dp).toPx()

    val padLeft = ceil(blurFar + -dxFar + 2.dp.toPx()).toInt()
    val padTop = ceil(blurFar + 2.dp.toPx()).toInt()
    val padRight = 2
    val padBottom = ceil(blurFar + dyFar + 2.dp.toPx()).toInt()
    val kw = ceil(keyWidth).toInt().coerceAtLeast(1)
    val kh = ceil(keyHeight).toInt().coerceAtLeast(1)

    val shadowMask = RoomShadowCache.image("bk-shadow:$kw:$kh:$pressed:$density") {
        val bitmap = Bitmap.createBitmap(kw + padLeft + padRight, kh + padTop + padBottom, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val radii = floatArrayOf(radius, radius, 0f, 0f, 0f, 0f, radius, radius)
        fun layer(dx: Float, dy: Float, blur: Float, alpha: Int, r: Int, g: Int, b: Int) {
            paint.color = android.graphics.Color.argb(alpha, r, g, b)
            paint.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
            val path = android.graphics.Path().apply {
                addRoundRect(padLeft + dx, padTop + dy, padLeft + dx + keyWidth, padTop + dy + keyHeight, radii, android.graphics.Path.Direction.CW)
            }
            canvas.drawPath(path, paint)
        }
        layer(dxFar, dyFar, blurFar, if (pressed) 51 else 61, 20, 24, 21)
        layer(dxNear, dyNear, blurNear, if (pressed) 76 else 87, 18, 22, 20)
        bitmap.asImageBitmap()
    }
    drawImage(shadowMask, topLeft = Offset(left - padLeft, top - padTop))

    val keyPath = Path().apply {
        addRoundRect(
            RoundRect(
                rect = Rect(left, top, left + keyWidth, top + keyHeight),
                topLeft = CornerRadius(radius, radius),
                bottomLeft = CornerRadius(radius, radius),
            )
        )
    }
    clipPath(keyPath) {
        if (ebony != null) {
            val tilePx = 200.dp.toPx()
            val shader = ImageShader(ebony, TileMode.Repeated, TileMode.Repeated)
            shader.setLocalMatrix(Matrix().apply {
                setScale(tilePx / ebony.width.coerceAtLeast(1), tilePx / ebony.height.coerceAtLeast(1))
            })
            drawRect(ShaderBrush(shader), topLeft = Offset(left, top), size = Size(keyWidth, keyHeight))
        } else {
            drawRect(Color(0xFF1F2421), topLeft = Offset(left, top), size = Size(keyWidth, keyHeight))
        }
        drawRect(
            Brush.horizontalGradient(
                0f to Color.White.copy(alpha = 0.20f),
                0.11f to Color.White.copy(alpha = 0.09f),
                0.21f to Color.White.copy(alpha = 0.02f),
                0.35f to Color.Black.copy(alpha = 0.05f),
                0.92f to Color.Black.copy(alpha = 0.28f),
                1f to Color.Black.copy(alpha = 0.45f),
                startX = left,
                endX = left + keyWidth,
            ),
            topLeft = Offset(left, top),
            size = Size(keyWidth, keyHeight),
        )
        drawRect(
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.22f),
                0.08f to Color.White.copy(alpha = 0.06f),
                0.20f to Color.Transparent,
                0.86f to Color.Black.copy(alpha = 0.28f),
                1f to Color(0xFF080A09).copy(alpha = 0.72f),
                startY = top,
                endY = top + keyHeight,
            ),
            topLeft = Offset(left, top),
            size = Size(keyWidth, keyHeight),
        )
        if (pressed) drawRect(Color.Black.copy(alpha = 0.10f), topLeft = Offset(left, top), size = Size(keyWidth, keyHeight))
        val rim = 1.dp.toPx()
        val rimAlpha = if (pressed) 0.08f else 0.16f
        drawLine(Color.White.copy(alpha = rimAlpha), Offset(left, top + rim * 0.5f), Offset(left + keyWidth, top + rim * 0.5f), rim)
        drawLine(Color.White.copy(alpha = rimAlpha), Offset(left + rim * 0.5f, top), Offset(left + rim * 0.5f, top + keyHeight), rim)
    }
}

@Composable
internal fun BauhausBottomFooter(ebony: ImageBitmap?, metal: ImageBitmap?) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(34.dp)
            .zIndex(15f)
            .tiledMaterial(ebony, 240.dp, Color(0xFF1F2421)) {
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = 0.16f),
                        0.06f to Color.White.copy(alpha = 0.03f),
                        0.55f to Color.Black.copy(alpha = 0.18f),
                        1f to Color.Black.copy(alpha = 0.42f),
                    )
                )
                val stroke = 1.dp.toPx()
                drawLine(Color.White.copy(alpha = 0.18f), Offset(0f, stroke * 0.5f), Offset(size.width, stroke * 0.5f), stroke)
                drawLine(Color.White.copy(alpha = 0.16f), Offset(stroke * 0.5f, 0f), Offset(stroke * 0.5f, size.height), stroke)
                drawLine(Color.Black.copy(alpha = 0.62f), Offset(size.width - stroke * 0.5f, 0f), Offset(size.width - stroke * 0.5f, size.height), stroke)
                drawLine(Color.Black.copy(alpha = 0.62f), Offset(0f, size.height - stroke * 0.5f), Offset(size.width, size.height - stroke * 0.5f), stroke)
            }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        MicroScrew()

        Row(
            Modifier
                .height(20.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Brush.verticalGradient(0f to Color(0xFF0D0F0E), 0.75f to Color(0xFF151917), 1f to Color(0xFF1C211E)))
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.9f), Color.Transparent),
                            startY = 0f,
                            endY = 4.dp.toPx(),
                        ),
                        size = Size(size.width, 4.dp.toPx()),
                    )
                    drawLine(
                        Color.White.copy(alpha = 0.13f),
                        Offset(0f, size.height - 0.5.dp.toPx()),
                        Offset(size.width, size.height - 0.5.dp.toPx()),
                        1.dp.toPx(),
                    )
                }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            repeat(3) {
                BauhausCantileverPedal(metal)
            }
            Canvas(Modifier.padding(start = 2.dp).size(3.dp)) {
                drawCircle(Color(0xFFBD382B).copy(alpha = 0.35f), radius = size.minDimension * 1.15f)
                drawCircle(Color(0xFFBD382B), radius = size.minDimension * 0.5f)
            }
        }

        MicroScrew()
    }
}

@Composable
private fun BauhausCantileverPedal(metal: ImageBitmap?) {
    val motion = LocalPhysicalMotion.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val dipPx = with(LocalDensity.current) { 1.dp.toPx() }

    Box(
        Modifier
            .size(22.dp, 11.dp)
            .clearAndSetSemantics {}
            .clickable(interactionSource = interaction, indication = null) {}
            .drawWithCache {
                val radius = 1.5.dp.toPx()
                val dropDy = if (pressed) 1.dp.toPx() else 2.dp.toPx()
                val dropBlur = (if (pressed) 1.dp else 3.dp).toPx()
                onDrawBehind {
                    drawRoundRect(
                        Color.Black.copy(alpha = 0.82f),
                        topLeft = Offset(0f, dropDy),
                        size = Size(size.width, size.height + dropBlur * 0.25f),
                        cornerRadius = CornerRadius(radius, radius),
                    )
                }
            }
            .graphicsLayer {
                if (pressed && motion) translationY = dipPx
            }
            .clip(RoundedCornerShape(1.5.dp))
            .tiledMaterial(metal, 90.dp, Color(0xFF565E60)) {
                drawRect(
                    Brush.verticalGradient(
                        0f to Color(0xFFE1E8E8).copy(alpha = 0.34f),
                        0.18f to Color(0xFF949EA0).copy(alpha = 0.42f),
                        0.65f to Color(0xFF444A4C).copy(alpha = 0.78f),
                        1f to Color(0xFF181C1D).copy(alpha = 0.96f),
                    )
                )
                if (pressed) drawRect(Color.Black.copy(alpha = 0.14f))
                val stroke = 1.dp.toPx()
                drawLine(Color.White.copy(alpha = if (pressed) 0.16f else 0.32f), Offset(0f, stroke * 0.5f), Offset(size.width, stroke * 0.5f), stroke)
                drawLine(Color.Black.copy(alpha = 0.70f), Offset(0f, size.height - stroke * 0.75f), Offset(size.width, size.height - stroke * 0.75f), stroke * 1.5f)
                val ridgePad = 3.dp.toPx()
                drawLine(Color.White.copy(alpha = 0.28f), Offset(ridgePad, 3.dp.toPx()), Offset(size.width - ridgePad, 3.dp.toPx()), stroke)
            }
    )
}
