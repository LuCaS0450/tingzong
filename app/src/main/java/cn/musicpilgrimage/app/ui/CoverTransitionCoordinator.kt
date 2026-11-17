package cn.musicpilgrimage.app.ui

import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector4D
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import cn.musicpilgrimage.app.ui.room.coverSleeve
import cn.musicpilgrimage.app.ui.room.sleeveShadow
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import java.lang.ref.WeakReference
import kotlin.math.roundToInt
import kotlin.time.TimeSource

internal const val DEFAULT_COVER_WAIT_TIMEOUT_MS = 1200L
internal const val DEFAULT_COVER_REVEAL_CONFIRM_TIMEOUT_MS = 250L
// Swiss archive CSS uses a 360px layout with a 32px top inset, 56px header and 16px hero gap.
internal fun archiveCoverTopPx(viewportWidth:Float):Float = viewportWidth * (104f / 360f)
internal const val RELEASE_COVER_SIDE_INSET = 16f

private val RectVectorConverter: TwoWayConverter<Rect, AnimationVector4D> = TwoWayConverter(
    convertToVector = { AnimationVector4D(it.left, it.top, it.right, it.bottom) },
    convertFromVector = { Rect(it.v1, it.v2, it.v3, it.v4) },
)

private fun lerpRect(from: Rect, to: Rect, fraction: Float): Rect = Rect(
    left = from.left + (to.left - from.left) * fraction,
    top = from.top + (to.top - from.top) * fraction,
    right = from.right + (to.right - from.right) * fraction,
    bottom = from.bottom + (to.bottom - from.bottom) * fraction,
)

/**
 * Shared element handoff coordinator bridging native Compose cards/cells with WebView hero media.
 * Binds each enter/return session to nav entry IDs, layout/orientation versions, render tokens,
 * and drawn-frame confirmation so stale callbacks and premature reveals cannot happen.
 */
@Stable
internal class CoverTransitionCoordinator {
    enum class Mode {
        ENTERING,
        RETURNING,
        RETURNING_INTERACTIVE,
        RETURNING_CANCELING,
    }

    data class SourceRecord(
        val key: String,
        val ownerEntryId: String?,
        val page: Int,
        val layoutVersion: Long,
        val orientation: Int,
        val model: Any?,
        val memoryCacheKey: String?,
        val aspectRatio: Float,
        val boundsInRoot: Rect,
        val sleeve: Boolean,
        val imageLoaded: Boolean,
        val visible: Boolean = true,
        val coverBitmap: WeakReference<ImageBitmap>? = null,
    )

    data class DestinationHeroRecord(
        val identity: String,
        val navEntryId: String?,
        val orientation: Int,
        val rectInRoot: Rect,
        val decoded: Boolean,
        val drawn: Boolean,
        val failed: Boolean,
        val renderToken: Long,
        val webInstanceId: Long = 0L,
        val loadAttempt: Int = 0,
        val generation: Long = 0L,
        val decodedAtMs: Long?,
        val drawnAtMs: Long?,
    )

    data class ActiveMorph(
        val key: String,
        val route: String,
        val releaseId: String = "",
        val sourceNavEntryId: String?,
        val targetNavEntryId: String?,
        val identity: String,
        val model: Any?,
        val sourceMemoryCacheKey: String?,
        val aspectRatio: Float,
        val fromRect: Rect,
        val toRect: Rect?,
        val sleeve: Boolean,
        val mode: Mode,
        val generation: Long,
        val orientation: Int,
        val webInstanceId: Long = 0L,
        val loadAttempt: Int = 0,
        val renderToken: Long,
        val destinationDecoded: Boolean,
        val destinationDrawn: Boolean,
        val destinationFailed: Boolean,
        val animFinished: Boolean,
        val revealDispatched: Boolean,
        val revealConfirmed: Boolean,
        val timedOut: Boolean,
        val destinationWaitTimedOut: Boolean = false,
        val degradedFallback: Boolean = false,
        val revealConfirmTimedOut: Boolean = false,
        val interactiveProgress: Float,
        val lastVisualProgress: Float,
        val lastVisualRect: Rect? = null,
        val decodedAtMs: Long?,
        val drawnAtMs: Long?,
        val degradeRequestedAtMs: Long? = null,
        val overlayClearedAtMs: Long?,
        val startedAt: TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow(),
        val coverBitmap: ImageBitmap? = null,
        val nativeDestination: Boolean = false,
    ) {
        val destinationReady: Boolean
            get() = destinationDrawn

        val isReturnSession: Boolean
            get() = mode == Mode.RETURNING || mode == Mode.RETURNING_INTERACTIVE || mode == Mode.RETURNING_CANCELING
    }

    private val sources = mutableStateMapOf<String, SourceRecord>()
    private val destinationHeroes = mutableStateMapOf<String, DestinationHeroRecord>()
    private val releasedEntryIds = HashSet<String>()
    private val destroyedWebInstanceIds = HashSet<Long>()
    private val activeSettledEntryByIdentity = HashMap<String, String>()
    private val activeWebByEntry = HashMap<String, Long>()
    private val entryGeneration = HashMap<String, Long>()
    private val recoveringEntries = mutableStateMapOf<String, Unit>()

    fun needsCoverRecovery(entryId: String?): Boolean = entryId != null && entryId in recoveringEntries

    fun clearCoverRecovery(entryId: String?) {
        if (entryId != null) recoveringEntries.remove(entryId)
    }

    fun markCoverRecovery(entryId: String?) {
        if (entryId != null) recoveringEntries[entryId] = Unit
    }

    private var activeShelfEntryId: String? = null
    private var activeShelfPage by mutableIntStateOf(0)
    private var activeShelfLayoutVersion by mutableLongStateOf(0L)

