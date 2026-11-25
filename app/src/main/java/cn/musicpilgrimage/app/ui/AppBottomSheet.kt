package cn.musicpilgrimage.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.musicpilgrimage.app.ui.room.RoomColors
import cn.musicpilgrimage.app.ui.room.SwissButton
import cn.musicpilgrimage.app.ui.room.SwissTextButton
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.tanh

@Stable
internal class AppSheetPortalState(
    visible: Boolean,
    busy: Boolean,
    allowExpandDetent: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier,
    onDragHandleClick: (() -> Unit)?,
    content: @Composable (isExpanded: Boolean, toggleDetent: () -> Unit) -> Unit,
) {
    var visible by mutableStateOf(visible)
    var busy by mutableStateOf(busy)
    var allowExpandDetent by mutableStateOf(allowExpandDetent)
    var onDismissRequest by mutableStateOf(onDismissRequest)
    var modifier by mutableStateOf(modifier)
    var onDragHandleClick by mutableStateOf(onDragHandleClick)
    var content by mutableStateOf(content)
}

internal class AppSheetPortalEntry(
    val id: Any,
    val content: @Composable () -> Unit,
)

@Stable
internal class AppSheetCoordinator {
    private val progresses = mutableStateMapOf<Any, Float>()
    val entries = mutableStateListOf<AppSheetPortalEntry>()

    val hasActiveSheet: Boolean
        get() = entries.isNotEmpty() || progresses.values.any { it > 0.001f }

    val backdropFraction: Float
        get() = progresses.values.maxOrNull()?.coerceIn(0f, 1.15f) ?: 0f

    internal fun setProgress(id: Any, progress: Float) {
        if (progress <= 0f) progresses.remove(id) else progresses[id] = progress
    }

    internal fun register(entry: AppSheetPortalEntry) {
        if (entries.none { it.id == entry.id }) entries.add(entry)
    }

    internal fun unregister(id: Any) {
        entries.removeAll { it.id == id }
        progresses.remove(id)
    }
}

internal val LocalSheetCoordinator = staticCompositionLocalOf<AppSheetCoordinator?> { null }

/**
 * Shared Swiss paper bottom sheet with continuous detent drag, nested-scroll handover,
 * interruptible spring settle, predictive back integration, and backdrop depth coordination.
 */
@Composable
internal fun AppBottomSheet(
    visible: Boolean,
    busy: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    allowExpandDetent: Boolean = true,
    onDragHandleClick: (() -> Unit)? = null,
    content: @Composable (isExpanded: Boolean, toggleDetent: () -> Unit) -> Unit,
) {
    val coordinator = LocalSheetCoordinator.current
    val entryId = remember { Any() }

    if (coordinator != null) {
        val portalState = remember {
            AppSheetPortalState(
                visible = visible,
                busy = busy,
                allowExpandDetent = allowExpandDetent,
                onDismissRequest = onDismissRequest,
                modifier = modifier,
                onDragHandleClick = onDragHandleClick,
                content = content,
            )
        }
        DisposableEffect(coordinator, entryId) {
            onDispose { coordinator.unregister(entryId) }
        }
        SideEffect {
            portalState.visible = visible
            portalState.busy = busy
            portalState.allowExpandDetent = allowExpandDetent
            portalState.onDismissRequest = onDismissRequest
            portalState.modifier = modifier
            portalState.onDragHandleClick = onDragHandleClick
            portalState.content = content
            if (visible) {
                coordinator.register(AppSheetPortalEntry(entryId) {
                    AppBottomSheetHost(
                        entryId = entryId,
                        coordinator = coordinator,
                        visible = portalState.visible,
                        busy = portalState.busy,
                        allowExpandDetent = portalState.allowExpandDetent,
                        onDismissRequest = portalState.onDismissRequest,
                        modifier = portalState.modifier,
                        onDragHandleClick = portalState.onDragHandleClick,
                        content = portalState.content,
                    )
                })
            } else {
                coordinator.unregister(entryId)
            }
        }
    } else if (visible) {
        Dialog(
            onDismissRequest = { if (!busy) onDismissRequest() },
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            Box(Modifier.fillMaxSize()) {
                AppBottomSheetHost(
                    entryId = entryId,
                    coordinator = null,
                    visible = visible,
                    busy = busy,
                    allowExpandDetent = allowExpandDetent,
                    onDismissRequest = onDismissRequest,
                    modifier = modifier,
                    onDragHandleClick = onDragHandleClick,
                    content = content,
                )
            }
        }
    }
}

