package cn.musicpilgrimage.app.ui.room

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.musicpilgrimage.app.ui.LocalPhotoViewerCoordinator
import cn.musicpilgrimage.app.ui.LocalPhysicalMotion
import cn.musicpilgrimage.app.ui.rememberDetailZoomSource
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ArchivePreviewImage(
    sources: List<String>,
    modifier: Modifier,
    scale: ContentScale,
    loadedTag: String? = null,
    onLoaded: (Drawable) -> Unit = {},
    onUnavailable: () -> Unit = {},
) {
    var index by remember(sources) { mutableIntStateOf(0) }
    val source = sources.getOrNull(index) ?: return
    var loaded by remember(source) { mutableStateOf(false) }
    AsyncImage(
        source, null, if (loaded && loadedTag != null) modifier.testTag(loadedTag) else modifier, contentScale = scale,
        onSuccess = {
            loaded = true
            onLoaded(it.result.drawable)
        },
        onError = {
            index++
            if (index == sources.size) onUnavailable()
        },
    )
}

@Composable
internal fun PlacePhotoThumbnail(
    placeId: String,
    photo: ArchivePhoto,
    modifier: Modifier = Modifier.size(46.dp),
    fallback: @Composable () -> Unit = {},
) {
    var unavailable by remember(photo) { mutableStateOf(false) }
    if (unavailable) {
        fallback()
        return
    }
    val zoom = rememberDetailZoomSource("place/${Uri.encode(placeId)}", photo.asset, ContentScale.Crop)
    ArchivePreviewImage(
        photo.previewSources, zoom?.position(modifier) ?: modifier, ContentScale.Crop,
        loadedTag = "place-photo-preview-$placeId",
        onLoaded = { zoom?.load(it) },
        onUnavailable = { unavailable = true; zoom?.load(null) },
    )
}

@Composable
internal fun ArchiveFavoriteIcon(favorite: Boolean, description: String?, modifier: Modifier = Modifier) {
    val motion = LocalPhysicalMotion.current
    val scale = remember { Animatable(1f) }
    var wasFavorite by remember { mutableStateOf(favorite) }
    LaunchedEffect(favorite, motion) {
        val changed = favorite != wasFavorite
        wasFavorite = favorite
        scale.snapTo(1f)
        if (changed && motion) {
            scale.animateTo(1f, keyframes {
                durationMillis = if (favorite) 260 else 160
                if (favorite) {
                    1.22f at 81
                    .96f at 165
                } else .86f at 55
            })
        }
    }
    Icon(if (favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, description,
        modifier.graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        },
        tint = if (favorite) RoomColors.Red else RoomColors.Ink)
}

@Composable
internal fun ArchiveImage(
    id: String,
    model: String,
    description: String,
    modifier: Modifier,
    fallback: String? = null,
    credit: String? = null,
    timestamp: String? = null,
    scale: ContentScale = ContentScale.Fit,
    onSources: (() -> Unit)? = null,
    onUnavailable: (() -> Unit)? = null,
    fullImage: String? = null,
    fullImageSize: IntSize = IntSize.Zero,
    thumbnail: ImageBitmap? = null,
    sleeve: Boolean = false,
) {
    val viewer = LocalPhotoViewerCoordinator.current
    var source by remember(model, fallback) { mutableStateOf(model) }
    var ratio by remember(source) { mutableFloatStateOf(1f) }
    var loaded by remember(source) { mutableStateOf(false) }
    var failed by remember(source) { mutableStateOf(false) }
    var viewing by remember(id, source) { mutableStateOf(false) }
    var bounds by remember(id, source) { mutableStateOf<Rect?>(null) }
    val hidden = viewer?.hiddenPhotoId == id
    val unavailable by rememberUpdatedState(onUnavailable)
    val placeholder = remember(thumbnail) { thumbnail?.let { BitmapPainter(it) } }
    Box(
        modifier.onGloballyPositioned { coordinates ->
            val visible = coordinates.boundsInRoot()
            val measured = coordinates.size
            bounds = visible.takeIf {
                it.width >= measured.width - 1 && it.height >= measured.height - 1
            }
        }.clickable(enabled = loaded && !hidden, role = Role.Button, onClickLabel = "查看$description") { viewing = true }
            .drawWithContent { if (viewer?.hiddenPhotoId != id) drawContent() }
            .then(if (sleeve) Modifier.sleeveShadow().coverSleeve() else Modifier)
            .clipToBounds()
            .background(RoomColors.Line.copy(alpha = .3f)),
    ) {
        AsyncImage(
            source, description, Modifier.fillMaxSize(), placeholder = placeholder, contentScale = scale,
            onSuccess = {
                val drawable = it.result.drawable
                if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
                    ratio = drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight
                }
                loaded = true
                failed = false
            },
            onError = {
                if (!fallback.isNullOrBlank() && source != fallback) source = fallback
                else {
                    failed = true
                    unavailable?.invoke()
                }
            },
        )
        if (failed && onUnavailable == null) {
            Text("照片暂时无法加载", Modifier.align(Alignment.Center).padding(5.dp), color = RoomColors.Muted, fontSize = 12.sp)
        }
        if (loaded && timestamp != null) {
            Text(
                timestamp,
                Modifier.align(Alignment.BottomEnd).padding(3.dp).background(RoomColors.Ink.copy(alpha = .8f), RoundedCornerShape(2.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                color = RoomColors.Paper, fontSize = 10.sp, lineHeight = 12.sp,
            )
        }
    }
    if (viewing) {
        val context = LocalContext.current
        val originalImage by produceState<ImageRequest?>(null, source, fullImage, fullImageSize) {
            if (fullImage != null) {
                val original = archiveOriginalSource(context, source, fullImage, fullImageSize)
                // The viewer starts at the thumbnail's bounds. Decode independent of the morph size.
                value = ImageRequest.Builder(context).data(original).size(Size.ORIGINAL)
                    .memoryCacheKey("archive-original:$original").build()
            }
        }
        val viewerImage = if (fullImage == null) source else originalImage
        viewerImage?.let { image ->
            RoomCommunityPhotoViewer(
                image, description, credit = credit, sourceBoundsInRoot = bounds, sourceAspectRatio = ratio,
                sourceIsCroppedCover = scale == ContentScale.Crop, sourceId = id, onSources = onSources,
                showOriginalLoadStatus = fullImage != null,
            ) { viewing = false }
        }
    }
}

private suspend fun archiveOriginalSource(context: Context, preview: String, original: String, expected: IntSize): String {
    val assetPrefix = "file:///android_asset/"
    if (!original.startsWith("https://") || !preview.startsWith(assetPrefix) || expected == IntSize.Zero) return original
    return withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            context.assets.open(preview.removePrefix(assetPrefix)).use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
        }
        if (bounds.outWidth == expected.width && bounds.outHeight == expected.height) preview else original
    }
}
