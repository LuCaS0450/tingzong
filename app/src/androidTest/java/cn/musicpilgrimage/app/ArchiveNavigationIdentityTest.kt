package cn.musicpilgrimage.app

import android.net.Uri
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.createGraph
import androidx.test.platform.app.InstrumentationRegistry
import cn.musicpilgrimage.app.ui.appDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveNavigationIdentityTest {
    @Test
    fun parameterizedRoutesOnlyMatchEntriesWithTheSameArguments() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val firstId = Uri.encode("地点/一 #1")
        val secondId = Uri.encode("地点/二 ?2")
        val routes = mapOf(
            "album/{id}" to listOf("album/$firstId", "album/$secondId"),
            "release-search/{id}" to listOf("release-search/$firstId", "release-search/$secondId"),
            "music-favorites/{kind}" to listOf("music-favorites/releases", "music-favorites/songs"),
            "song/{id}" to listOf("song/$firstId", "song/$secondId"),
            "perform/{song}" to listOf("perform/$firstId", "perform/$secondId"),
            "archive/{kind}" to listOf("archive/places", "archive/footprints"),
            "place/{id}" to listOf("place/$firstId", "place/$secondId"),
            "trip/{id}" to listOf("trip/$firstId", "trip/$secondId"),
            "mine/{kind}" to listOf("mine/visit", "mine/message"),
            "profile/{id}" to listOf("profile/$firstId", "profile/$secondId"),
            "form/{kind}/{target}" to listOf("form/visit/$firstId", "form/visit/$secondId", "form/message/$firstId"),
            "camera/{scene}" to listOf("camera/$firstId", "camera/$secondId"),
        )
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync {
            result = runCatching {
                val nav = NavHostController(instrumentation.targetContext)
                nav.navigatorProvider.addNavigator(ComposeNavigator())
                nav.graph = nav.createGraph(startDestination = "home") {
                    appDestination("home") {}
                    appDestination("intermediate") {}
                    routes.keys.forEach { appDestination(it) {} }
                }
                for ((pattern, paths) in routes) {
                    val (original, other) = paths
                    nav.navigate(original)
                    val originalEntry = nav.getBackStackEntry(original)
                    nav.navigate("intermediate")
                    for (unvisited in paths.drop(1)) {
                        assertFalse("$pattern matched a different record", nav.popBackStack(unvisited, inclusive = false))
                        assertEquals("intermediate", nav.currentDestination?.route)
                    }
                    assertTrue(nav.popBackStack())
                    nav.navigate(other)
                    assertNotEquals(originalEntry.id, nav.getBackStackEntry(other).id)
                    assertTrue(nav.popBackStack(original, inclusive = false))
                    assertSame(originalEntry, nav.currentBackStackEntry)
                    assertTrue(nav.popBackStack())
                }
            }
        }
        requireNotNull(result).getOrThrow()
    }
}