private enum class SheetLifecyclePhase {
    HIDDEN,
    ENTERING,
    SETTLED,
    DRAGGING,
    PREDICTIVE_BACK,
    DISMISSING,
}

@Composable
private fun AppBottomSheetHost(
    entryId: Any,
    coordinator: AppSheetCoordinator?,
    visible: Boolean,
    busy: Boolean,
    allowExpandDetent: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier,
    onDragHandleClick: (() -> Unit)?,
    content: @Composable (isExpanded: Boolean, toggleDetent: () -> Unit) -> Unit,
) {
    val motion = LocalPhysicalMotion.current
    val haptics = LocalAppHaptics.current ?: rememberAppHaptics()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val imeVisible = WindowInsets.ime.getBottom(density) > 0

    val latestBusy by rememberUpdatedState(busy)
    val latestMotion by rememberUpdatedState(motion)
    val latestOnDismissRequest by rememberUpdatedState(onDismissRequest)
    val latestOnDragHandleClick by rememberUpdatedState(onDragHandleClick)

    val rootVelocityTracker = remember(entryId) { VelocityTracker() }
    var rootReleaseVelocityY by remember(entryId) { mutableFloatStateOf(0f) }

    // Measure full viewport to calibrate mid/high detents cleanly across portrait, landscape, and IME.
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .pointerInput(entryId) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    rootReleaseVelocityY = 0f
                    rootVelocityTracker.resetTracking()
                    rootVelocityTracker.addPosition(down.uptimeMillis, down.position)
                    val activeId = down.id
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == activeId } ?: event.changes.firstOrNull() ?: break
                        rootVelocityTracker.addPosition(change.uptimeMillis, change.position)
                        if (!change.pressed) {
                            rootReleaseVelocityY = rootVelocityTracker.calculateVelocity().y
                            break
                        }
                    }
                }
            },
    ) {
        val viewportHeightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
        val topClearanceDp = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding() + 14.dp
        val maxAvailableDp = (maxHeight - topClearanceDp).coerceAtLeast(180.dp)
        val midCapDp = (maxHeight * 0.50f).coerceIn(minOf(260.dp, maxAvailableDp), maxAvailableDp)
        val highCapDp = (maxHeight * 0.88f).coerceIn(midCapDp, maxAvailableDp)
        val canToggleDetent = allowExpandDetent && (highCapDp - midCapDp) >= 56.dp

        val midPx = with(density) { (if (canToggleDetent) midCapDp else maxAvailableDp).toPx() }
        val highPx = with(density) { highCapDp.toPx() }
        val maxOverPullPx = with(density) { 20.dp.toPx() }

        val latestCanToggle by rememberUpdatedState(canToggleDetent)
        val latestMidPx by rememberUpdatedState(midPx)
        val latestHighPx by rememberUpdatedState(highPx)

        var phase by remember { mutableStateOf(SheetLifecyclePhase.HIDDEN) }
        var motionGen by remember { mutableIntStateOf(0) }
        var activeMotionJob by remember { mutableStateOf<Job?>(null) }
        var dismissDispatched by remember { mutableStateOf(false) }

        var expanded by remember { mutableStateOf(false) }
        var measuredSingleHeightPx by remember { mutableFloatStateOf(with(density) { midCapDp.toPx() }) }
        val offsetY = remember { Animatable(viewportHeightPx * 0.65f) }
        val detentHeightPx = remember { Animatable(midPx) }
        val overPullUp = remember { Animatable(0f) }

        var backBaselineOffsetPx by remember { mutableFloatStateOf(0f) }
        var hapticKeyCounter by remember { mutableIntStateOf(0) }
        var firedMidDragHaptic by remember { mutableStateOf(false) }
        var nestedSheetDragActive by remember { mutableStateOf(false) }
        var dismissLockedHeightPx by remember { mutableFloatStateOf(midPx) }

        fun currentSheetHeightPx(): Float = if (canToggleDetent) detentHeightPx.value else measuredSingleHeightPx

        fun claimMotion(newPhase: SheetLifecyclePhase): Int {
            activeMotionJob?.cancel()
            activeMotionJob = null
            phase = newPhase
            return ++motionGen
        }

        fun springSpec() = spring<Float>(
            dampingRatio = 0.88f,
            stiffness = 460f,
            visibilityThreshold = 0.5f,
        )

        fun settleToDetent(targetExpanded: Boolean, initialVelocityY: Float = 0f) {
            val useExpanded = latestCanToggle && targetExpanded
            expanded = useExpanded
            firedMidDragHaptic = false
            val gen = claimMotion(SheetLifecyclePhase.ENTERING)
            val targetHeight = if (useExpanded) latestHighPx else latestMidPx
            activeMotionJob = scope.launch {
                try {
                    if (latestMotion) {
                        val heightJob = launch {
                            detentHeightPx.animateTo(targetHeight, springSpec())
                        }
                        val overJob = launch {
                            overPullUp.animateTo(0f, springSpec())
                        }
                        offsetY.animateTo(
                            targetValue = 0f,
                            initialVelocity = initialVelocityY.coerceAtMost(0f),
                            animationSpec = springSpec(),
                        )
                        heightJob.join()
                        overJob.join()
                    } else {
                        detentHeightPx.snapTo(targetHeight)
                        overPullUp.snapTo(0f)
                        offsetY.snapTo(0f)
                    }
                    if (gen == motionGen && phase == SheetLifecyclePhase.ENTERING) {
                        phase = SheetLifecyclePhase.SETTLED
                    }
                } catch (_: CancellationException) {
                    // Ownership moved synchronously via claimMotion.
                }
            }
        }

        fun dismissWithMotion(velocityY: Float = 0f) {
            if (dismissDispatched) return
            if (latestBusy) {
                settleToDetent(expanded)
                return
            }
            if (phase == SheetLifecyclePhase.DISMISSING) return
            dismissLockedHeightPx = currentSheetHeightPx().coerceAtLeast(with(density) { 180.dp.toPx() })
            val gen = claimMotion(SheetLifecyclePhase.DISMISSING)
            haptics.detent("sheet-dismiss-${++hapticKeyCounter}")
            if (!latestMotion) {
                dismissDispatched = true
                coordinator?.setProgress(entryId, 0f)
                latestOnDismissRequest()
                return
            }
            activeMotionJob = scope.launch {
                try {
                    val target = (dismissLockedHeightPx + with(density) { 28.dp.toPx() })
                        .coerceAtLeast(viewportHeightPx * 0.45f)
                    if (latestMotion) {
                        launch { overPullUp.animateTo(0f, springSpec()) }
                        offsetY.animateTo(
                            targetValue = target,
                            initialVelocity = velocityY.coerceAtLeast(0f),
                            animationSpec = tween(durationMillis = LocalOpenCloseMillis, easing = AppNavEasing),
                        )
                    } else {
                        overPullUp.snapTo(0f)
                        offsetY.snapTo(target)
                    }
                    if (gen == motionGen && phase == SheetLifecyclePhase.DISMISSING && !latestBusy && !dismissDispatched) {
                        dismissDispatched = true
                        coordinator?.setProgress(entryId, 0f)
                        latestOnDismissRequest()
                    } else if (gen == motionGen && latestBusy) {
                        settleToDetent(expanded)
                    }
                } catch (_: CancellationException) {
                    // Interrupted cleanly by user takeover, predictive back, or busy save recovery.
                }
            }
        }

        LaunchedEffect(visible) {
            if (visible) {
                dismissDispatched = false
                expanded = false
                val gen = claimMotion(SheetLifecyclePhase.ENTERING)
                val targetHeight = if (latestCanToggle && imeVisible) {
                    expanded = true
                    latestHighPx
                } else {
                    latestMidPx
                }
                detentHeightPx.snapTo(targetHeight)
                overPullUp.snapTo(0f)
                if (latestMotion) {
                    offsetY.snapTo((targetHeight * 0.92f).coerceAtLeast(viewportHeightPx * 0.42f))
                    activeMotionJob = launch {
                        try {
                            offsetY.animateTo(
                                targetValue = 0f,
                                animationSpec = spring(dampingRatio = 0.88f, stiffness = 430f, visibilityThreshold = 0.5f),
                            )
                            if (gen == motionGen && phase == SheetLifecyclePhase.ENTERING) {
                                phase = SheetLifecyclePhase.SETTLED
                            }
                        } catch (_: CancellationException) {
                        }
                    }
                } else {
                    offsetY.snapTo(0f)
                    phase = SheetLifecyclePhase.SETTLED
                }
            } else {
                claimMotion(SheetLifecyclePhase.HIDDEN)
            }
        }

        // Recover immediately if a save starts while the sheet is mid-exit or dragged away.
        LaunchedEffect(busy) {
            if (busy && (phase == SheetLifecyclePhase.DISMISSING || phase == SheetLifecyclePhase.PREDICTIVE_BACK || offsetY.value > 0.5f)) {
                settleToDetent(expanded)
            }
        }

        LaunchedEffect(motion) {
            if (!motion && phase == SheetLifecyclePhase.PREDICTIVE_BACK) settleToDetent(expanded)
        }

        // Auto-expand when IME opens and re-clamp detents when orientation/viewport changes.
        LaunchedEffect(imeVisible, canToggleDetent, midPx, highPx) {
            if (imeVisible && canToggleDetent && !expanded && phase != SheetLifecyclePhase.DISMISSING) {
                settleToDetent(targetExpanded = true)
            } else if (phase == SheetLifecyclePhase.SETTLED) {
                val snapHeight = if (expanded && canToggleDetent) highPx else midPx
                detentHeightPx.snapTo(snapHeight)
            }
        }

        val handleDismissThresholdPx = with(density) { 96.dp.toPx() }
        val flingDismissVelocityPx = with(density) { 780.dp.toPx() }
        val flingUpVelocityPx = -with(density) { 680.dp.toPx() }

        val dampedOverPullPx = if (overPullUp.value > 0f) {
            maxOverPullPx * tanh(overPullUp.value / (maxOverPullPx * 2.2f))
        } else {
            0f
        }
        val renderedHeightPx = if (canToggleDetent) {
            (detentHeightPx.value + dampedOverPullPx).coerceIn(midPx, highPx + maxOverPullPx)
        } else {
            measuredSingleHeightPx
        }
        val currentOffsetPx = (offsetY.value - if (!canToggleDetent) dampedOverPullPx else 0f)
            .coerceAtLeast(-maxOverPullPx)

        val progressHeight = (if (phase == SheetLifecyclePhase.DISMISSING) dismissLockedHeightPx else renderedHeightPx)
            .coerceAtLeast(1f)
        val dismissRatio = (1f - (offsetY.value / progressHeight)).coerceIn(0f, 1f)
        val detentRatio = if (canToggleDetent && highPx > midPx) {
            ((detentHeightPx.value - midPx) / (highPx - midPx)).coerceIn(0f, 1f)
        } else {
            0f
        }
        val effectiveFraction = (dismissRatio * (1f + 0.10f * detentRatio)).coerceIn(0f, 1.12f)

        SideEffect {
            coordinator?.setProgress(entryId, if (visible) effectiveFraction else 0f)
        }

        fun applyRawDragDelta(deltaY: Float): Float {
            if (latestBusy) {
                // While saving, provide tight rubber-band feedback around resting anchor without dismissing.
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    val resisted = (offsetY.value + deltaY * 0.18f).coerceIn(0f, with(density) { 26.dp.toPx() })
                    offsetY.snapTo(resisted)
                }
                return deltaY
            }
            if (phase != SheetLifecyclePhase.DRAGGING) {
                claimMotion(SheetLifecyclePhase.DRAGGING)
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    offsetY.stop()
                    detentHeightPx.stop()
                    overPullUp.stop()
                }
            }
            var remaining = deltaY
            val curOffset = offsetY.value
            val curHeight = detentHeightPx.value
            val curOver = overPullUp.value

            if (remaining < 0f) {
                // Dragging upward: first raise sheet offset back to 0, then expand detent mid -> high, then over-pull.
                var nextOffset = curOffset
                var nextHeight = curHeight
                var nextOver = curOver
                if (nextOffset > 0f) {
                    val consumeOffset = minOf(-remaining, nextOffset)
                    nextOffset -= consumeOffset
                    remaining += consumeOffset
                }
                if (remaining < 0f && latestCanToggle && nextHeight < latestHighPx) {
                    val room = latestHighPx - nextHeight
                    val consumeHeight = minOf(-remaining, room)
                    nextHeight += consumeHeight
                    remaining += consumeHeight
                }
                if (remaining < 0f) {
                    nextOver = (nextOver + (-remaining)).coerceAtMost(maxOverPullPx * 4f)
                }
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    offsetY.snapTo(nextOffset)
                    detentHeightPx.snapTo(nextHeight)
                    overPullUp.snapTo(nextOver)
                }
            } else if (remaining > 0f) {
                // Dragging downward: first release over-pull, then collapse high -> mid, then translate offscreen.
                var nextOffset = curOffset
                var nextHeight = curHeight
                var nextOver = curOver
                if (nextOver > 0f) {
                    val consumeOver = minOf(remaining, nextOver)
                    nextOver -= consumeOver
                    remaining -= consumeOver
                }
                if (remaining > 0f && latestCanToggle && nextHeight > latestMidPx && nextOffset <= 0.5f) {
                    val room = nextHeight - latestMidPx
                    val consumeHeight = minOf(remaining, room)
                    nextHeight -= consumeHeight
                    remaining -= consumeHeight
                }
                if (remaining > 0f) {
                    nextOffset = (nextOffset + remaining).coerceAtLeast(0f)
                }
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    offsetY.snapTo(nextOffset)
                    detentHeightPx.snapTo(nextHeight)
                    overPullUp.snapTo(nextOver)
                }
                val activeThreshold = handleDismissThresholdPx.coerceAtMost(progressHeight * 0.36f)
                if (!firedMidDragHaptic && nextOffset >= activeThreshold) {
                    firedMidDragHaptic = true
                    haptics.detent("sheet-threshold-${++hapticKeyCounter}")
                }
            }
            return deltaY
        }

        fun effectiveFlingVelocityY(fallbackY: Float): Float =
            if (abs(rootReleaseVelocityY) > 1f) rootReleaseVelocityY else fallbackY

        fun finishDragGesture(velocityY: Float, cancelled: Boolean = false) {
            if (latestBusy) {
                settleToDetent(expanded)
                return
            }
            val midHighBoundary = (latestMidPx + latestHighPx) * 0.5f
            if (cancelled) {
                // Cancelled gesture returns smoothly to nearest valid detent rather than dismissing unexpectedly.
                val targetExpanded = latestCanToggle && detentHeightPx.value >= midHighBoundary
                settleToDetent(targetExpanded)
                return
            }
            val curOffset = offsetY.value
            val curHeight = detentHeightPx.value
            val activeDismissThreshold = handleDismissThresholdPx.coerceAtMost(progressHeight * 0.36f)

            if (curOffset > 0.5f) {
                if (velocityY < flingUpVelocityPx && curOffset < progressHeight * 0.92f) {
                    val jumpToHigh = latestCanToggle && velocityY < flingUpVelocityPx * 1.65f && curOffset < progressHeight * 0.28f
                    settleToDetent(targetExpanded = jumpToHigh || curHeight >= midHighBoundary, initialVelocityY = velocityY)
                } else if (curOffset > activeDismissThreshold || velocityY > flingDismissVelocityPx) {
                    dismissWithMotion(velocityY)
                } else {
                    settleToDetent(targetExpanded = latestCanToggle && curHeight >= midHighBoundary, initialVelocityY = velocityY)
                }
                return
            }

            if (velocityY > flingDismissVelocityPx) {
                if (latestCanToggle && expanded && velocityY < flingDismissVelocityPx * 1.55f) {
                    haptics.dragLand("sheet-collapse-${++hapticKeyCounter}")
                    settleToDetent(targetExpanded = false, initialVelocityY = velocityY)
                } else {
                    dismissWithMotion(velocityY)
                }
                return
            }

            if (latestCanToggle) {
                val targetExpanded = when {
                    velocityY < flingUpVelocityPx -> true
                    curHeight >= midHighBoundary -> true
                    else -> false
                }
                if (targetExpanded != expanded) {
                    haptics.dragLand(if (targetExpanded) "sheet-expand-${++hapticKeyCounter}" else "sheet-collapse-${++hapticKeyCounter}")
                }
                settleToDetent(targetExpanded = targetExpanded, initialVelocityY = velocityY)
            } else {
                settleToDetent(targetExpanded = false, initialVelocityY = velocityY)
            }
        }

        // Scrollable child consumes internal scroll first; at top edge downward drag transfers to sheet.
        // Reverse-pull during dismissal or active sheet drag reclaims upward scroll in onPreScroll first.
        val sheetNestedScroll = remember(entryId) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (source != NestedScrollSource.UserInput) return Offset.Zero
                    if (latestBusy) return Offset.Zero
                    val recoveringSheet = phase == SheetLifecyclePhase.DISMISSING || offsetY.value > 0.5f
                    if (recoveringSheet && available.y != 0f) {
                        nestedSheetDragActive = true
                        applyRawDragDelta(available.y)
                        return Offset(0f, available.y)
                    }
                    if (nestedSheetDragActive && available.y < 0f && latestCanToggle && detentHeightPx.value < latestHighPx - 0.5f) {
                        applyRawDragDelta(available.y)
                        return Offset(0f, available.y)
                    }
                    return Offset.Zero
                }

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (source != NestedScrollSource.UserInput) return Offset.Zero
                    if (available.y > 0.5f || (available.y < -0.5f && latestCanToggle && detentHeightPx.value < latestHighPx - 0.5f)) {
                        nestedSheetDragActive = true
                        applyRawDragDelta(available.y)
                        return Offset(0f, available.y)
                    }
                    return Offset.Zero
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (nestedSheetDragActive || phase == SheetLifecyclePhase.DRAGGING || offsetY.value > 0.5f || overPullUp.value > 0.5f) {
                        nestedSheetDragActive = false
                        finishDragGesture(effectiveFlingVelocityY(available.y))
                        return available
                    }
                    return Velocity.Zero
                }

                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                    if (nestedSheetDragActive || offsetY.value > 0.5f || overPullUp.value > 0.5f) {
                        nestedSheetDragActive = false
                        finishDragGesture(effectiveFlingVelocityY(available.y))
                    }
                    return Velocity.Zero
                }
            }
        }

        // Consume every back press (including repeated presses) while sheet remains mounted on screen.
        AppPredictiveBackHandler(
            enabled = visible && !dismissDispatched,
            onStart = {
                if (latestMotion && !latestBusy && phase != SheetLifecyclePhase.DISMISSING) {
                    backBaselineOffsetPx = offsetY.value
                    claimMotion(SheetLifecyclePhase.PREDICTIVE_BACK)
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        offsetY.stop()
                        detentHeightPx.stop()
                        overPullUp.stop()
                    }
                }
            },
            onProgress = { backEvent ->
                if (latestMotion && !latestBusy && phase == SheetLifecyclePhase.PREDICTIVE_BACK) {
                    val progress = backEvent.progress.coerceIn(0f, 1f)
                    val previewShift = backBaselineOffsetPx + progress * with(density) { 120.dp.toPx() }
                    scope.launch(start = CoroutineStart.UNDISPATCHED) { offsetY.snapTo(previewShift) }
                }
            },
            onCancel = {
                if (phase == SheetLifecyclePhase.PREDICTIVE_BACK) {
                    settleToDetent(expanded)
                }
            },
            onBack = {
                when {
                    latestBusy -> settleToDetent(expanded)
                    phase == SheetLifecyclePhase.DISMISSING -> Unit // Ignore repeat back while already closing.
                    else -> dismissWithMotion()
                }
            },
        )

        // Backdrop scrim calibrated strictly from the same position state as height & background shrink.
        val scrimAlpha = ((0.34f + 0.06f * detentRatio) * dismissRatio).coerceIn(0f, 0.42f)
        Box(
            Modifier
                .fillMaxSize()
                .background(RoomColors.Ink.copy(alpha = scrimAlpha))
                .pointerInput(entryId) {
                    detectTapGestures {
                        if (!latestBusy && phase != SheetLifecyclePhase.DISMISSING && !dismissDispatched) {
                            dismissWithMotion()
                        }
                    }
                },
        )

        val heightModifier = if (canToggleDetent) {
            Modifier.height(with(density) { renderedHeightPx.toDp() })
        } else {
            Modifier.heightIn(max = maxAvailableDp)
        }

        Surface(
            modifier = modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .then(heightModifier)
                .testTag("app-bottom-sheet-surface")
                .onSizeChanged { size ->
                    if (!canToggleDetent && size.height > 0) {
                        measuredSingleHeightPx = size.height.toFloat()
                    }
                }
                .pointerInput(entryId) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (down.position.y < offsetY.value - 12f) return@awaitEachGesture
                        nestedSheetDragActive = false
                        val wasDismissing = phase == SheetLifecyclePhase.DISMISSING
                        val wasEntering = phase == SheetLifecyclePhase.ENTERING
                        if (wasDismissing || wasEntering) {
                            claimMotion(SheetLifecyclePhase.DRAGGING)
                            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                offsetY.stop()
                                detentHeightPx.stop()
                                overPullUp.stop()
                            }
                        }
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        var dragStarted = false
                        var cancelledByParent = false
                        val postSlop = awaitVerticalTouchSlopOrCancellation(down.id) { change, overSlop ->
                            change.consume()
                            dragStarted = true
                            firedMidDragHaptic = false
                            if (phase != SheetLifecyclePhase.DRAGGING) {
                                claimMotion(SheetLifecyclePhase.DRAGGING)
                                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                    offsetY.stop()
                                    detentHeightPx.stop()
                                    overPullUp.stop()
                                }
                            }
                            tracker.addPosition(change.uptimeMillis, change.position)
                            applyRawDragDelta(overSlop)
                        }
                        if (postSlop != null) {
                            var activeId = postSlop.id
                            var activePressed = postSlop.pressed
                            while (activePressed) {
                                val event = awaitPointerEvent()
                                val move = event.changes.firstOrNull { it.id == activeId }
                                if (move == null) {
                                    cancelledByParent = event.changes.any { it.pressed }
                                    break
                                }
                                if (!move.pressed) {
                                    tracker.addPosition(move.uptimeMillis, move.position)
                                    break
                                }
                                if (move.isConsumed) {
                                    cancelledByParent = true
                                    break
                                }
                                val dy = move.positionChange().y
                                if (dy != 0f) {
                                    move.consume()
                                    tracker.addPosition(move.uptimeMillis, move.position)
                                    applyRawDragDelta(dy)
                                }
                                activeId = move.id
                                activePressed = move.pressed
                            }
                            val velocityY = effectiveFlingVelocityY(tracker.calculateVelocity().y)
                            finishDragGesture(velocityY, cancelled = cancelledByParent)
                        } else if (dragStarted) {
                            finishDragGesture(0f, cancelled = true)
                        } else {
                            // Child (e.g. LazyColumn) or tap may have taken the pointer.
                            while (currentEvent.changes.any { it.pressed }) {
                                awaitPointerEvent()
                            }
                            if ((wasDismissing || wasEntering) && !nestedSheetDragActive && phase == SheetLifecyclePhase.DRAGGING) {
                                finishDragGesture(0f)
                            }
                        }
                    }
                }
                .offset { IntOffset(0, currentOffsetPx.roundToInt()) }
                .nestedScroll(sheetNestedScroll),
            shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
            color = lerp(RoomColors.Paper, Color.White, 0.45f),
            border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.82f)),
            shadowElevation = 14.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                            .union(WindowInsets.ime.only(WindowInsetsSides.Bottom))
                    ),
            ) {
                // Tactile rounded drag handle + accessible toggle fallback when multi-detent active.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .testTag("app-bottom-sheet-handle")
                        .semantics {
                            contentDescription = when {
                                busy -> "正在保存，请稍候"
                                canToggleDetent && !expanded -> "面板控制条，轻点可展开更高视图"
                                canToggleDetent && expanded -> "面板控制条，轻点可收起至中挡"
                                else -> "面板控制条，下滑可关闭"
                            }
                        }
                        .pointerInput(entryId) {
                            detectTapGestures {
                                latestOnDragHandleClick?.invoke() ?: run {
                                    if (latestCanToggle && !latestBusy) {
                                        val next = !expanded
                                        haptics.detent("sheet-tap-detent-${++hapticKeyCounter}")
                                        settleToDetent(next)
                                    } else if (!latestBusy) {
                                        dismissWithMotion()
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .width(38.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(99.dp))
                            .background(RoomColors.Ink.copy(alpha = 0.18f)),
                    )
                }
                content(expanded) {
                    if (latestCanToggle && !latestBusy) {
                        val next = !expanded
                        haptics.detent("sheet-toggle-${++hapticKeyCounter}")
                        settleToDetent(next)
                    }
                }
            }
        }
    }
}