    var waitTimeoutMs: Long = DEFAULT_COVER_WAIT_TIMEOUT_MS
    var revealConfirmTimeoutMs: Long = DEFAULT_COVER_REVEAL_CONFIRM_TIMEOUT_MS

    var activeMorph by mutableStateOf<ActiveMorph?>(null)
        private set
    var viewportBoundsInRoot by mutableStateOf(Rect.Zero)
        private set
    var currentOrientation by mutableIntStateOf(Configuration.ORIENTATION_UNDEFINED)
        private set
    var generationCounter by mutableLongStateOf(0L)
        private set
    var lastOverlayClearEvent by mutableStateOf<String?>(null)
        private set

    fun updateViewportBounds(bounds: Rect, orientation: Int = currentOrientation) {
        if (orientation != Configuration.ORIENTATION_UNDEFINED && currentOrientation != orientation) {
            val previous = currentOrientation
            currentOrientation = orientation
            if (previous != Configuration.ORIENTATION_UNDEFINED) {
                cancelAll()
            }
        }
        if (bounds.width > 1f && bounds.height > 1f) viewportBoundsInRoot = bounds
    }

    fun updateShelfState(ownerEntryId: String?, page: Int, layoutVersion: Long) {
        activeShelfEntryId = ownerEntryId
        activeShelfPage = page
        activeShelfLayoutVersion = layoutVersion
    }

    fun updateSource(
        key: String,
        model: Any?,
        aspectRatio: Float,
        bounds: Rect,
        sleeve: Boolean,
        visible: Boolean = true,
        ownerEntryId: String? = activeShelfEntryId,
        page: Int = activeShelfPage,
        layoutVersion: Long = activeShelfLayoutVersion,
        orientation: Int = currentOrientation,
        memoryCacheKey: String? = null,
        imageLoaded: Boolean = model != null,
        coverBitmap: ImageBitmap? = null,
    ) {
        if (bounds.width <= 1f || bounds.height <= 1f) return
        val record = SourceRecord(
            key = key,
            ownerEntryId = ownerEntryId,
            page = page,
            layoutVersion = layoutVersion,
            orientation = orientation,
            model = model,
            memoryCacheKey = memoryCacheKey,
            aspectRatio = aspectRatio,
            boundsInRoot = bounds,
            sleeve = sleeve,
            imageLoaded = imageLoaded,
            visible = visible,
            coverBitmap = coverBitmap?.let(::WeakReference),
        )
        sources[key] = record
        val cur = activeMorph
        if (cur != null && cur.isReturnSession && cur.key == key && visible &&
            (cur.targetNavEntryId == null || cur.targetNavEntryId == ownerEntryId) &&
            page == activeShelfPage
        ) {
            activeMorph = cur.copy(toRect = bounds)
        }
    }

    fun removeSourceIfInactive(key: String) {
        if (activeMorph?.key != key) sources.remove(key)
    }

    fun registerWebInstance(navEntryId: String?, webInstanceId: Long) {
        if (navEntryId == null || webInstanceId <= 0L) return
        if (webInstanceId in destroyedWebInstanceIds) return
        releasedEntryIds.remove(navEntryId)
        activeWebByEntry[navEntryId] = webInstanceId
    }

    private fun isAuthorizedDestinationUpdate(
        route: String?,
        key: String?,
        identity: String,
        navEntryId: String?,
        expectedGeneration: Long?,
        webInstanceId: Long,
        loadAttempt: Int,
        renderToken: Long,
        orientation: Int,
    ): Boolean {
        if (navEntryId != null && navEntryId in releasedEntryIds) return false
        if (webInstanceId > 0L && webInstanceId in destroyedWebInstanceIds) return false
        if (navEntryId != null && webInstanceId > 0L) {
            val activeWeb = activeWebByEntry[navEntryId]
            if (activeWeb != null && activeWeb != webInstanceId) return false
        }
        if (orientation != Configuration.ORIENTATION_UNDEFINED &&
            currentOrientation != Configuration.ORIENTATION_UNDEFINED &&
            orientation != currentOrientation
        ) {
            return false
        }

        val cur = activeMorph
        if (expectedGeneration != null && expectedGeneration > 0L) {
            if (cur != null && cur.generation != expectedGeneration) return false
            if (cur == null && navEntryId != null) {
                val boundGen = entryGeneration[navEntryId]
                if (boundGen != null && boundGen != expectedGeneration) return false
                val settled = activeSettledEntryByIdentity[identity]
                if (settled != null && settled != navEntryId) return false
            }
        }

        if (cur != null && cur.mode == Mode.ENTERING && (route == null || cur.route == route) && (key == null || cur.key == key)) {
            if (navEntryId != null) {
                if (cur.targetNavEntryId != navEntryId) return false
            } else if (expectedGeneration == null && (cur.route != route || cur.key != key)) {
                return false
            }
            if (cur.orientation != Configuration.ORIENTATION_UNDEFINED &&
                orientation != Configuration.ORIENTATION_UNDEFINED &&
                cur.orientation != orientation
            ) {
                return false
            }
            if (cur.webInstanceId > 0L && webInstanceId > 0L && cur.webInstanceId != webInstanceId) return false
            if (loadAttempt > 0 && cur.loadAttempt > 0 && loadAttempt < cur.loadAttempt) return false
            if (loadAttempt == cur.loadAttempt && renderToken > 0L && cur.renderToken > 0L && renderToken < cur.renderToken) return false
        }

        if (cur != null && cur.isReturnSession && cur.identity == identity) {
            if (navEntryId != null && cur.sourceNavEntryId != null && navEntryId != cur.sourceNavEntryId) return false
        }

        val prev = destinationHeroes[identity]
        if (prev != null) {
            if (navEntryId != null && prev.navEntryId != null && prev.navEntryId != navEntryId) {
                val allowedTransfer = prev.navEntryId in releasedEntryIds ||
                    activeSettledEntryByIdentity[identity] == navEntryId ||
                    (cur?.mode == Mode.ENTERING && cur.targetNavEntryId == navEntryId)
                if (!allowedTransfer) return false
            } else if (webInstanceId > 0L && prev.webInstanceId > 0L && prev.webInstanceId != webInstanceId) {
                val allowedWebTransfer = prev.webInstanceId in destroyedWebInstanceIds ||
                    (cur?.mode == Mode.ENTERING && cur.targetNavEntryId == navEntryId && (cur.webInstanceId == 0L || cur.webInstanceId == webInstanceId))
                if (!allowedWebTransfer) return false
            } else if (prev.navEntryId == navEntryId && prev.orientation == orientation) {
                if (loadAttempt > 0 && prev.loadAttempt > 0 && loadAttempt < prev.loadAttempt) return false
                if (loadAttempt == prev.loadAttempt && renderToken > 0L && prev.renderToken > 0L && renderToken < prev.renderToken) return false
            }
        }
        return true
    }

