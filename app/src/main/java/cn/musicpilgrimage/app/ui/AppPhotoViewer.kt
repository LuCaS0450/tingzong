package cn.musicpilgrimage.app.ui

import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.musicpilgrimage.app.ui.room.RoomColors
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Immutable
internal data class PhotoViewerRequest(
    val id: String,
    val model: Any?,
    val description: String,
    val credit: String? = null,
    val sourceBoundsInRoot: Rect? = null,
    val sourceAspectRatio: Float? = null,
    val sourceIsCroppedCover: Boolean = true,
    val sourceOrientation: Int = Configuration.ORIENTATION_UNDEFINED,
    val onSources: (() -> Unit)? = null,
    val restoreSource: (suspend () -> Unit)? = null,
    val showOriginalLoadStatus: Boolean = false,
    val onClosed: () -> Unit,
)

@Stable
internal class AppPhotoViewerCoordinator {
    var activeRequest by mutableStateOf<PhotoViewerRequest?>(null)
        private set
    var hiddenPhotoId by mutableStateOf<String?>(null)
        private set
    val isOpen: Boolean
        get() = activeRequest != null

    fun open(request: PhotoViewerRequest) {
        activeRequest = request
        hiddenPhotoId = request.id.takeIf { it.isNotBlank() && request.sourceBoundsInRoot != null }
    }

    fun updateActive(request: PhotoViewerRequest) {
        if (activeRequest?.id == request.id || activeRequest != null) {
            activeRequest = request
        }
    }

    fun clearIfMatches(request: PhotoViewerRequest) {
        if (activeRequest?.id == request.id && activeRequest?.onClosed == request.onClosed) {
            activeRequest = null
            hiddenPhotoId = null
        }
    }

    fun clearAll() {
        activeRequest = null
        hiddenPhotoId = null
    }
}

internal val LocalPhotoViewerCoordinator = staticCompositionLocalOf<AppPhotoViewerCoordinator?> { null }

@Composable
internal fun AppPhotoViewerPortal(coordinator: AppPhotoViewerCoordinator) {
    val orientation = rememberPhotoViewerOrientation()
    val request = coordinator.activeRequest ?: return
    key(request.id, request.model) {
        InPlacePhotoViewerContent(
            request = request,
            orientation = orientation,
            onFinishedClose = {
                coordinator.clearIfMatches(request)
                request.onClosed()
            },
        )
    }
}

@Composable
internal fun InPlacePhotoViewerStandalone(request: PhotoViewerRequest) {
    val orientation = rememberPhotoViewerOrientation()
    Dialog(
        onDismissRequest = request.onClosed,
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(Modifier.fillMaxSize()) {
            InPlacePhotoViewerContent(
                request = request,
                orientation = orientation,
                onFinishedClose = request.onClosed,
            )
        }
    }
}

private enum class ViewerPhase { OPENING, OPEN, CLOSING }
private enum class OriginalPhotoLoad { Loading, Ready, Failed }
private val PhotoControlSize = 48.dp
private val PhotoControlSpacing = 8.dp

