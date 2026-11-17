package cn.musicpilgrimage.app.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.staticCompositionLocalOf

internal val AppNavEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
internal val appMotionEasing = CubicBezierEasing(.22f, .61f, .36f, 1f)
internal const val ContentFadeMillis = 130
internal const val WallTabFadeMillis = 110
internal const val LiveFadeMillis = 100
internal const val SubpageTransitionMillis = 270
internal const val CoverMorphMillis = 270
internal const val CoverMorphCancelMillis = 175
internal const val LocalOpenCloseMillis = 190
internal const val LiveOpenCloseMillis = 190

internal val LocalRouteActive = staticCompositionLocalOf { true }
internal val LocalRouteIsCurrent = staticCompositionLocalOf<() -> Boolean> { { true } }
internal val LocalRouteEntryId = staticCompositionLocalOf<String?> { null }
internal val LocalCoverCoordinator = staticCompositionLocalOf<CoverTransitionCoordinator?> { null }
internal val LocalAppHaptics = staticCompositionLocalOf<AppHaptics?> { null }

internal fun wallSection(route: String?) = when (route) {
    "room", "catalogue" -> 0
    "explore" -> 1
    "performance" -> 2
    "me" -> 3
    else -> null
}

internal enum class AppRouteMotion(private val duration: Int) {
    Tab(WallTabFadeMillis),
    Detail(SubpageTransitionMillis),
    Live(LiveOpenCloseMillis);

    fun enter(initialAlpha: Float = 0f): EnterTransition = when (this) {
        Tab -> fadeIn(tween(WallTabFadeMillis, easing = appMotionEasing), initialAlpha)
        Detail -> slideInHorizontally(tween(SubpageTransitionMillis, easing = AppNavEasing)) { it }
        Live -> fadeIn(tween(LiveOpenCloseMillis, easing = AppNavEasing), initialAlpha) +
            scaleIn(tween(LiveOpenCloseMillis, easing = AppNavEasing), initialScale = 0.96f)
    }

    fun exit(): ExitTransition = when (this) {
        Tab -> fadeOut(tween(WallTabFadeMillis, easing = appMotionEasing), targetAlpha = 0.99f)
        Detail -> slideOutHorizontally(tween(SubpageTransitionMillis, easing = AppNavEasing)) { -(it * 0.08f).toInt() }
        Live -> fadeOut(tween(LiveOpenCloseMillis, easing = AppNavEasing))
    }

    fun popEnter(): EnterTransition = when (this) {
        Tab -> fadeIn(tween(WallTabFadeMillis, easing = appMotionEasing), 0f)
        Detail -> slideInHorizontally(tween(SubpageTransitionMillis, easing = AppNavEasing)) { -(it * 0.08f).toInt() }
        Live -> fadeIn(tween(LiveOpenCloseMillis, easing = AppNavEasing), 0.75f)
    }

    fun popExit(): ExitTransition = when (this) {
        Tab -> fadeOut(tween(WallTabFadeMillis, easing = appMotionEasing))
        Detail -> slideOutHorizontally(tween(SubpageTransitionMillis, easing = AppNavEasing)) { it }
        Live -> fadeOut(tween(LiveOpenCloseMillis, easing = AppNavEasing)) +
            scaleOut(tween(LiveOpenCloseMillis, easing = AppNavEasing), targetScale = 0.96f)
    }

    fun morphEnter(): EnterTransition = fadeIn(tween(170, easing = AppNavEasing), initialAlpha = 0.15f)
    fun morphExitUnder(): ExitTransition = fadeOut(tween(CoverMorphMillis, easing = AppNavEasing), targetAlpha = 0.99f)
    fun morphPopEnterUnder(): EnterTransition = fadeIn(tween(150, easing = AppNavEasing), initialAlpha = 0.85f)
    fun morphPopExit(): ExitTransition = fadeOut(tween(160, easing = AppNavEasing))
}

internal fun routeMotion(from: String?, to: String?): AppRouteMotion {
    return when {
        wallSection(from) != null && wallSection(to) != null -> AppRouteMotion.Tab
        from.isLiveRoute() || to.isLiveRoute() -> AppRouteMotion.Live
        else -> AppRouteMotion.Detail
    }
}

private fun String?.isLiveRoute() = this == "perform/{song}" || this == "camera/{scene}"