    fun updateDestinationHeroRect(
        identity: String,
        rectInRoot: Rect?,
        navEntryId: String? = null,
        orientation: Int = currentOrientation,
        renderToken: Long = 0L,
        decoded: Boolean = false,
        drawn: Boolean = false,
        failed: Boolean = false,
        timestampMs: Long? = null,
        webInstanceId: Long = 0L,
        loadAttempt: Int = 0,
        expectedGeneration: Long? = null,
        route: String? = null,
        key: String? = null,
    ) {
        if (!isAuthorizedDestinationUpdate(
                route = route,
                key = key,
                identity = identity,
                navEntryId = navEntryId,
                expectedGeneration = expectedGeneration,
                webInstanceId = webInstanceId,
                loadAttempt = loadAttempt,
                renderToken = renderToken,
                orientation = orientation,
            )
        ) {
            return
        }
        if (rectInRoot == null || rectInRoot.width <= 1f || rectInRoot.height <= 1f) {
            destinationHeroes.remove(identity)
            return
        }
        val prev = destinationHeroes[identity]
        val keepTokens = prev != null &&
            prev.navEntryId == navEntryId &&
            prev.webInstanceId == webInstanceId &&
            prev.loadAttempt == loadAttempt &&
            prev.renderToken == renderToken &&
            prev.orientation == orientation
        destinationHeroes[identity] = DestinationHeroRecord(
            identity = identity,
            navEntryId = navEntryId ?: prev?.navEntryId,
            orientation = orientation,
            rectInRoot = rectInRoot,
            decoded = decoded || (keepTokens && prev?.decoded == true),
            drawn = (decoded && drawn) || (keepTokens && prev?.drawn == true),
            failed = failed && !(decoded && drawn),
            renderToken = renderToken,
            webInstanceId = if (webInstanceId > 0L) webInstanceId else (if (keepTokens) prev?.webInstanceId ?: 0L else 0L),
            loadAttempt = if (loadAttempt > 0) loadAttempt else (if (keepTokens) prev?.loadAttempt ?: 0 else 0),
            generation = expectedGeneration ?: prev?.generation ?: 0L,
            decodedAtMs = if (decoded) (if (keepTokens) prev?.decodedAtMs else null) ?: timestampMs else if (keepTokens) prev?.decodedAtMs else null,
            drawnAtMs = if (decoded && drawn) (if (keepTokens) prev?.drawnAtMs else null) ?: timestampMs else if (keepTokens) prev?.drawnAtMs else null,
        )
    }

    fun destinationHeroRect(identity: String): Rect? = destinationHeroes[identity]?.rectInRoot

    fun sourceFor(key: String): SourceRecord? = sources[key]

    fun estimateAlbumHeroRect(aspectRatio: Float): Rect? {
        val vp = viewportBoundsInRoot
        if (vp.width <= 1f) return null
        val top = vp.top + archiveCoverTopPx(vp.width)
        val inset = vp.width * (RELEASE_COVER_SIDE_INSET / 360f)
        val height = ((vp.width - 2 * inset) / aspectRatio.coerceIn(0.35f,3f)).coerceAtLeast(1f)
        return Rect(vp.left + inset, top, vp.right - inset, top + height)
    }

    fun startEnter(
        key: String,
        route: String,
        predictedToRect: Rect?,
        physicalMotion: Boolean,
        sourceNavEntryId: String? = activeShelfEntryId,
        releaseId: String = "",
    ): Boolean {
        if (!physicalMotion) {
            cancelAll()
            return false
        }
        val src = sources[key] ?: run {
            cancelAll()
            return false
        }
        val matchesOrientation = currentOrientation == Configuration.ORIENTATION_UNDEFINED ||
            src.orientation == Configuration.ORIENTATION_UNDEFINED ||
            src.orientation == currentOrientation
        val matchesEntry = sourceNavEntryId == null || src.ownerEntryId == null || src.ownerEntryId == sourceNavEntryId
        if (!src.visible || !src.imageLoaded || src.model == null || !matchesOrientation || !matchesEntry ||
            src.boundsInRoot.width <= 1f || src.boundsInRoot.height <= 1f
        ) {
            cancelAll()
            return false
        }
        val gen = ++generationCounter
        val resolvedReleaseId = releaseId.ifBlank {
            route.takeIf { it.startsWith("album/") }?.removePrefix("album/")
                ?: key.removePrefix("release:")
        }
        val identity = route.takeIf { it.startsWith("album/") }?.let { "album:${it.removePrefix("album/")}" } ?: key
        activeMorph = ActiveMorph(
            key = key,
            route = route,
            releaseId = resolvedReleaseId,
            sourceNavEntryId = sourceNavEntryId ?: src.ownerEntryId,
            targetNavEntryId = null,
            identity = identity,
            model = src.model,
            sourceMemoryCacheKey = src.memoryCacheKey,
            coverBitmap = src.coverBitmap?.get(),
            aspectRatio = src.aspectRatio,
            fromRect = src.boundsInRoot,
            toRect = predictedToRect,
            sleeve = src.sleeve,
            mode = Mode.ENTERING,
            generation = gen,
            orientation = currentOrientation,
            webInstanceId = 0L,
            loadAttempt = 0,
            renderToken = 0L,
            destinationDecoded = false,
            destinationDrawn = false,
            destinationFailed = false,
            animFinished = false,
            revealDispatched = false,
            revealConfirmed = false,
            timedOut = false,
            destinationWaitTimedOut = false,
            degradedFallback = false,
            revealConfirmTimedOut = false,
            interactiveProgress = 0f,
            lastVisualProgress = 0f,
            lastVisualRect = src.boundsInRoot,
            decodedAtMs = null,
            drawnAtMs = null,
            degradeRequestedAtMs = null,
            overlayClearedAtMs = null,
        )
        return true
    }

