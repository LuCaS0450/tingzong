package cn.musicpilgrimage.app.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.staticCompositionLocalOf

internal val appMotionEasing = CubicBezierEasing(.22f, .61f, .36f, 1f)
internal const val ContentFadeMillis = 130
internal const val WallTabFadeMillis = 110
internal const val LiveFadeMillis = 100
internal val LocalRouteActive = staticCompositionLocalOf { true }
internal val LocalRouteIsCurrent = staticCompositionLocalOf<() -> Boolean> { { true } }

internal fun wallSection(route: String?) = when (route) {
    "room", "catalogue" -> 0
    "explore" -> 1
    "performance" -> 2
    "me" -> 3
    else -> null
}

internal enum class AppRouteMotion(private val duration: Int) {
    Tab(WallTabFadeMillis), Detail(ContentFadeMillis), Live(LiveFadeMillis);

    fun enter(initialAlpha: Float = 0f): EnterTransition =
        fadeIn(tween(duration, easing = appMotionEasing), initialAlpha)
}

internal fun routeMotion(from: String?, to: String?): AppRouteMotion {
    return when {
        wallSection(from) != null && wallSection(to) != null -> AppRouteMotion.Tab
        from.isLiveRoute() || to.isLiveRoute() -> AppRouteMotion.Live
        else -> AppRouteMotion.Detail
    }
}

private fun String?.isLiveRoute() = this == "perform/{song}" || this == "camera/{scene}"
