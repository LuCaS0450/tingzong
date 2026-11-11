package cn.musicpilgrimage.app.ui

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Window
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import kotlin.math.roundToInt

internal val LocalArchiveDrawn = staticCompositionLocalOf<() -> Unit> { {} }
internal val LocalArchiveBack = staticCompositionLocalOf<(() -> Unit)?> { null }

internal fun isArchiveHandoffPath(path: String?) = when (path?.substringBefore('/')) {
    "album", "song", "place" -> true
    else -> false
}

internal class ArchiveHandoff {
    data class Frame(val image: ImageBitmap, val popping: Boolean)

    private val handler = Handler(Looper.getMainLooper())
    private var request = 0L
    private var awaitedEntry by mutableStateOf<String?>(null)
    var frame by mutableStateOf<Frame?>(null)
        private set
    var holding by mutableStateOf(false)
        private set

    fun holds(entryId: String) = holding && awaitedEntry == entryId

    fun navigate(
        window: Window?, bounds: Rect?, popping: Boolean, currentEntry: () -> String?,
        changeRoute: () -> Unit,
    ) {
        val source = currentEntry()
        val token = ++request
        if (window == null || bounds == null || bounds.isEmpty || source == null) {
            changeRoute()
            return
        }

        val scale = minOf(1f, 1600f / maxOf(bounds.width(), bounds.height()))
        val bitmap = Bitmap.createBitmap((bounds.width() * scale).roundToInt().coerceAtLeast(1),
            (bounds.height() * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        var delivered = false
        fun finish(result: Bitmap?) {
            if (delivered || token != request || currentEntry() != source) {
                result?.recycle()
                return
            }
            delivered = true
            if (result != null) {
                frame = Frame(result.asImageBitmap(), popping)
                holding = true
            }
            changeRoute()
            val target = currentEntry()
            if (result == null || target == source || target == null) {
                holding = false
                awaitedEntry = null
            } else {
                awaitedEntry = target
                // Recover even if the destination itself could not resolve an archive record.
                handler.postDelayed({ if (token == request) drawn(target) }, 4500)
            }
        }

        try {
            PixelCopy.request(window, bounds, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) finish(bitmap)
                else { bitmap.recycle(); finish(null) }
            }, handler)
            handler.postDelayed({ finish(null) }, 150)
        } catch (_: IllegalArgumentException) {
            bitmap.recycle()
            finish(null)
        }
    }

    fun drawn(entryId: String) {
        if (holds(entryId)) {
            awaitedEntry = null
            holding = false
        }
    }

    fun routeChanged(entryId: String?) {
        if (holding && entryId != awaitedEntry) {
            awaitedEntry = null
            holding = false
        }
    }

    fun release(expected: Frame?) {
        if (!holding && frame === expected) frame = null
    }
}

@Composable internal fun ArchiveHandoffLayer(handoff: ArchiveHandoff, motion: Boolean, modifier: Modifier) {
    val frame = handoff.frame
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = handoff.holding
    val exit = if (motion) fadeOut(tween(190, easing = appMotionEasing)) +
        slideOutHorizontally(tween(190, easing = appMotionEasing)) { width ->
            if (frame?.popping == true) width / 18 else -width / 18
        } else ExitTransition.None

    AnimatedVisibility(visibility, modifier, enter = EnterTransition.None, exit = exit) {
        if (frame != null) Image(frame.image, contentDescription = null, contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize().blockArchiveFrameTouches(handoff.holding))
    }
    LaunchedEffect(visibility.isIdle, visibility.currentState, frame) {
        if (visibility.isIdle && !visibility.currentState) handoff.release(frame)
    }
}

private fun Modifier.blockArchiveFrameTouches(blockTouches: Boolean): Modifier =
    pointerInput(blockTouches) {
        if (blockTouches) awaitPointerEventScope {
            while (true) awaitPointerEvent().changes.forEach { it.consume() }
        }
    }