    fun bindDestinationEntry(route: String, key: String, navEntryId: String?): Long? {
        if (navEntryId == null || navEntryId in releasedEntryIds) return null
        val cur = activeMorph
        val existingBoundGen = entryGeneration[navEntryId]
        if (existingBoundGen != null) {
            return if (cur != null && cur.generation == existingBoundGen && cur.targetNavEntryId == navEntryId) existingBoundGen else null
        }
        if (cur != null && cur.mode == Mode.ENTERING && cur.route == route && cur.key == key && cur.targetNavEntryId == null) {
            activeMorph = cur.copy(targetNavEntryId = navEntryId)
            entryGeneration[navEntryId] = cur.generation
            activeSettledEntryByIdentity[cur.identity] = navEntryId
            return cur.generation
        }
        return null
    }

    fun reportDestinationHero(
        route: String,
        key: String,
        heroRectInRoot: Rect?,
        firstPaint: Boolean,
        navEntryId: String? = null,
        identity: String? = null,
        renderToken: Long = 0L,
        decoded: Boolean = firstPaint,
        drawn: Boolean = firstPaint,
        failed: Boolean = false,
        orientation: Int = currentOrientation,
        timestampMs: Long = System.currentTimeMillis(),
        webInstanceId: Long = 0L,
        loadAttempt: Int = 0,
        expectedGeneration: Long? = null,
        nativeDestination: Boolean = false,
    ) {
        val resolvedIdentity = identity ?: key
        if (!isAuthorizedDestinationUpdate(
                route = route,
                key = key,
                identity = resolvedIdentity,
                navEntryId = navEntryId,
                expectedGeneration = expectedGeneration,
                webInstanceId = webInstanceId,
                loadAttempt = loadAttempt,
                renderToken = renderToken,
                orientation = orientation,
            )
        ) {
            return
        }
        if (navEntryId != null) {
            activeSettledEntryByIdentity[resolvedIdentity] = navEntryId
        }
        updateDestinationHeroRect(
            identity = resolvedIdentity,
            rectInRoot = heroRectInRoot,
            navEntryId = navEntryId,
            orientation = orientation,
            renderToken = renderToken,
            decoded = decoded,
            drawn = decoded && drawn,
            failed = failed,
            timestampMs = timestampMs,
            webInstanceId = webInstanceId,
            loadAttempt = loadAttempt,
            expectedGeneration = expectedGeneration,
            route = route,
            key = key,
        )
        val cur = activeMorph ?: return
        if (cur.route != route || cur.key != key || cur.mode != Mode.ENTERING) return
        if (navEntryId != null && cur.targetNavEntryId != navEntryId) return
        if (expectedGeneration != null && expectedGeneration > 0L && cur.generation != expectedGeneration) return
        if (cur.webInstanceId > 0L && webInstanceId > 0L && cur.webInstanceId != webInstanceId) return

        val attemptReset = loadAttempt > 0 && cur.loadAttempt > 0 && loadAttempt > cur.loadAttempt
        val tokenReset = attemptReset || (renderToken > 0L && cur.renderToken != 0L && renderToken > cur.renderToken)
        val nextRect = heroRectInRoot?.takeIf { it.width > 1f && it.height > 1f } ?: cur.toRect
        val nextDecoded = if (tokenReset) decoded else (cur.destinationDecoded || decoded)
        val nextDrawn = if (tokenReset) (decoded && drawn) else (cur.destinationDrawn || (nextDecoded && drawn))
        val nextFailed = failed && !nextDrawn
        if (nextFailed) markCoverRecovery(cur.targetNavEntryId)
        activeMorph = cur.copy(
            targetNavEntryId = cur.targetNavEntryId,
            toRect = nextRect,
            webInstanceId = if (webInstanceId > 0L) webInstanceId else cur.webInstanceId,
            loadAttempt = maxOf(cur.loadAttempt, loadAttempt),
            renderToken = if (attemptReset) renderToken else maxOf(cur.renderToken, renderToken),
            destinationDecoded = nextDecoded,
            destinationDrawn = nextDrawn,
            destinationFailed = nextFailed,
            nativeDestination = nativeDestination,
            decodedAtMs = if (tokenReset) (if (nextDecoded) timestampMs else null) else (cur.decodedAtMs ?: if (nextDecoded) timestampMs else null),
            drawnAtMs = if (tokenReset) (if (nextDrawn) timestampMs else null) else (cur.drawnAtMs ?: if (nextDrawn) timestampMs else null),
        )
    }

    fun updateVisualProgress(generation: Long, progress: Float, visualRect: Rect? = null) {
        val cur = activeMorph ?: return
        if (cur.generation == generation) {
            activeMorph = cur.copy(
                lastVisualProgress = progress.coerceIn(0f, 1f),
                lastVisualRect = visualRect ?: cur.lastVisualRect,
            )
        }
    }