/**
 * Centered Swiss paper confirmation dialog with calibrated scale + backdrop scrim.
 */
@Composable
internal fun AppCenterConfirmDialog(
    onDismissRequest: () -> Unit,
    dismissOnBackPress: Boolean = true,
    dismissOnClickOutside: Boolean = true,
    content: @Composable () -> Unit,
) {
    val motion = LocalPhysicalMotion.current
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    val progress by animateFloatAsState(
        targetValue = if (visible || !motion) 1f else 0f,
        animationSpec = if (motion) tween(durationMillis = 180, easing = AppNavEasing) else tween(0),
        label = "center-confirm-dialog",
    )

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = dismissOnBackPress,
            dismissOnClickOutside = dismissOnClickOutside,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(RoomColors.Ink.copy(alpha = 0.36f * progress))
                .pointerInput(dismissOnClickOutside) {
                    detectTapGestures {
                        if (dismissOnClickOutside) onDismissRequest()
                    }
                }
                .padding(horizontal = 28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 420.dp)
                    .graphicsLayer {
                        val s = 0.94f + 0.06f * progress
                        scaleX = s
                        scaleY = s
                        alpha = progress
                    }
                    .pointerInput(Unit) { detectTapGestures { } },
                shape = RoundedCornerShape(18.dp),
                color = lerp(RoomColors.Paper, Color.White, 0.72f),
                border = BorderStroke(0.8.dp, RoomColors.Line.copy(alpha = 0.75f)),
                shadowElevation = 12.dp,
            ) {
                Box(Modifier.padding(22.dp)) {
                    content()
                }
            }
        }
    }
}

