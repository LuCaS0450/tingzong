package cn.musicpilgrimage.app

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.BackEventCompat
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import cn.musicpilgrimage.app.data.Catalog
import cn.musicpilgrimage.app.data.Place
import cn.musicpilgrimage.app.data.Scene
import cn.musicpilgrimage.app.ui.LocalPhysicalMotion
import cn.musicpilgrimage.app.ui.PilgrimageTheme
import cn.musicpilgrimage.app.ui.room.RoomBrowseState
import cn.musicpilgrimage.app.ui.room.SwissWorldWall
import coil.imageLoader
import kotlinx.coroutines.CompletableDeferred
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * End-to-end PilgrimageApp instrumentation verification (via guest-enabled MusicSpacePreviewActivity):
 * Native release-cover handoff, predictive/system back return to Shelf, and World summary motion.
 */
@RunWith(AndroidJUnit4::class)
class PrototypeMainNavigationUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<MusicSpacePreviewActivity>()

    private fun findWebByUrl(view: View, urlSubstring: String): WebView? {
        if (view is WebView && view.url?.contains(urlSubstring) == true) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findWebByUrl(view.getChildAt(i), urlSubstring)?.let { return it }
            }
        }
        return null
    }

    private fun evalWebByUrl(urlSubstring: String, script: String): String {
        val latch = CountDownLatch(1)
        var out = ""
        compose.runOnUiThread {
            val web = findWebByUrl(compose.activity.window.decorView, urlSubstring)
            if (web == null) {
                latch.countDown()
            } else {
                web.evaluateJavascript(script) { value ->
                    out = value ?: ""
                    latch.countDown()
                }
            }
        }
        check(latch.await(5, TimeUnit.SECONDS)) { "Timed out evaluating script on $urlSubstring" }
        return out
    }

    @Test
    fun realMainActivity_sharedCoverHandoffAndBackReturn() {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("three-walls-tabs", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        // Ensure Music/Shelf tab ("wall-tab-0") is selected.
        if (compose.onAllNodesWithTag("shelf-pager", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("wall-tab-0", useUnmergedTree = true).performClick()
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("shelf-pager", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }

        val displayWidth = compose.activity.resources.displayMetrics.widthPixels.toFloat()
        val releaseMatcher = SemanticsMatcher("visible-release-cover-card") { node ->
            val tag = node.config.getOrElse(SemanticsProperties.TestTag) { "" }
            tag.startsWith("room-release-") &&
                node.boundsInRoot.width > 12f &&
                node.boundsInRoot.center.x in 1f..(displayWidth - 1f)
        }
        var targetTag: String? = null
        compose.waitUntil(8_000) {
            val visibleNodes = compose.onAllNodes(releaseMatcher, useUnmergedTree = true).fetchSemanticsNodes()
            targetTag = visibleNodes.firstOrNull()?.config?.getOrElse(SemanticsProperties.TestTag) { "" }
                ?.takeIf { it.isNotEmpty() }
            targetTag != null
        }

        compose.onNodeWithTag(requireNotNull(targetTag), useUnmergedTree = true).performClick()
        compose.onNodeWithTag("native-release-archive").assertIsDisplayed()
        compose.onNodeWithTag("release-archive-header").assertIsDisplayed()
        val title=compose.onNodeWithTag("release-archive-title")
        title.assertIsDisplayed()
        val first=title.fetchSemanticsNode().boundsInRoot
        compose.waitUntil(8_000) {
            compose.onAllNodesWithTag("release-archive-cover", useUnmergedTree = true)
                .fetchSemanticsNodes().any { isEnabled().matches(it) }
        }
        assertEquals(first,title.fetchSemanticsNode().boundsInRoot)
        assertTrue(compose.runOnUiThread {
            findWebByUrl(compose.activity.window.decorView,"swiss-details") == null
        })
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        File(instrumentation.targetContext.getExternalFilesDir(null),"native-release-archive.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)
        }

        // System back returns via shared cover/route transition and restores Shelf state cleanly.
        Espresso.pressBack()
        compose.waitUntil(8_000) {
            compose.onAllNodesWithTag("shelf-pager", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }

        // Quick tab cross-fade switch ("我的" -> "音乐") keeps shelf intact without losing navigation state.
        compose.onNodeWithTag("wall-tab-3", useUnmergedTree = true).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("shelf-pager", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("wall-tab-0", useUnmergedTree = true).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("shelf-pager", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @SuppressLint("VisibleForTests")
    @Test
    fun realMainActivity_predictiveCancel_uiBack_immediateTabSwitch_andReentry() {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("three-walls-tabs", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithTag("shelf-pager", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("wall-tab-0", useUnmergedTree = true).performClick()
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("shelf-pager", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }

        val displayWidth = compose.activity.resources.displayMetrics.widthPixels.toFloat()
        val releaseMatcher = SemanticsMatcher("visible-release-cover-card-p2") { node ->
            val tag = node.config.getOrElse(SemanticsProperties.TestTag) { "" }
            tag.startsWith("room-release-") &&
                node.boundsInRoot.width > 12f &&
                node.boundsInRoot.center.x in 1f..(displayWidth - 1f)
        }
        var targetTag: String? = null
        compose.waitUntil(8_000) {
            val visibleNodes = compose.onAllNodes(releaseMatcher, useUnmergedTree = true).fetchSemanticsNodes()
            targetTag = visibleNodes.firstOrNull()?.config?.getOrElse(SemanticsProperties.TestTag) { "" }
                ?.takeIf { it.isNotEmpty() }
            targetTag != null
        }

        // Open A -> wait for rendered archive page -> cancel predictive back midway -> stays in A cleanly.
        compose.onNodeWithTag(requireNotNull(targetTag), useUnmergedTree = true).performClick()
        compose.onNodeWithTag("native-release-archive").assertIsDisplayed()

        compose.runOnUiThread {
            val dispatcher = compose.activity.onBackPressedDispatcher
            dispatcher.dispatchOnBackStarted(BackEventCompat(16f, 400f, 0.12f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(180f, 400f, 0.48f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackCancelled()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("native-release-archive").assertIsDisplayed()

        compose.onNodeWithTag("room-back").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("wall-tab-3", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("wall-tab-3", useUnmergedTree = true).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("shelf-pager", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }

        // Switch immediately back to Shelf and rapidly re-enter same release A.
        compose.onNodeWithTag("wall-tab-0", useUnmergedTree = true).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(requireNotNull(targetTag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(requireNotNull(targetTag), useUnmergedTree = true).performClick()
        compose.onNodeWithTag("native-release-archive").assertIsDisplayed()
        Espresso.pressBack()
        compose.waitUntil(8_000) {
            compose.onAllNodesWithTag("shelf-pager", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openWorld() {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("wall-tab-1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("wall-tab-1").performClick()
        compose.onNodeWithTag("world-place-list").assertIsDisplayed()
    }

    private fun chooseWorldPlace(query: String, placeId: String? = null) {
        compose.onNodeWithTag("world-place-list").performClick()
        val search = compose.onNodeWithTag("world-place-query")
        search.performTextInput(query)
        val result = SemanticsMatcher("world place option") { node ->
            node.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith("world-place-option-")
        }
        compose.waitUntil(6_000) { compose.onAllNodes(result, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        if (placeId == null) compose.onAllNodes(result, useUnmergedTree = true).onFirst().performClick()
        else compose.onNodeWithTag("world-place-option-$placeId").performClick()
        compose.onNodeWithTag("world-place-summary").assertIsDisplayed()
    }

    @Test fun worldAndPlaceArchivesShowCanonicalLocationAndReviewedFilmPreview() {
        openWorld()
        chooseWorldPlace("大屿山")
        compose.onNodeWithText("中国香港 · 大屿山").assertIsDisplayed()
        compose.onNodeWithTag("world-summary-detail").performClick()
        compose.onNodeWithTag("native-place-archive").assertIsDisplayed()
        compose.onNodeWithText("中国香港 · 大屿山").assertIsDisplayed()
        Espresso.pressBack()
        compose.waitUntil(5_000) { compose.onNodeWithTag("world-place-list").isDisplayed() }

        chooseWorldPlace("Stay Alive")
        val mediaLabel = "电影片段公开预览图"
        compose.onNodeWithTag("world-summary-media-credit").assertTextContains(mediaLabel, substring = true)
        compose.onNodeWithTag("world-summary-detail").performClick()
        compose.onNodeWithTag("native-place-archive").assertIsDisplayed()
        compose.onNodeWithText(mediaLabel).performScrollTo().assertIsDisplayed()
        val image = hasContentDescription("《Stay Alive》$mediaLabel") and isEnabled()
        compose.waitUntil(12_000) { compose.onAllNodes(image).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(image).performClick()
        compose.onNodeWithContentDescription("关闭大图").assertIsDisplayed()
        Espresso.pressBack()
        compose.onNodeWithText(mediaLabel).assertIsDisplayed()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        File(instrumentation.targetContext.getExternalFilesDir(null), "native-place-soundtrack.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("Stay Alive").performScrollTo().performClick()
        compose.onNodeWithTag("native-work-archive").assertIsDisplayed()
        compose.onNodeWithTag("work-archive-title").assertTextEquals("Stay Alive")
        File(instrumentation.targetContext.getExternalFilesDir(null), "native-work-archive.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        Espresso.pressBack()
        compose.onNodeWithText(mediaLabel).assertIsDisplayed()
    }

    @Test fun placeArchiveMvScreenshotsDecodeOriginalsFromBundleAndNetwork() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val cache = requireNotNull(context.imageLoader.memoryCache)
        val samples = listOf(
            Triple("Love 119", "fuchukeiba-seimon-mae-station", "file:///android_asset/"),
            Triple("说好不哭", "tower-view-street", "https://"),
        )
        openWorld()
        for ((work, placeId, sourcePrefix) in samples) {
            chooseWorldPlace(work, placeId)
            compose.onNodeWithTag("world-summary-detail").performClick()
            compose.onNodeWithTag("native-place-archive").assertIsDisplayed()
            val image = hasContentDescription("《$work》MV 截图", substring = false)
            compose.onNode(image).performScrollTo()
            compose.waitUntil(12_000) { compose.onAllNodes(image and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(image and isEnabled()).performClick()
            compose.onNodeWithContentDescription("关闭大图").assertIsDisplayed()
            compose.waitUntil(20_000) {
                cache.keys.any { key ->
                    key.key.startsWith("archive-original:$sourcePrefix") &&
                        cache[key]?.bitmap?.let { it.width == 1280 && it.height == 720 } == true
                }
            }
            if (work == "Love 119") {
                File(context.getExternalFilesDir(null), "place-archive-mv-original.png").outputStream().use {
                    instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            compose.onNodeWithContentDescription("关闭大图").performClick()
            compose.onNodeWithTag("room-community-photo-viewer").assertDoesNotExist()
            compose.onNode(image and isEnabled()).assertIsDisplayed()
            Espresso.pressBack()
            compose.waitUntil(5_000) { compose.onNodeWithTag("world-place-list").isDisplayed() }
        }
    }

    @Test fun widePhotoRotatesInPlaceAndReturnsToTheSameArchivePosition() {
        openWorld()
        chooseWorldPlace("Love 119", "fuchukeiba-seimon-mae-station")
        compose.onNodeWithTag("world-summary-detail").performClick()
        val thumb = hasContentDescription("《Love 119》MV 截图", substring = false)
        compose.onNode(thumb).performScrollTo()
        compose.waitUntil(12_000) { compose.onAllNodes(thumb and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        val startingActivity = compose.activity
        val startingBounds = compose.onNode(thumb and isEnabled()).fetchSemanticsNode().boundsInRoot
        assertEquals(Configuration.ORIENTATION_PORTRAIT, startingActivity.resources.configuration.orientation)
        compose.onNode(thumb and isEnabled()).performClick()
        val photo = compose.onNodeWithTag("photo-viewer-image")
        val portraitWidth = photo.fetchSemanticsNode().boundsInRoot.width
        val controls = compose.onNodeWithTag("photo-viewer-controls")
        assertFalse(photo.fetchSemanticsNode().boundsInRoot.overlaps(controls.fetchSemanticsNode().boundsInRoot))

        compose.onNodeWithContentDescription("横屏观看").performTouchInput { click() }
        compose.waitUntil(8_000) {
            startingActivity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        assertSame(startingActivity, compose.activity)
        compose.onNodeWithContentDescription("返回竖屏").assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭大图").assertIsDisplayed()
        compose.onNodeWithText("《Love 119》MV 截图").assertDoesNotExist()
        compose.onNodeWithText("SMTOWN").assertDoesNotExist()
        photo.assertContentDescriptionEquals("《Love 119》MV 截图")
        assertTrue(photo.fetchSemanticsNode().boundsInRoot.width > portraitWidth * 1.4f)
        assertFalse(photo.fetchSemanticsNode().boundsInRoot.overlaps(controls.fetchSemanticsNode().boundsInRoot))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        File(context.getExternalFilesDir(null), "place-archive-mv-landscape.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }

        compose.onNodeWithContentDescription("图片信息").performTouchInput { click() }
        compose.onNodeWithTag("photo-viewer-info").assertIsDisplayed()
        compose.onNodeWithText("《Love 119》MV 截图").assertIsDisplayed()
        compose.onNodeWithText("SMTOWN").assertIsDisplayed()
        compose.onNode(hasText("资料来源") and hasAnyAncestor(hasTestTag("photo-viewer-info"))).performClick()
        compose.onNodeWithTag("photo-viewer-info").assertDoesNotExist()
        compose.onNodeWithText("地点照片").assertIsDisplayed()
        Espresso.pressBack()
        photo.assertIsDisplayed()

        val viewer = compose.onNodeWithTag("room-community-photo-viewer")
        viewer.performTouchInput {
            pinch(center - Offset(70f, 0f), center - Offset(200f, 0f),
                center + Offset(70f, 0f), center + Offset(200f, 0f))
        }
        controls.assertDoesNotExist()
        viewer.performTouchInput { click() }
        compose.waitUntil(2_000) { compose.onAllNodesWithTag("photo-viewer-controls").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("返回竖屏").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(2_700)
        controls.assertDoesNotExist()
        viewer.performTouchInput { click() }
        compose.waitUntil(2_000) { compose.onAllNodesWithTag("photo-viewer-controls").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("返回竖屏").performTouchInput { click() }
        compose.waitUntil(8_000) {
            startingActivity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        }
        photo.assertIsDisplayed()
        compose.onNodeWithContentDescription("横屏观看").performTouchInput { click() }
        compose.waitUntil(8_000) {
            startingActivity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        Espresso.pressBack()
        compose.waitUntil(8_000) {
            startingActivity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT &&
                compose.onAllNodesWithTag("room-community-photo-viewer").fetchSemanticsNodes().isEmpty()
        }
        assertEquals(startingBounds, compose.onNode(thumb and isEnabled()).fetchSemanticsNode().boundsInRoot)

        Espresso.pressBack()
        compose.waitUntil(5_000) { compose.onNodeWithTag("world-place-list").isDisplayed() }
        chooseWorldPlace("淵明", "taipei-ticc-reference")
        compose.onNodeWithTag("world-summary-detail").performClick()
        val secondThumb = hasContentDescription("《淵明》MV 截图", substring = false)
        compose.onNode(secondThumb).performScrollTo()
        compose.waitUntil(12_000) { compose.onAllNodes(secondThumb and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(secondThumb and isEnabled()).performClick()
        compose.onNodeWithContentDescription("横屏观看").performTouchInput { click() }
        compose.waitUntil(8_000) {
            startingActivity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        assertFalse(photo.fetchSemanticsNode().boundsInRoot.overlaps(controls.fetchSemanticsNode().boundsInRoot))
        compose.onNodeWithText("《淵明》MV 截图").assertDoesNotExist()
        File(context.getExternalFilesDir(null), "place-archive-mv-landscape-yuanming.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        viewer.performTouchInput { doubleClick() }
        controls.assertDoesNotExist()
        Espresso.pressBack()
        compose.waitUntil(8_000) {
            startingActivity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT &&
                compose.onAllNodesWithTag("room-community-photo-viewer").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test fun portraitPlaceArchivesKeepPhotosAndTitlesSeparateAndRemoveExplanatoryCopy() {
        openWorld()
        for ((query, id) in listOf("东京日暮里" to "nippori-tokyo-playlist-reference", "Tower View" to "tower-view-street", "Tokyo Flash" to "tokyo-city-lyrics-reference")) {
            chooseWorldPlace(query, id)
            compose.onNodeWithTag("world-summary-detail").performClick()
            compose.onNodeWithTag("native-place-archive").assertIsDisplayed()
            val photo = compose.onNodeWithTag("place-archive-photo")
            compose.waitUntil(12_000) { compose.onAllNodes(hasTestTag("place-archive-photo") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            val title = compose.onNodeWithTag("place-archive-title")
            assertTrue(photo.getUnclippedBoundsInRoot().bottom < title.getUnclippedBoundsInRoot().top)
            compose.onNodeWithText("区域级关联").assertDoesNotExist()
            compose.onNodeWithText("地点级关联").assertDoesNotExist()
            compose.onNodeWithText("城市级关联").assertDoesNotExist()
            compose.onNodeWithText("实景 ·", substring = true).assertDoesNotExist()
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            File(instrumentation.targetContext.getExternalFilesDir(null), "place-layout-$id.png").outputStream().use {
                instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            Espresso.pressBack()
            compose.waitUntil(5_000) { compose.onNodeWithTag("world-place-list").isDisplayed() }
        }
    }

    @Test fun realMainActivity_worldSummaryTracksTouchAndCanDismiss() {
        openWorld()
        chooseWorldPlace("Stay Alive")
        val handle = compose.onNodeWithTag("world-summary-handle")
        val root = compose.onRoot()
        val position = handle.fetchSemanticsNode().boundsInRoot
        root.performTouchInput {
            down(position.center)
            repeat(4) {
                advanceEventTime(24)
                moveBy(Offset(0f, 15f))
            }
        }
        try {
            compose.waitUntil(3_000) { handle.fetchSemanticsNode().boundsInRoot.top > position.top + 12f }
        } finally {
            root.performTouchInput {
                advanceEventTime(24)
                moveBy(Offset(0f, -60f))
                advanceEventTime(120)
                up()
            }
        }
        compose.onNodeWithTag("world-summary-detail").assertIsEnabled()
        compose.onNodeWithTag("world-summary-close").performClick()
        compose.onNodeWithTag("world-summary-detail").assertDoesNotExist()
        compose.onNodeWithTag("world-place-list").assertIsDisplayed()
    }

    @Test fun worldChooserWorksBeforeMapAndOnlyTheLatestSelectionMovesIt() {
        val gate = CompletableDeferred<Unit>()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scene = Scene("scene", "artist", "艺人", "测试作品", imageStatus = "approved", verificationStatus = "verified")
        val catalog = Catalog(listOf(
            Place("north-test", "北方测试地点", "巴黎", "法国", 48.85, 2.35, scenes = listOf(scene)),
            Place("south-test", "南方测试地点", "悉尼", "澳大利亚", -33.87, 151.20, scenes = listOf(scene.copy(id = "scene2"))),
        ))
        lateinit var browse: RoomBrowseState
        compose.runOnUiThread {
            browse = RoomBrowseState(context)
            compose.activity.setContent {
                PilgrimageTheme {
                    CompositionLocalProvider(LocalPhysicalMotion provides false) {
                        DisposableEffect(browse) { onDispose { browse.worldScene?.destroy() } }
                        SwissWorldWall(catalog, browse, emptySet(), false, {}, {}, {}, {}, {}, {}, {}, emptySet(), true,
                            awaitMapStartup = { gate.await() })
                    }
                }
            }
        }
        chooseWorldPlace("北方测试地点")
        compose.onNodeWithTag("world-summary-title").assertTextEquals("北方测试地点")
        chooseWorldPlace("南方测试地点")
        compose.onNodeWithTag("world-summary-title").assertTextEquals("南方测试地点")
        assertNull(compose.runOnUiThread { browse.worldScene })
        compose.runOnUiThread { gate.complete(Unit) }
        compose.waitUntil(20_000) {
            evalWebByUrl("world.tingzong.local",
                "Boolean(window.worldDiagnostics?.().state.selected === 'south-test' && window.worldDiagnostics().state.zoom >= 11)") == "true"
        }
        assertEquals(2L, compose.runOnUiThread { browse.worldFocus?.token })
        compose.onNodeWithTag("world-summary-close").performClick()
        compose.onNodeWithTag("world-place-summary").assertDoesNotExist()
    }

    @Test
    fun realPreviewActivity_quickBackAndReenterSameAlbum_and_predictiveHalfwayCancelCommit() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("wall-tab-0").fetchSemanticsNodes().isNotEmpty()
        }
        val displayWidth = compose.activity.resources.displayMetrics.widthPixels.toFloat()
        val firstCell = SemanticsMatcher("visible-room-release-cover") { node ->
            val tag = node.config.getOrElse(SemanticsProperties.TestTag) { "" }
            tag.startsWith("room-release-") &&
                node.boundsInRoot.width > 12f &&
                node.boundsInRoot.center.x in 1f..(displayWidth - 1f)
        }
        compose.waitUntil(10_000) {
            compose.onAllNodes(firstCell, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        val firstTag = compose.onAllNodes(firstCell, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .first()
            .config[SemanticsProperties.TestTag]

        // Enter A1, immediately Back, and immediately re-enter A2 for the SAME release before old A1 finishes disposing.
        compose.onNodeWithTag(firstTag, useUnmergedTree = true).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("native-release-archive", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("wall-tab-0").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(firstTag, useUnmergedTree = true).performClick()
        compose.onNodeWithTag("native-release-archive").assertIsDisplayed()

        // Predictive back ~50% progress -> cancel -> still on A2; then ~50% progress -> commit -> returns to Shelf with all 4 tabs ready.
        compose.runOnUiThread {
            val d = compose.activity.onBackPressedDispatcher
            d.dispatchOnBackStarted(BackEventCompat(0f, 360f, 0f, BackEventCompat.EDGE_LEFT))
            d.dispatchOnBackProgressed(BackEventCompat(160f, 360f, 0.50f, BackEventCompat.EDGE_LEFT))
            d.dispatchOnBackCancelled()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("release-archive-title").assertIsDisplayed()

        compose.runOnUiThread {
            val d = compose.activity.onBackPressedDispatcher
            d.dispatchOnBackStarted(BackEventCompat(0f, 360f, 0f, BackEventCompat.EDGE_LEFT))
            d.dispatchOnBackProgressed(BackEventCompat(180f, 360f, 0.55f, BackEventCompat.EDGE_LEFT))
            d.onBackPressed()
        }
        compose.waitUntil(6_000) {
            compose.onAllNodesWithTag("wall-tab-0").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("wall-tab-3").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("wall-tab-0").assertIsDisplayed()
        compose.onNodeWithTag("wall-tab-3").assertIsDisplayed()
    }
}