    fun markAnimFinished(generation: Long) {
        val cur = activeMorph ?: return
        if (cur.generation != generation) return
        activeMorph = cur.copy(animFinished = true, lastVisualProgress = 1f)
        if (cur.mode == Mode.RETURNING || cur.mode == Mode.RETURNING_CANCELING) {
            clearMorphIfGeneration(generation)
        }
    }

    fun markDestinationWaitTimedOut(generation: Long, timestampMs: Long = System.currentTimeMillis()) {
        val cur = activeMorph ?: return
        if (cur.generation != generation || cur.mode != Mode.ENTERING) return
        if (cur.toRect == null) {
            markCoverRecovery(cur.targetNavEntryId)
            activeMorph = cur.copy(destinationWaitTimedOut = true, timedOut = true)
            clearMorphIfGeneration(generation, timestampMs)
            return
        }
        if (cur.destinationDrawn || cur.destinationFailed || cur.revealDispatched) return
        markCoverRecovery(cur.targetNavEntryId)
        activeMorph = cur.copy(
            destinationWaitTimedOut = true,
            degradedFallback = true,
            timedOut = true,
            degradeRequestedAtMs = cur.degradeRequestedAtMs ?: timestampMs,
        )
    }

    fun markRevealDispatched(generation: Long, settledRect: Rect?) {
        val cur = activeMorph ?: return
        if (cur.generation != generation || cur.mode != Mode.ENTERING || cur.revealDispatched) return
        if (!cur.animFinished || settledRect == null || settledRect != cur.toRect) return
        if (!cur.destinationDrawn && !cur.destinationFailed && !cur.degradedFallback) return
        // Native destinations and the overlay share the same drawing pass. Let the
        // destination paint only when this overlay stops, including the sleeve shadow.
        if (cur.nativeDestination) clearMorphIfGeneration(generation)
        else activeMorph = cur.copy(revealDispatched = true)
    }

    fun markRevealConfirmed(generation: Long, timestampMs: Long = System.currentTimeMillis()) {
        val cur = activeMorph ?: return
        if (cur.generation == generation && cur.revealDispatched && !cur.revealConfirmed) {
            activeMorph = cur.copy(revealConfirmed = true, overlayClearedAtMs = timestampMs)
        }
    }

    fun markRevealConfirmTimedOut(generation: Long, timestampMs: Long = System.currentTimeMillis()) {
        val cur = activeMorph ?: return
        if (cur.generation != generation || cur.mode != Mode.ENTERING) return
        if (!cur.revealDispatched || cur.revealConfirmed) return
        markCoverRecovery(cur.targetNavEntryId)
        activeMorph = cur.copy(revealConfirmTimedOut = true, timedOut = true)
        clearMorphIfGeneration(generation, timestampMs)
    }

    fun markTimedOut(generation: Long) {
        markDestinationWaitTimedOut(generation)
    }