@Composable
private fun InPlacePhotoViewerContent(
    request: PhotoViewerRequest,
    orientation: PhotoViewerOrientation?,
    onFinishedClose: () -> Unit,
) {
    val motion = LocalPhysicalMotion.current
    val haptics = LocalAppHaptics.current ?: rememberAppHaptics()
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val layoutDirection = LocalLayoutDirection.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val currentAxis by rememberUpdatedState(configuration.orientation)
    val scope = rememberCoroutineScope()
    DisposableEffect(orientation) {
        orientation?.open(currentAxis)
        onDispose { orientation?.close() }
    }

    var phase by remember { mutableStateOf(ViewerPhase.OPENING) }
    var naturalAspectRatio by remember(request.model) {
        mutableStateOf(request.sourceAspectRatio?.takeIf { it.isFinite() && it > 0.05f })
    }
    var originalLoad by remember { mutableStateOf(OriginalPhotoLoad.Loading) }
    var originalAttempt by remember { mutableIntStateOf(0) }
    val backProgressAnim = remember(request.id) { Animatable(0f) }
    val backProgress = if (motion) backProgressAnim.value.coerceIn(0f, 1f) else 0f

    // Morph progress: 0 = thumbnail bounds (cropped cover), 1 = expanded centered photo (uncropped fit).
    val morphProgress = remember { Animatable(if (motion) 0f else 1f) }
    // Current interactive zoom & pan.
    var zoomScale by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var zoomControlsVisible by remember { mutableStateOf(false) }
    var infoVisible by remember { mutableStateOf(false) }
    val zoomed = zoomScale > 1f
    LaunchedEffect(zoomed, zoomControlsVisible, infoVisible) {
        if (zoomed && zoomControlsVisible && !infoVisible) {
            delay(2_600)
            zoomControlsVisible = false
        }
    }
    // Downward swipe-to-dismiss state (only active at zoom <= 1.02f).
    val dragOffsetX = remember { Animatable(0f) }
    val dragOffsetY = remember { Animatable(0f) }
    var draggingDismiss by remember { mutableStateOf(false) }
    LaunchedEffect(configuration.orientation) {
        zoomScale = 1f
        panOffset = Offset.Zero
        zoomControlsVisible = false
        draggingDismiss = false
        dragOffsetX.snapTo(0f)
        dragOffsetY.snapTo(0f)
    }

    // Captured closing origin when dismissing from a dragged/scaled viewer state.
    var closingFromRect by remember { mutableStateOf<Rect?>(null) }
    var closingFadeOnly by remember { mutableStateOf(false) }
    val closingProgress = remember { Animatable(0f) }
    val fadeOnlyAlpha = remember { Animatable(1f) }
    val fadeOnlyScale = remember { Animatable(1f) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .testTag("room-community-photo-viewer"),
    ) {
        val viewportW = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val viewportH = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
        val safeInsets = WindowInsets.safeDrawing.asPaddingValues()
        val safeLeft = with(density) { safeInsets.calculateLeftPadding(layoutDirection).toPx() }
        val safeTop = with(density) { safeInsets.calculateTopPadding().toPx() }
        val safeW = (viewportW - safeLeft - with(density) { safeInsets.calculateRightPadding(layoutDirection).toPx() }).coerceAtLeast(1f)
        val safeH = (viewportH - safeTop - with(density) { safeInsets.calculateBottomPadding().toPx() }).coerceAtLeast(1f)
        val controlRail = with(density) { (PhotoControlSize + PhotoControlSpacing * 2).toPx() }
        val sideRail = if (landscape) controlRail.coerceAtMost(safeW - 1f) else 0f
        val topRail = if (landscape) 0f else controlRail.coerceAtMost(safeH - 1f)
        val rtl = layoutDirection == LayoutDirection.Rtl
        val galleryW = safeW - sideRail
        val galleryH = safeH - topRail
        val galleryLeft = if (rtl) safeLeft + sideRail else safeLeft
        val galleryTop = safeTop + topRail

        val aspect = naturalAspectRatio ?: (request.sourceBoundsInRoot?.let {
            if (it.height > 1f) it.width / it.height else null
        } ?: (16f / 9f))

        val fitW: Float
        val fitH: Float
        if (galleryW / galleryH > aspect) {
            fitH = galleryH
            fitW = fitH * aspect
        } else {
            fitW = galleryW
            fitH = fitW / aspect
        }
        // Keep the image centered in the safe area whenever its existing margins can hold the controls.
        val targetLeft = (safeLeft + (safeW - fitW) * 0.5f)
            .coerceIn(galleryLeft, galleryLeft + (galleryW - fitW).coerceAtLeast(0f))
        val targetTop = (safeTop + (safeH - fitH) * 0.5f)
            .coerceIn(galleryTop, galleryTop + (galleryH - fitH).coerceAtLeast(0f))
        val targetRect = Rect(targetLeft, targetTop, targetLeft + fitW, targetTop + fitH)

        val validSourceRect = remember(request.sourceBoundsInRoot, request.sourceOrientation, configuration.orientation, viewportW, viewportH) {
            val rect = request.sourceBoundsInRoot
            val sameOrientation = request.sourceOrientation == Configuration.ORIENTATION_UNDEFINED ||
                request.sourceOrientation == configuration.orientation
            rect?.takeIf {
                sameOrientation &&
                    it.width > 8f && it.height > 8f &&
                    it.right > 0f && it.left < viewportW &&
                    it.bottom > 0f && it.top < viewportH
            }
        }

        val fallbackSourceRect = remember(targetRect) {
            val w = targetRect.width * 0.86f
            val h = targetRect.height * 0.86f
            Rect(
                targetRect.center.x - w * 0.5f,
                targetRect.center.y - h * 0.5f,
                targetRect.center.x + w * 0.5f,
                targetRect.center.y + h * 0.5f,
            )
        }

        LaunchedEffect(request.id, motion) {
            if (!motion) backProgressAnim.snapTo(0f)
            if (phase != ViewerPhase.OPENING) return@LaunchedEffect
            if (motion) {
                morphProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(dampingRatio = 0.88f, stiffness = 420f, visibilityThreshold = 0.001f),
                )
            } else {
                morphProgress.snapTo(1f)
            }
            if (phase == ViewerPhase.OPENING) phase = ViewerPhase.OPEN
        }

        fun currentInteractiveRect(): Rect {
            val p = morphProgress.value.coerceIn(0f, 1f)
            val baseSrc = validSourceRect ?: fallbackSourceRect
            val base = lerpRect(baseSrc, targetRect, p)
            val dragProgress = (abs(dragOffsetY.value) / (viewportH * 0.45f)).coerceIn(0f, 1f)
            val dragScale = (1f - dragProgress * 0.24f) * (1f - backProgress * 0.14f)
            val totalScale = zoomScale * dragScale
            val cx = base.center.x + dragOffsetX.value + panOffset.x
            val cy = base.center.y + dragOffsetY.value + panOffset.y
            val hw = base.width * totalScale * 0.5f
            val hh = base.height * totalScale * 0.5f
            return Rect(cx - hw, cy - hh, cx + hw, cy + hh)
        }

        fun triggerClose() {
            if (phase == ViewerPhase.CLOSING) return
            phase = ViewerPhase.CLOSING
            val originRect = currentInteractiveRect()
            val canMorphToSource = motion && validSourceRect != null && zoomScale <= 1.04f
            scope.launch {
                morphProgress.stop()
                dragOffsetX.stop()
                dragOffsetY.stop()
                backProgressAnim.stop()
                val restoreSource=request.restoreSource
                val openingAxis = orientation?.restoreOpeningAxis()
                if (openingAxis != null) {
                    withTimeoutOrNull(700) { snapshotFlow { currentAxis }.first { it == openingAxis } }
                }
                if (!motion) {
                    restoreSource?.invoke()
                    onFinishedClose()
                    return@launch
                }
                if (canMorphToSource && openingAxis == null) {
                    closingFromRect = originRect
                    closingFadeOnly = false
                    closingProgress.snapTo(0f)
                    closingProgress.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(durationMillis = 225, easing = AppNavEasing),
                    )
                    restoreSource?.invoke()
                } else {
                    val sourceFrame=restoreSource?.let{launch{it()}}
                    closingFadeOnly = true
                    fadeOnlyAlpha.snapTo(1f)
                    fadeOnlyScale.snapTo(1f)
                    launch {
                        fadeOnlyScale.animateTo(0.88f, tween(175, easing = AppNavEasing))
                    }
                    fadeOnlyAlpha.animateTo(0f, tween(175, easing = FastOutSlowInEasing))
                    sourceFrame?.join()
                }
                onFinishedClose()
            }
        }

        // Consume repeat Back presses even while CLOSING so underlying pages never pop prematurely.
        AppPredictiveBackHandler(
            enabled = true,
            onProgress = { backEvent ->
                if (motion && phase != ViewerPhase.CLOSING) {
                    scope.launch { backProgressAnim.snapTo(backEvent.progress.coerceIn(0f, 1f)) }
                }
            },
            onCancel = {
                if (phase != ViewerPhase.CLOSING) {
                    scope.launch {
                        if (motion) backProgressAnim.animateTo(0f, tween(CoverMorphCancelMillis, easing = AppNavEasing))
                        else backProgressAnim.snapTo(0f)
                    }
                }
            },
            onBack = {
                if (phase != ViewerPhase.CLOSING) {
                    triggerClose()
                }
            },
        )

        val dragRatio = (dragOffsetY.value.coerceAtLeast(0f) / (viewportH * 0.45f)).coerceIn(0f, 1f)
        val openScrimAlpha = (if (landscape) 1f else 0.94f) * morphProgress.value.coerceIn(0f, 1f) *
            (1f - dragRatio * 0.72f).coerceIn(0.16f, 1f) *
            (1f - backProgress * 0.65f).coerceIn(0.2f, 1f)
        val scrimAlpha = if (phase == ViewerPhase.CLOSING) {
            if (closingFadeOnly) {
                openScrimAlpha * fadeOnlyAlpha.value
            } else {
                openScrimAlpha * (1f - closingProgress.value).coerceIn(0f, 1f)
            }
        } else {
            openScrimAlpha
        }

        // Compute displayed frame rectangle & crop-to-fit image size.
        val activeFrame: Rect
        val cropBlend: Float // 0 = cropped like thumbnail cover, 1 = full natural fit
        if (phase == ViewerPhase.CLOSING && !closingFadeOnly && closingFromRect != null && validSourceRect != null) {
            val t = closingProgress.value.coerceIn(0f, 1f)
            activeFrame = lerpRect(closingFromRect!!, validSourceRect, t)
            cropBlend = 1f - t
        } else {
            val rect = currentInteractiveRect()
            activeFrame = if (phase == ViewerPhase.CLOSING && closingFadeOnly) {
                val s = fadeOnlyScale.value
                val hw = rect.width * s * 0.5f
                val hh = rect.height * s * 0.5f
                Rect(rect.center.x - hw, rect.center.y - hh, rect.center.x + hw, rect.center.y + hh)
            } else {
                rect
            }
            cropBlend = morphProgress.value.coerceIn(0f, 1f)
        }

        val frameAspect = if (activeFrame.height > 1f) activeFrame.width / activeFrame.height else aspect
        val coverW: Float
        val coverH: Float
        if (aspect > frameAspect) {
            coverH = activeFrame.height
            coverW = coverH * aspect
        } else {
            coverW = activeFrame.width
            coverH = coverW / aspect
        }
        val fitInsideFrameW: Float
        val fitInsideFrameH: Float
        if (frameAspect > aspect) {
            fitInsideFrameH = activeFrame.height
            fitInsideFrameW = fitInsideFrameH * aspect
        } else {
            fitInsideFrameW = activeFrame.width
            fitInsideFrameH = fitInsideFrameW / aspect
        }

        val cropFactor = if (request.sourceIsCroppedCover && validSourceRect != null) cropBlend else 1f
        val imageW = coverW + (fitInsideFrameW - coverW) * cropFactor
        val imageH = coverH + (fitInsideFrameH - coverH) * cropFactor
        val imageContentAlpha = if (phase == ViewerPhase.CLOSING && closingFadeOnly) {
            fadeOnlyAlpha.value
        } else if (phase == ViewerPhase.CLOSING && validSourceRect == null) {
            (1f - closingProgress.value).coerceIn(0f, 1f)
        } else {
            1f
        }

        // Scrim & Gesture layer.
        val dismissDistancePx = with(density) { 88.dp.toPx() }
        val dismissVelocityPx = with(density) { 680.dp.toPx() }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF141816).copy(alpha = scrimAlpha.coerceIn(0f, 1f)))
                .pointerInput(phase, motion, targetRect) {
                    detectTapGestures(
                        onDoubleTap = { tap ->
                            if (phase == ViewerPhase.CLOSING) return@detectTapGestures
                            zoomControlsVisible = false
                            if (zoomScale > 1.05f) {
                                zoomScale = 1f
                                panOffset = Offset.Zero
                            } else {
                                zoomScale = 2.2f
                                val center = targetRect.center
                                panOffset = (center - tap) * 0.55f
                            }
                        },
                        onTap = {
                            if (phase == ViewerPhase.CLOSING) return@detectTapGestures
                            if (zoomScale > 1f) zoomControlsVisible = !zoomControlsVisible else triggerClose()
                        },
                    )
                }
                .pointerInput(phase, motion, targetRect, viewportW, viewportH, dismissDistancePx, dismissVelocityPx) {
                    awaitEachGesture {
                        val firstDown = awaitFirstDown(requireUnconsumed = false)
                        if (phase == ViewerPhase.CLOSING) return@awaitEachGesture
                        val tracker = VelocityTracker()
                        var isDismissGesture = false
                        var dismissedThresholdHaptic = false
                        var dragX = dragOffsetX.value
                        var dragY = dragOffsetY.value
                        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            dragOffsetX.stop()
                            dragOffsetY.stop()
                        }

                        while (true) {
                            val event = awaitPointerEvent()
                            val activePointers = event.changes.count { it.pressed }
                            if (activePointers == 0) break

                            if (activePointers >= 2 || zoomScale > 1.02f) {
                                isDismissGesture = false
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                val nextZoom = (zoomScale * zoomChange).coerceIn(0.88f, 4f)
                                val oldZoom = zoomScale
                                if (nextZoom != oldZoom || panChange != Offset.Zero) zoomControlsVisible = false
                                zoomScale = nextZoom
                                if (nextZoom > 1.02f) {
                                    val maxPanX = ((targetRect.width * nextZoom - viewportW) * 0.5f + 48f).coerceAtLeast(36f)
                                    val maxPanY = ((targetRect.height * nextZoom - viewportH) * 0.5f + 48f).coerceAtLeast(36f)
                                    val nextPan = if (oldZoom != nextZoom && zoomChange != 1f) {
                                        val centroid = event.calculateCentroid(useCurrent = false)
                                        (panOffset - (centroid - targetRect.center) * (nextZoom / oldZoom - 1f)) + panChange
                                    } else {
                                        panOffset + panChange
                                    }
                                    panOffset = Offset(
                                        nextPan.x.coerceIn(-maxPanX, maxPanX),
                                        nextPan.y.coerceIn(-maxPanY, maxPanY),
                                    )
                                } else {
                                    panOffset = Offset.Zero
                                }
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            } else {
                                val change = event.changes.firstOrNull { it.id == firstDown.id } ?: event.changes.first()
                                val delta = change.position - change.previousPosition
                                val totalFromDown = change.position - firstDown.position
                                if (!isDismissGesture) {
                                    // Only engage dismiss drag after crossing touch slop downward at initial zoom.
                                    if (totalFromDown.y > viewConfiguration.touchSlop &&
                                        abs(totalFromDown.y) >= abs(totalFromDown.x) * 0.85f
                                    ) {
                                        isDismissGesture = true
                                        draggingDismiss = true
                                    }
                                }
                                if (isDismissGesture) {
                                    tracker.addPosition(change.uptimeMillis, change.position)
                                    dragX += delta.x * 0.78f
                                    dragY = (dragY + delta.y).coerceAtLeast(-24f)
                                    val nextX = dragX
                                    val nextY = dragY
                                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                        dragOffsetX.snapTo(nextX)
                                        dragOffsetY.snapTo(nextY)
                                    }
                                    if (!dismissedThresholdHaptic && nextY >= dismissDistancePx) {
                                        dismissedThresholdHaptic = true
                                        haptics.detent("photo-dismiss-ready")
                                    }
                                    change.consume()
                                }
                            }
                        }

                        if (zoomScale <= 1.02f) {
                            zoomScale = 1f
                            panOffset = Offset.Zero
                        }
                        if (isDismissGesture) {
                            draggingDismiss = false
                            val velocityY = tracker.calculateVelocity().y
                            if (dragOffsetY.value > dismissDistancePx || velocityY > dismissVelocityPx) {
                                triggerClose()
                            } else {
                                scope.launch {
                                    if (motion) {
                                        launch { dragOffsetX.animateTo(0f, spring(0.86f, 440f)) }
                                        dragOffsetY.animateTo(0f, spring(0.86f, 440f))
                                    } else {
                                        dragOffsetX.snapTo(0f)
                                        dragOffsetY.snapTo(0f)
                                    }
                                }
                            }
                        }
                    }
                },
        ) {
            // In-place cropped-to-full morph image container aligned to root coordinates.
            Box(
                modifier = Modifier
                    .absoluteOffset {
                        IntOffset(activeFrame.left.roundToInt(), activeFrame.top.roundToInt())
                    }
                    .size(
                        width = with(density) { activeFrame.width.toDp() },
                        height = with(density) { activeFrame.height.toDp() },
                    )
                    .clipToBounds()
                    .graphicsLayer { alpha = imageContentAlpha },
                contentAlignment = Alignment.Center,
            ) {
                key(originalAttempt) {
                    AsyncImage(
                        model = request.model,
                        contentDescription = request.description,
                        modifier = Modifier.requiredSize(
                            width = with(density) { imageW.toDp() },
                            height = with(density) { imageH.toDp() },
                        ).testTag("photo-viewer-image"),
                        contentScale = ContentScale.FillBounds,
                        filterQuality = FilterQuality.High,
                        onSuccess = { state ->
                            val size = state.painter.intrinsicSize
                            if (size.width > 1f && size.height > 1f) {
                                naturalAspectRatio = size.width / size.height
                            }
                            if (request.showOriginalLoadStatus) originalLoad = OriginalPhotoLoad.Ready
                        },
                        onError = { if (request.showOriginalLoadStatus) originalLoad = OriginalPhotoLoad.Failed },
                    )
                }
            }

            if (request.showOriginalLoadStatus && phase != ViewerPhase.CLOSING &&
                originalLoad != OriginalPhotoLoad.Ready
            ) {
                Column(
                    Modifier.align(Alignment.Center).semantics { liveRegion = LiveRegionMode.Polite },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (originalLoad == OriginalPhotoLoad.Failed) {
                        Text("原图暂时无法加载", color = RoomColors.Paper)
                        TextButton(onClick = {
                            originalLoad = OriginalPhotoLoad.Loading
                            originalAttempt++
                        }) { Text("重试", color = RoomColors.Paper) }
                    } else {
                        Text("正在载入原图…", color = RoomColors.Paper)
                    }
                }
            }

            val controlAlpha = (0.4f + 0.6f * morphProgress.value).coerceIn(0f, 1f) *
                (1f - dragRatio * 2.4f).coerceIn(0f, 1f) *
                (1f - backProgress * 2.4f).coerceIn(0f, 1f)
            if (phase != ViewerPhase.CLOSING && controlAlpha > 0.01f && (!zoomed || zoomControlsVisible)) {
                val controlModifier = Modifier.align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(PhotoControlSpacing)
                    .graphicsLayer { alpha = controlAlpha }
                    .testTag("photo-viewer-controls")
                val photoOptions: @Composable () -> Unit = {
                    if (orientation?.canRequest(configuration) == true) {
                        PhotoViewerButton(Icons.Outlined.ScreenRotation, if (landscape) "返回竖屏" else "横屏观看") {
                            orientation.toggle(currentAxis)
                        }
                    }
                    PhotoViewerButton(Icons.Outlined.Info, "图片信息") { infoVisible = true }
                }
                val closeButton: @Composable () -> Unit = {
                    PhotoViewerButton(Icons.Outlined.Close, "关闭大图") { triggerClose() }
                }
                if (landscape) {
                    Column(controlModifier, verticalArrangement = Arrangement.spacedBy(PhotoControlSpacing)) {
                        closeButton()
                        photoOptions()
                    }
                } else {
                    Row(controlModifier, horizontalArrangement = Arrangement.spacedBy(PhotoControlSpacing)) {
                        photoOptions()
                        closeButton()
                    }
                }
            }
        }
    }

    if (infoVisible) {
        AppMotionDialog(onDismissRequest = { infoVisible = false }) {
            Surface(
                modifier = Modifier.widthIn(max = 380.dp).fillMaxWidth().testTag("photo-viewer-info"),
                color = RoomColors.Paper,
                contentColor = RoomColors.Ink,
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                    Text(request.description, fontSize = 19.sp, lineHeight = 26.sp)
                    request.credit?.takeIf(String::isNotBlank)?.let { credit ->
                        Text(credit, Modifier.padding(top = 10.dp), color = RoomColors.Muted, fontSize = 14.sp, lineHeight = 20.sp)
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        request.onSources?.let { openSources ->
                            TextButton(onClick = {
                                infoVisible = false
                                zoomControlsVisible = false
                                openSources()
                            }) { Text("资料来源", color = RoomColors.Ink) }
                        }
                        TextButton(onClick = { infoVisible = false }) { Text("关闭", color = RoomColors.Ink) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoViewerButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(PhotoControlSize).background(Color(0xEC242B27), CircleShape),
    ) {
        Icon(icon, contentDescription = description, tint = RoomColors.Paper)
    }
}

private fun lerpRect(a: Rect, b: Rect, t: Float): Rect = Rect(
    left = a.left + (b.left - a.left) * t,
    top = a.top + (b.top - a.top) * t,
    right = a.right + (b.right - a.right) * t,
    bottom = a.bottom + (b.bottom - a.bottom) * t,
)
