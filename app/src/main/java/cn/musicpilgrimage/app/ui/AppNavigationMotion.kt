package cn.musicpilgrimage.app.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween

internal val appMotionEasing = CubicBezierEasing(.22f, .61f, .36f, 1f)
internal const val WallTabFadeMillis = 160

internal fun wallSection(route: String?) = when (route) {
    "room", "catalogue" -> 0
    "explore" -> 1
    "performance" -> 2
    "me" -> 3
    else -> null
}

internal enum class AppRouteMotion(private val duration: Int) {
    Tab(WallTabFadeMillis), Search(155), Live(110), Detail(230), Form(240);

    fun enter(): EnterTransition {
        val fade = fadeIn(tween(duration, easing = appMotionEasing))
        return when (this) {
            Detail -> fade + slideInHorizontally(tween(duration, easing = appMotionEasing)) { it / 12 }
            Form -> fade + slideInVertically(tween(duration, easing = appMotionEasing)) { it / 9 }
            else -> fade
        }
    }

    fun exit(): ExitTransition {
        val fade = fadeOut(tween(duration, easing = appMotionEasing))
        return when (this) {
            Detail -> fade + slideOutHorizontally(tween(duration, easing = appMotionEasing)) { it / 12 }
            Form -> fade + slideOutVertically(tween(duration, easing = appMotionEasing)) { it / 9 }
            else -> fade
        }
    }
}

internal fun routeMotion(from: String?, to: String?, popping: Boolean): AppRouteMotion {
    val presented = if (popping) from else to
    return when {
        wallSection(from) != null && wallSection(to) != null -> AppRouteMotion.Tab
        from.isLiveRoute() || to.isLiveRoute() -> AppRouteMotion.Live
        presented in listOf("search", "release-search/{id}", "auth") -> AppRouteMotion.Search
        presented == "form/{kind}/{target}" || presented == "edit-profile" -> AppRouteMotion.Form
        else -> AppRouteMotion.Detail
    }
}

private fun String?.isLiveRoute() = this == "perform/{song}" || this == "camera/{scene}"