    fun beginReturnSession(
        key: String,
        route: String,
        identity: String,
        physicalMotion: Boolean,
        sourceNavEntryId: String? = null,
        targetNavEntryId: String? = null,
        interactive: Boolean = false,
        orientation: Int = currentOrientation,
    ): Long? {
        if (!physicalMotion) {
            cancelAll()
            return null
        }
        val destCell = sources[key] ?: run {
            cancelAll()
            return null
        }
        val matchesTarget = targetNavEntryId == null || destCell.ownerEntryId == null || destCell.ownerEntryId == targetNavEntryId
        val matchesPage = destCell.page == activeShelfPage
        val matchesLayout = destCell.layoutVersion == activeShelfLayoutVersion
        val matchesOrientation = orientation == Configuration.ORIENTATION_UNDEFINED ||
            destCell.orientation == Configuration.ORIENTATION_UNDEFINED ||
            destCell.orientation == orientation
        val vp = viewportBoundsInRoot
        val cellVisibleInViewport = vp.width <= 1f || (
            destCell.boundsInRoot.right > vp.left + 8f &&
                destCell.boundsInRoot.left < vp.right - 8f &&
                destCell.boundsInRoot.bottom > vp.top + 8f &&
                destCell.boundsInRoot.top < vp.bottom - 8f
            )
        if (!destCell.visible || !destCell.imageLoaded || !matchesTarget || !matchesPage ||
            !matchesLayout || !matchesOrientation || !cellVisibleInViewport ||
            destCell.boundsInRoot.width <= 1f
        ) {
            cancelAll()
            return null
        }

        // Seamless mid-enter interruption: pick up from current visual overlay rect only while
        // actively moving in on the bound destination; early back before start or while waiting
        // without a drawn hero falls through to cancelAll() and standard route exit.
        val existing = activeMorph
        val canInterruptMidEnter = existing != null &&
            existing.key == key &&
            existing.route == route &&
            existing.mode == Mode.ENTERING &&
            !existing.animFinished &&
            existing.lastVisualProgress > 0.02f &&
            !existing.degradedFallback &&
            !existing.destinationFailed &&
            existing.targetNavEntryId != null &&
            (sourceNavEntryId == null || existing.targetNavEntryId == sourceNavEntryId)
        val currentVisualRect: Rect? = when {
            canInterruptMidEnter -> {
                existing!!.lastVisualRect ?: run {
                    val target = existing.toRect ?: destinationHeroes[identity]?.rectInRoot
                    target?.let { lerpRect(existing.fromRect, it, existing.lastVisualProgress.coerceIn(0f, 1f)) }
                }
            }
            existing != null && existing.key == key && existing.route == route && existing.mode == Mode.RETURNING_CANCELING &&
                (sourceNavEntryId == null || existing.sourceNavEntryId == null || existing.sourceNavEntryId == sourceNavEntryId) -> {
                existing.lastVisualRect ?: existing.fromRect
            }
            else -> null
        }

        val heroRecord = destinationHeroes[identity]
        val startRect = currentVisualRect ?: run {
            if (heroRecord == null || !heroRecord.decoded || !heroRecord.drawn || heroRecord.failed) {
                cancelAll()
                return null
            }
            if (sourceNavEntryId != null && heroRecord.navEntryId != null && heroRecord.navEntryId != sourceNavEntryId) {
                cancelAll()
                return null
            }
            if (orientation != Configuration.ORIENTATION_UNDEFINED &&
                heroRecord.orientation != Configuration.ORIENTATION_UNDEFINED &&
                heroRecord.orientation != orientation
            ) {
                cancelAll()
                return null
            }
            val heroVisible = vp.width <= 1f || (
                heroRecord.rectInRoot.bottom > vp.top + 32f &&
                    heroRecord.rectInRoot.top < vp.bottom - 20f
                )
            if (!heroVisible || heroRecord.rectInRoot.width <= 1f) {
                cancelAll()
                return null
            }
            heroRecord.rectInRoot
        }

        val initialBackProgress = if (existing != null && existing.key == key && existing.route == route && existing.mode == Mode.RETURNING_CANCELING &&
            (sourceNavEntryId == null || existing.sourceNavEntryId == null || existing.sourceNavEntryId == sourceNavEntryId)
        ) {
            (existing.lastVisualProgress / 0.45f).coerceIn(0f, 1f)
        } else {
            0f
        }

        val gen = ++generationCounter
        val resolvedReleaseId = route.takeIf { it.startsWith("album/") }?.removePrefix("album/")
            ?: key.removePrefix("release:")
        activeMorph = ActiveMorph(
            key = key,
            route = route,
            releaseId = resolvedReleaseId,
            sourceNavEntryId = sourceNavEntryId,
            targetNavEntryId = targetNavEntryId ?: destCell.ownerEntryId,
            identity = identity,
            model = destCell.model,
            sourceMemoryCacheKey = destCell.memoryCacheKey,
            coverBitmap = destCell.coverBitmap?.get(),
            aspectRatio = destCell.aspectRatio,
            fromRect = startRect,
            toRect = destCell.boundsInRoot,
            sleeve = destCell.sleeve,
            mode = if (interactive) Mode.RETURNING_INTERACTIVE else Mode.RETURNING,
            generation = gen,
            orientation = orientation,
            webInstanceId = heroRecord?.webInstanceId ?: 0L,
            loadAttempt = heroRecord?.loadAttempt ?: 0,
            renderToken = heroRecord?.renderToken ?: 0L,
            destinationDecoded = true,
            destinationDrawn = true,
            destinationFailed = false,
            animFinished = false,
            revealDispatched = false,
            revealConfirmed = false,
            timedOut = false,
            destinationWaitTimedOut = false,
            degradedFallback = false,
            revealConfirmTimedOut = false,
            interactiveProgress = initialBackProgress,
            lastVisualProgress = if (interactive) initialBackProgress * 0.45f else 0f,
            lastVisualRect = startRect,
            decodedAtMs = heroRecord?.decodedAtMs,
            drawnAtMs = heroRecord?.drawnAtMs,
            degradeRequestedAtMs = null,
            overlayClearedAtMs = null,
        )
        return gen
    }

    fun startReturn(
        key: String,
        route: String,
        identity: String,
        physicalMotion: Boolean,
        sourceNavEntryId: String? = null,
        targetNavEntryId: String? = null,
    ): Boolean = beginReturnSession(
        key = key,
        route = route,
        identity = identity,
        physicalMotion = physicalMotion,
        sourceNavEntryId = sourceNavEntryId,
        targetNavEntryId = targetNavEntryId,
        interactive = false,
    ) != null

    fun updateInteractiveReturn(generation: Long, progress: Float) {
        val cur = activeMorph ?: return
        if (cur.generation == generation && cur.mode == Mode.RETURNING_INTERACTIVE) {
            val clamped = progress.coerceIn(0f, 1f)
            activeMorph = cur.copy(
                interactiveProgress = clamped,
                lastVisualProgress = clamped * 0.45f,
            )
        }
    }

    fun cancelInteractiveReturn(generation: Long) {
        val cur = activeMorph ?: return
        if (cur.generation == generation && cur.mode == Mode.RETURNING_INTERACTIVE) {
            if (cur.lastVisualProgress <= 0.01f) {
                clearMorphIfGeneration(generation)
            } else {
                activeMorph = cur.copy(mode = Mode.RETURNING_CANCELING)
            }
        }
    }

    fun commitInteractiveReturn(generation: Long): Boolean {
        val cur = activeMorph ?: return false
        if (cur.generation != generation) return false
        if (cur.mode != Mode.RETURNING_INTERACTIVE && cur.mode != Mode.RETURNING_CANCELING) return false
        activeMorph = cur.copy(mode = Mode.RETURNING)
        return true
    }

    fun releaseWebInstance(
        webInstanceId: Long,
        navEntryId: String? = null,
        generation: Long? = null,
    ) {
        if (webInstanceId > 0L) {
            destroyedWebInstanceIds.add(webInstanceId)
            if (destroyedWebInstanceIds.size > 256) {
                destroyedWebInstanceIds.clear()
                destroyedWebInstanceIds.add(webInstanceId)
            }
            if (navEntryId != null && activeWebByEntry[navEntryId] == webInstanceId) {
                activeWebByEntry.remove(navEntryId)
            }
        }
        val toRemove = destinationHeroes.entries
            .filter { (_, rec) ->
                (webInstanceId > 0L && rec.webInstanceId == webInstanceId) &&
                    (navEntryId == null || rec.navEntryId == null || rec.navEntryId == navEntryId)
            }
            .map { it.key }
        toRemove.forEach { destinationHeroes.remove(it) }

        val cur = activeMorph ?: return
        val genOk = generation == null || cur.generation == generation
        if (cur.mode == Mode.ENTERING && genOk &&
            webInstanceId > 0L && cur.webInstanceId == webInstanceId &&
            (navEntryId == null || cur.targetNavEntryId == navEntryId)
        ) {
            cancelAll()
        }
    }