@Composable
internal fun AppConfirmation(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    cancelLabel: String = "取消",
    busy: Boolean = false,
    error: String? = null,
    destructive: Boolean = false,
    confirmEnabled: Boolean = true,
    details: (@Composable () -> Unit)? = null,
) {
    AppCenterConfirmDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        dismissOnBackPress = !busy,
        dismissOnClickOutside = !busy,
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = RoomColors.Ink)
            Text(message, fontSize = 14.sp, lineHeight = 21.sp, color = RoomColors.Muted)
            details?.invoke()
            error?.let {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(RoomColors.Red.copy(alpha = 0.08f))
                        .border(0.8.dp, RoomColors.Red.copy(alpha = 0.22f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                ) {
                    Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SwissTextButton(
                    onDismiss,
                    enabled = !busy,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(cancelLabel, color = RoomColors.Muted)
                }
                SwissButton(
                    onConfirm,
                    enabled = confirmEnabled && !busy,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (destructive) RoomColors.Red else RoomColors.Ink,
                        contentColor = RoomColors.Paper,
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp, pressedElevation = 0.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                ) { Text(confirmLabel, fontWeight = FontWeight.Medium) }
            }
        }
    }
}

/**
 * Confirmation banner featuring an animated checkmark stroke draw before holding crisp status text.
 */