    fun releaseEntrySession(
        navEntryId: String?,
        generation: Long? = null,
        webInstanceId: Long? = null,
    ) {
        if (navEntryId == null && (webInstanceId == null || webInstanceId <= 0L)) return
        if (navEntryId != null && webInstanceId != null && webInstanceId > 0L) {
            val activeWeb = activeWebByEntry[navEntryId]
            if (activeWeb != null && activeWeb != webInstanceId) return
        }
        if (navEntryId != null) {
            releasedEntryIds.add(navEntryId)
            if (releasedEntryIds.size > 256) {
                releasedEntryIds.clear()
                releasedEntryIds.add(navEntryId)
            }
            activeSettledEntryByIdentity.entries.removeAll { it.value == navEntryId }
            activeWebByEntry.remove(navEntryId)
            entryGeneration.remove(navEntryId)
            clearCoverRecovery(navEntryId)
        }
        if (navEntryId == null && webInstanceId != null && webInstanceId > 0L) {
            destroyedWebInstanceIds.add(webInstanceId)
        }
        val heroKeysToRemove = destinationHeroes.entries
            .filter { (_, rec) ->
                (navEntryId != null && rec.navEntryId == navEntryId) &&
                    (webInstanceId == null || webInstanceId <= 0L || rec.webInstanceId == 0L || rec.webInstanceId == webInstanceId)
            }
            .map { it.key }
        heroKeysToRemove.forEach { destinationHeroes.remove(it) }

        val cur = activeMorph ?: return
        val genMatches = generation == null || cur.generation == generation
        if (!genMatches) return
        val webMatches = webInstanceId == null || webInstanceId <= 0L || cur.webInstanceId == 0L || cur.webInstanceId == webInstanceId
        val matchesEnter = cur.mode == Mode.ENTERING &&
            navEntryId != null && cur.targetNavEntryId == navEntryId && webMatches
        val matchesInteractiveReturn = (cur.mode == Mode.RETURNING_INTERACTIVE || cur.mode == Mode.RETURNING_CANCELING) &&
            navEntryId != null && cur.sourceNavEntryId == navEntryId && webMatches
        if (matchesEnter || matchesInteractiveReturn) {
            cancelAll()
        }
    }

    fun clearMorphIfGeneration(generation: Long, clearedAtMs: Long = System.currentTimeMillis()) {
        val cur = activeMorph ?: return
        if (cur.generation == generation) {
            if (cur.mode == Mode.ENTERING) {
                lastOverlayClearEvent = "route=${cur.route} gen=${cur.generation} decodedAt=${cur.decodedAtMs} drawnAt=${cur.drawnAtMs} waitTimeout=${cur.destinationWaitTimedOut} confirmTimeout=${cur.revealConfirmTimedOut} clearedAt=$clearedAtMs"
            }
            activeMorph = null
        }
    }

    fun cancelAll() {
        generationCounter++
        activeMorph = null
    }

    fun isSourceHidden(key: String, ownerEntryId: String? = null, page: Int? = null): Boolean {
        val cur = activeMorph ?: return false
        if (cur.key != key || cur.toRect == null) return false
        val ownerBound = when {
            ownerEntryId == null -> true
            cur.mode == Mode.ENTERING -> cur.sourceNavEntryId == null || cur.sourceNavEntryId == ownerEntryId
            else -> cur.targetNavEntryId == null || cur.targetNavEntryId == ownerEntryId
        }
        val pageBound = page == null || page == activeShelfPage
        return ownerBound && pageBound
    }

    fun shouldHoldHeroHidden(key: String?, route: String?, navEntryId: String? = null): Boolean {
        val cur = activeMorph ?: return false
        val match = (key != null && cur.key == key) || (route != null && cur.route == route)
        if (!match) return false
        val entryMatch = when {
            navEntryId == null -> true
            cur.mode == Mode.ENTERING -> cur.targetNavEntryId == null || cur.targetNavEntryId == navEntryId
            else -> cur.sourceNavEntryId == null || cur.sourceNavEntryId == navEntryId
        }
        if (!entryMatch) return false
        return (cur.mode == Mode.ENTERING && !cur.revealDispatched) || cur.isReturnSession
    }

    fun isMorphingEnter(key: String): Boolean {
        val cur = activeMorph ?: return false
        return cur.mode == Mode.ENTERING && cur.key == key
    }

    fun isReturning(route: String?): Boolean {
        if (route == null) return false
        val cur = activeMorph ?: return false
        return cur.isReturnSession && cur.route == route
    }

    fun isEntering(route: String?): Boolean {
        if (route == null) return false
        val cur = activeMorph ?: return false
        return cur.mode == Mode.ENTERING && cur.route == route
    }
}