@Composable
internal fun AppSavedConfirmNotice(
    message: String?,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val motion = LocalPhysicalMotion.current
    val haptics = LocalAppHaptics.current ?: rememberAppHaptics()
    var rememberedMessage by remember { mutableStateOf(message.orEmpty()) }
    SideEffect {
        if (!message.isNullOrBlank()) rememberedMessage = message
    }

    val drawProgress = remember { Animatable(0f) }
    LaunchedEffect(visible, message, motion) {
        if (visible && !message.isNullOrBlank()) {
            haptics.confirm("saved-$message")
            if (motion) {
                drawProgress.snapTo(0f)
                drawProgress.animateTo(1f, tween(durationMillis = 230, easing = FastOutSlowInEasing))
            } else {
                drawProgress.snapTo(1f)
            }
        }
    }

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (motion) {
            fadeIn(tween(150, easing = AppNavEasing)) +
                scaleIn(tween(190, easing = AppNavEasing), initialScale = 0.94f)
        } else {
            EnterTransition.None
        },
        exit = if (motion) {
            fadeOut(tween(110, easing = AppNavEasing)) +
                scaleOut(tween(110, easing = AppNavEasing), targetScale = 0.96f)
        } else {
            ExitTransition.None
        },
    ) {
        Surface(
            color = RoomColors.Navy,
            shape = RoundedCornerShape(6.dp),
            shadowElevation = 2.dp,
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Canvas(Modifier.size(16.dp)) {
                    val w = size.width
                    val h = size.height
                    val full = Path().apply {
                        moveTo(w * 0.14f, h * 0.54f)
                        lineTo(w * 0.41f, h * 0.80f)
                        lineTo(w * 0.88f, h * 0.22f)
                    }
                    val segment = Path()
                    val pm = PathMeasure()
                    pm.setPath(full, false)
                    pm.getSegment(0f, pm.length * drawProgress.value.coerceIn(0f, 1f), segment, true)
                    drawPath(
                        path = segment,
                        color = RoomColors.Paper,
                        style = Stroke(width = 1.9.dp.toPx(), cap = StrokeCap.Square, join = StrokeJoin.Miter),
                    )
                }
                Text(message ?: rememberedMessage, color = RoomColors.Paper)
            }
        }
    }
}