@Composable
internal fun CoverTransitionOverlay(
    coordinator: CoverTransitionCoordinator,
) {
    val morph = coordinator.activeMorph ?: return
    LaunchedEffect(morph.generation, morph.mode) {
        if (morph.mode != CoverTransitionCoordinator.Mode.ENTERING) return@LaunchedEffect
        val elapsed = morph.startedAt.elapsedNow().inWholeMilliseconds
        delay((coordinator.waitTimeoutMs - elapsed).coerceAtLeast(0))
        coordinator.markDestinationWaitTimedOut(morph.generation)
    }
    val target = morph.toRect ?: return
    val context = LocalContext.current

    val startVisualFraction = remember(morph.generation, morph.mode) {
        when (morph.mode) {
            CoverTransitionCoordinator.Mode.ENTERING -> 0f
            CoverTransitionCoordinator.Mode.RETURNING -> morph.lastVisualProgress.coerceIn(0f, 1f)
            CoverTransitionCoordinator.Mode.RETURNING_INTERACTIVE -> morph.lastVisualProgress.coerceIn(0f, 1f)
            CoverTransitionCoordinator.Mode.RETURNING_CANCELING -> morph.lastVisualProgress.coerceIn(0f, 1f)
        }
    }
    val progress = remember(morph.generation, morph.mode) { Animatable(startVisualFraction) }
    val smoothedToRect = remember(morph.generation) { Animatable(target, RectVectorConverter) }

    LaunchedEffect(morph.generation, target) {
        if (smoothedToRect.targetValue != target) {
            smoothedToRect.animateTo(
                targetValue = target,
                animationSpec = tween(durationMillis = 120, easing = AppNavEasing),
            )
        }
    }

    LaunchedEffect(morph.generation, morph.mode) {
        when (morph.mode) {
            CoverTransitionCoordinator.Mode.ENTERING -> {
                progress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = CoverMorphMillis, easing = AppNavEasing),
                ) {
                    val liveRect = lerpRect(morph.fromRect, smoothedToRect.value, value)
                    coordinator.updateVisualProgress(morph.generation, value, liveRect)
                }
                coordinator.markAnimFinished(morph.generation)
            }
            CoverTransitionCoordinator.Mode.RETURNING -> {
                val remainingMs = ((1f - startVisualFraction) * CoverMorphMillis).roundToInt().coerceIn(110, CoverMorphMillis)
                progress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = remainingMs, easing = AppNavEasing),
                ) {
                    val liveRect = lerpRect(morph.fromRect, smoothedToRect.value, value)
                    coordinator.updateVisualProgress(morph.generation, value, liveRect)
                }
                coordinator.markAnimFinished(morph.generation)
            }
            CoverTransitionCoordinator.Mode.RETURNING_CANCELING -> {
                val remainingMs = ((startVisualFraction / 0.45f).coerceIn(0.2f, 1f) * CoverMorphCancelMillis).roundToInt()
                progress.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = remainingMs, easing = AppNavEasing),
                ) {
                    val liveRect = lerpRect(morph.fromRect, smoothedToRect.value, value)
                    coordinator.updateVisualProgress(morph.generation, value, liveRect)
                }
                coordinator.markAnimFinished(morph.generation)
            }
            CoverTransitionCoordinator.Mode.RETURNING_INTERACTIVE -> {
                progress.snapTo((morph.interactiveProgress * 0.45f).coerceIn(0f, 0.45f))
            }
        }
    }

    LaunchedEffect(morph.generation, morph.mode, morph.interactiveProgress) {
        if (morph.mode == CoverTransitionCoordinator.Mode.RETURNING_INTERACTIVE) {
            val next = (morph.interactiveProgress * 0.45f).coerceIn(0f, 0.45f)
            progress.snapTo(next)
            coordinator.updateVisualProgress(
                morph.generation,
                next,
                lerpRect(morph.fromRect, smoothedToRect.value, next),
            )
        }
    }

    // Keep both the progress and the retargeting animation on screen until the cover lands.
    val settledRect = smoothedToRect.value.takeIf { it == target && !smoothedToRect.isRunning }
    LaunchedEffect(
        morph.generation,
        morph.animFinished,
        settledRect,
        morph.destinationDrawn,
        morph.destinationFailed,
        morph.degradedFallback,
    ) {
        coordinator.markRevealDispatched(morph.generation, settledRect)
    }

    // Stage 3 -> Stage 4: Remove overlay immediately once WebView confirms reveal frame; enforce bounded
    // revealConfirmTimeoutMs window ONLY if VisualStateCallback is lost (zero fixed post-reveal sleep).
    LaunchedEffect(morph.generation, morph.revealDispatched, morph.revealConfirmed) {
        if (morph.mode == CoverTransitionCoordinator.Mode.ENTERING && morph.revealDispatched) {
            if (morph.revealConfirmed) {
                withFrameNanos { }
                coordinator.clearMorphIfGeneration(morph.generation)
            } else {
                delay(coordinator.revealConfirmTimeoutMs)
                coordinator.markRevealConfirmTimedOut(morph.generation)
            }
        }
    }

    val t = when (morph.mode) {
        CoverTransitionCoordinator.Mode.RETURNING_INTERACTIVE -> (morph.interactiveProgress * 0.45f).coerceIn(0f, 0.45f)
        else -> progress.value.coerceIn(0f, 1f)
    }
    val activeRect = lerpRect(morph.fromRect, smoothedToRect.value, t)
    SideEffect {
        coordinator.updateVisualProgress(morph.generation, t, activeRect)
    }
    val left = activeRect.left
    val top = activeRect.top
    val width = activeRect.width.coerceAtLeast(1f)
    val height = activeRect.height.coerceAtLeast(1f)
    val density = LocalDensity.current
    val widthDp = with(density) { width.toDp() }
    val heightDp = with(density) { height.toDp() }

    val imageRequest = remember(morph.model, morph.sourceMemoryCacheKey, context) {
        ImageRequest.Builder(context)
            .data(morph.model)
            .apply {
                morph.sourceMemoryCacheKey?.let { placeholderMemoryCacheKey(it) }
            }
            .crossfade(false)
            .build()
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .drawWithContent { if (coordinator.activeMorph?.generation == morph.generation) drawContent() }
                .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                .size(widthDp, heightDp)
                .then(if (morph.sleeve) Modifier.sleeveShadow().coverSleeve() else Modifier)
                .background(Panel),
        ) {
            val bitmap=morph.coverBitmap
            if(bitmap!=null) Image(bitmap,null,Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
            else AsyncImage(
                model = imageRequest,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}
