package cn.musicpilgrimage.app

import android.annotation.SuppressLint
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.down
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import cn.musicpilgrimage.app.data.Catalog
import cn.musicpilgrimage.app.data.Place
import cn.musicpilgrimage.app.data.ReleaseArtwork
import cn.musicpilgrimage.app.data.Scene
import cn.musicpilgrimage.app.performance.Judgment
import cn.musicpilgrimage.app.performance.LibraryDatabase
import cn.musicpilgrimage.app.performance.MusicEvent
import cn.musicpilgrimage.app.performance.PerformanceController
import cn.musicpilgrimage.app.performance.PerformanceScreen
import cn.musicpilgrimage.app.performance.PerformanceUi
import cn.musicpilgrimage.app.performance.PlayOptions
import cn.musicpilgrimage.app.performance.PlayState
import cn.musicpilgrimage.app.performance.RollingTabularDigits
import cn.musicpilgrimage.app.performance.SongPackage
import cn.musicpilgrimage.app.performance.SongSection
import cn.musicpilgrimage.app.performance.Target
import cn.musicpilgrimage.app.ui.AppBottomSheet
import cn.musicpilgrimage.app.ui.AppCenterConfirmDialog
import cn.musicpilgrimage.app.ui.AppPhotoViewerCoordinator
import cn.musicpilgrimage.app.ui.AppPhotoViewerPortal
import cn.musicpilgrimage.app.ui.AppPredictiveBackHandler
import cn.musicpilgrimage.app.ui.AppSavedConfirmNotice
import cn.musicpilgrimage.app.ui.AppSheetCoordinator
import cn.musicpilgrimage.app.ui.CoverTransitionCoordinator
import cn.musicpilgrimage.app.ui.CoverTransitionOverlay
import cn.musicpilgrimage.app.ui.LocalAppHaptics
import cn.musicpilgrimage.app.ui.LocalCoverCoordinator
import cn.musicpilgrimage.app.ui.LocalPhotoViewerCoordinator
import cn.musicpilgrimage.app.ui.LocalPhysicalMotion
import cn.musicpilgrimage.app.ui.LocalRouteActive
import cn.musicpilgrimage.app.ui.LocalRouteEntryId
import cn.musicpilgrimage.app.ui.LocalRouteIsCurrent
import cn.musicpilgrimage.app.ui.LocalSheetCoordinator
import cn.musicpilgrimage.app.ui.PilgrimageTheme
import cn.musicpilgrimage.app.ui.rememberAppHaptics
import cn.musicpilgrimage.app.ui.room.RoomBrowseState
import cn.musicpilgrimage.app.ui.room.RoomCommunityPhotoViewer
import cn.musicpilgrimage.app.ui.room.RoomPerformanceResult
import cn.musicpilgrimage.app.ui.room.RoomWall
import cn.musicpilgrimage.app.ui.room.SwissWebDetail
import cn.musicpilgrimage.app.ui.room.SwissWorldWall
import cn.musicpilgrimage.app.ui.room.WorldFocusKind
import cn.musicpilgrimage.app.ui.room.swissDetailData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PrototypeMotionUiTest {
    @get:Rule val compose = createComposeRule()

    @SuppressLint("VisibleForTests")
    @Test
    fun predictiveBackCancelAndLayerPriority_preservesSheetAndDirtyForm() {
        var sheetOpen by mutableStateOf(true)
        var dirtyFormOpen by mutableStateOf(true)
        var discardDialogVisible by mutableStateOf(false)
        var backStartedCount by mutableIntStateOf(0)
        var backCanceledCount by mutableIntStateOf(0)
        lateinit var dispatcher: OnBackPressedDispatcher

        compose.setContent {
            val owner = requireNotNull(LocalOnBackPressedDispatcherOwner.current)
            dispatcher = owner.onBackPressedDispatcher
            val sheetCoord = remember { AppSheetCoordinator() }
            val haptics = rememberAppHaptics()
            PilgrimageTheme {
                CompositionLocalProvider(
                    LocalPhysicalMotion provides true,
                    LocalSheetCoordinator provides sheetCoord,
                    LocalAppHaptics provides haptics,
                ) {
                    RoomWall(Modifier.fillMaxSize()) {
                        if (dirtyFormOpen) {
                            Box(Modifier.fillMaxSize().testTag("dirty-form-editor")) {
                                Text("未保存草稿")
                            }
                            AppPredictiveBackHandler(
                                enabled = true,
                                onStart = { backStartedCount++ },
                                onCancel = { backCanceledCount++ },
                                onBack = { discardDialogVisible = true },
                            )
                        }
                        AppBottomSheet(
                            visible = sheetOpen,
                            busy = false,
                            onDismissRequest = { sheetOpen = false },
                        ) { _, _ ->
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(220.dp)
                                    .padding(20.dp)
                                    .testTag("predictive-sheet-body"),
                            ) {
                                Text("加入行程面板")
                            }
                        }
                        sheetCoord.entries.forEach { portalEntry ->
                            key(portalEntry.id) { portalEntry.content() }
                        }
                        if (discardDialogVisible) {
                            AppCenterConfirmDialog(onDismissRequest = { discardDialogVisible = false }) {
                                Column(Modifier.padding(20.dp)) {
                                    Text("放弃修改？")
                                    Button(onClick = { discardDialogVisible = false }) { Text("继续编辑") }
                                    Button(onClick = {
                                        discardDialogVisible = false
                                        dirtyFormOpen = false
                                    }) { Text("放弃") }
                                }
                            }
                        }
                    }
                }
            }
        }

        compose.waitForIdle()
        compose.onNodeWithTag("predictive-sheet-body").assertIsDisplayed()

        // Start predictive back gesture across bottom sheet and cancel midway: sheet stays intact.
        compose.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(16f, 400f, 0.1f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(180f, 400f, 0.52f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackCancelled()
        }
        compose.waitForIdle()
        assertTrue(sheetOpen)
        assertTrue(dirtyFormOpen)
        assertFalse(discardDialogVisible)
        compose.onNodeWithTag("predictive-sheet-body").assertIsDisplayed()

        // Commit back: only topmost layer (bottom sheet) dismisses first; dirty form remains open.
        compose.runOnUiThread { dispatcher.onBackPressed() }
        compose.waitForIdle()
        assertFalse(sheetOpen)
        assertTrue(dirtyFormOpen)
        assertFalse(discardDialogVisible)

        // Start & cancel predictive back on dirty form: handler is restored intact and no dialog opens.
        compose.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(16f, 400f, 0.1f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(160f, 400f, 0.45f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackCancelled()
        }
        compose.waitForIdle()
        assertTrue(backStartedCount >= 1)
        assertTrue(backCanceledCount >= 1)
        assertFalse(discardDialogVisible)

        // Commit back on dirty form opens discard confirmation rather than dropping changes.
        compose.runOnUiThread { dispatcher.onBackPressed() }
        compose.waitForIdle()
        assertTrue(discardDialogVisible)
        compose.onNodeWithText("放弃修改？").assertIsDisplayed()
        compose.onNodeWithText("继续编辑").performClick()
        compose.waitForIdle()
        assertFalse(discardDialogVisible)
        assertTrue(dirtyFormOpen)
    }

    @Test
    fun bottomSheet_busyReboundDismissAndReduceMotionConfirm() {
        var visible by mutableStateOf(true)
        var busy by mutableStateOf(true)
        var motion by mutableStateOf(true)
        var savedVisible by mutableStateOf(false)
        lateinit var dispatcher: OnBackPressedDispatcher

        compose.setContent {
            dispatcher = requireNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            val sheetCoord = remember { AppSheetCoordinator() }
            val haptics = rememberAppHaptics()
            PilgrimageTheme {
                CompositionLocalProvider(
                    LocalPhysicalMotion provides motion,
                    LocalSheetCoordinator provides sheetCoord,
                    LocalAppHaptics provides haptics,
                ) {
                    RoomWall(Modifier.fillMaxSize()) {
                        AppBottomSheet(
                            visible = visible,
                            busy = busy,
                            onDismissRequest = { visible = false },
                        ) { _, _ ->
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(260.dp)
                                    .padding(20.dp)
                                    .testTag("busy-sheet-body"),
                            ) {
                                Text("行程列表内容")
                            }
                        }
                        sheetCoord.entries.forEach { portalEntry ->
                            key(portalEntry.id) { portalEntry.content() }
                        }
                        AppSavedConfirmNotice(message = "已加入行程", visible = savedVisible)
                    }
                }
            }
        }

        compose.waitForIdle()
        compose.onNodeWithTag("busy-sheet-body").assertIsDisplayed()

        // While busy = true, downward swipe must rubber-band back rather than closing mid-save.
        compose.onNodeWithTag("busy-sheet-body").performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertTrue(visible)
        compose.onNodeWithTag("busy-sheet-body").assertIsDisplayed()

        // Once save completes (busy = false), downward swipe dismisses sheet & triggers confirmation.
        busy = false
        compose.waitForIdle()
        compose.onNodeWithTag("busy-sheet-body").performTouchInput { swipeDown() }
        compose.waitUntil(3_000) { !visible }
        assertFalse(visible)
        compose.onNodeWithTag("app-bottom-sheet-surface").assertDoesNotExist()

        motion = false
        visible = true
        compose.waitForIdle()
        val sheet = compose.onNodeWithTag("app-bottom-sheet-surface")
        val settledBounds = sheet.fetchSemanticsNode().boundsInRoot
        compose.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 300f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(80f, 300f, 0.6f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        assertEquals(settledBounds, sheet.fetchSemanticsNode().boundsInRoot)
        compose.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
        sheet.assertIsDisplayed()
        compose.runOnUiThread { dispatcher.onBackPressed() }
        compose.waitUntil(3_000) { !visible }

        // With Reduce Motion ON, confirmation toast renders its final readable state immediately.
        savedVisible = true
        compose.waitForIdle()
        compose.onNodeWithText("已加入行程").assertIsDisplayed()
    }

    @Test
    fun originalPhotoViewerOffersRetryAfterLoadFailure() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val directory = kotlin.io.path.createTempDirectory(context.cacheDir.toPath(), "original-photo-").toFile()
        val missingImage = File(directory, "original.png")
        try {
            compose.setContent {
                PilgrimageTheme {
                    RoomCommunityPhotoViewer(
                        model = missingImage,
                        description = "MV 截图",
                        showOriginalLoadStatus = true,
                        onClosed = {},
                    )
                }
            }
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasText("原图暂时无法加载")).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("关闭大图").assertIsDisplayed()
            missingImage.outputStream().use { output ->
                val bitmap = Bitmap.createBitmap(128, 72, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(AndroidColor.RED)
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                bitmap.recycle()
            }
            compose.onNodeWithText("重试").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasText("正在载入原图…") or hasText("原图暂时无法加载"))
                    .fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithContentDescription("MV 截图").assertIsDisplayed()
        } finally {
            missingImage.delete()
            directory.delete()
        }
    }

    @Test
    fun inPlacePhotoViewer_morphFromBoundsDragDismissAndReduceMotion() {
        var open by mutableStateOf(true)
        var motion by mutableStateOf(true)
        var closedCount by mutableIntStateOf(0)
        lateinit var viewerCoord: AppPhotoViewerCoordinator
        lateinit var dispatcher: OnBackPressedDispatcher

        compose.setContent {
            dispatcher = requireNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            viewerCoord = remember { AppPhotoViewerCoordinator() }
            val haptics = rememberAppHaptics()
            PilgrimageTheme {
                CompositionLocalProvider(
                    LocalPhysicalMotion provides motion,
                    LocalPhotoViewerCoordinator provides viewerCoord,
                    LocalAppHaptics provides haptics,
                ) {
                    RoomWall(Modifier.fillMaxSize()) {
                        if (open) {
                            RoomCommunityPhotoViewer(
                                model = null,
                                description = "塞纳河畔实景图",
                                credit = "示例摄影",
                                sourceBoundsInRoot = Rect(48f, 240f, 348f, 480f),
                                sourceAspectRatio = 1.25f,
                                sourceIsCroppedCover = true,
                                sourceId = "place-photo-1",
                                onClosed = {
                                    closedCount++
                                    open = false
                                },
                            )
                        }
                        AppPhotoViewerPortal(viewerCoord)
                    }
                }
            }
        }

        compose.waitForIdle()
        compose.onNodeWithTag("room-community-photo-viewer").assertIsDisplayed()
        assertEquals("place-photo-1", viewerCoord.hiddenPhotoId)
        compose.onNodeWithContentDescription("关闭大图").assertIsDisplayed()

        // Swipe down at initial zoom closes viewer cleanly and restores hidden thumbnail visibility.
        compose.onNodeWithTag("room-community-photo-viewer").performTouchInput {
            swipeDown(startY = 300f, endY = 980f)
        }
        compose.waitUntil(3_000) { closedCount == 1 }
        compose.waitForIdle()
        assertFalse(open)
        assertNull(viewerCoord.hiddenPhotoId)

        // Reduce Motion ON: opens & closes immediately without blocking UI interaction.
        motion = false
        open = true
        compose.waitForIdle()
        compose.onNodeWithTag("room-community-photo-viewer").assertIsDisplayed()
        val image = compose.onNodeWithContentDescription("塞纳河畔实景图")
        val viewerBounds = image.fetchSemanticsNode().boundsInRoot
        compose.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 300f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(80f, 300f, 0.6f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        assertEquals(viewerBounds, image.fetchSemanticsNode().boundsInRoot)
        compose.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
        image.assertIsDisplayed()
        compose.runOnUiThread { dispatcher.onBackPressed() }
        compose.waitUntil(2_000) { closedCount == 2 }
        assertNull(viewerCoord.hiddenPhotoId)
    }

    @Test
    fun performanceResult_instantActionAndRollingNumeralSemantics() {
        compose.mainClock.autoAdvance = false
        var generation by mutableIntStateOf(0)
        var countdownDigit by mutableStateOf("3")
        val song = SongPackage(
            id = "test-track",
            version = 1,
            title = "夜曲",
            artist = "示例音乐人",
            referenceEdition = "标准版",
            durationUs = 12_000_000L,
            events = emptyList(),
            easy = emptyList(),
            standard = emptyList(),
            sections = emptyList(),
            placeIds = emptyList(),
            sources = JSONObject(),
        )
        val ui = PerformanceUi(
            state = PlayState.FINISHED,
            score = 9860,
            bestCombo = 24,
            judgments = listOf(Judgment.PERFECT, Judgment.GOOD, Judgment.PARTIAL, Judgment.MISSED),
            newRecord = true,
        )

        compose.setContent {
            val haptics = rememberAppHaptics()
            PilgrimageTheme {
                CompositionLocalProvider(
                    LocalPhysicalMotion provides true,
                    LocalAppHaptics provides haptics,
                ) {
                    RoomWall(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize()) {
                            RollingTabularDigits(
                                valueText = countdownDigit,
                                fontSize = 36.sp,
                                color = Color.Black,
                                motion = true,
                                modifier = Modifier.testTag("test-countdown-digits"),
                            )
                            RoomPerformanceResult(
                                song = song,
                                ui = ui,
                                artwork = ReleaseArtwork("rel-test", "夜曲", 2024, "Single", null),
                                preview = false,
                                onAgain = { generation++ },
                                onExplore = null,
                                onRetrySave = {},
                                attemptGeneration = generation,
                            )
                        }
                    }
                }
            }
        }

        // Frame 0: title, final score, and Replay action must be immediately clickable without waiting for stagger.
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("test-countdown-digits").assertIsDisplayed()
        compose.onNodeWithTag("performance-result").assertIsDisplayed()
        compose.onNodeWithTag("performance-result-score").assertIsDisplayed()
        compose.onNodeWithTag("performance-again").performClick()
        assertEquals(1, generation)

        // Advancing clock reveals staggered accuracy & new record cleanly and updates rolling digit atomically.
        countdownDigit = "2"
        compose.mainClock.advanceTimeBy(280)
        compose.waitForIdle()
        compose.onNodeWithTag("performance-result-accuracy").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("51.2%").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("新纪录").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("2").assertExists()
    }

    @SuppressLint("VisibleForTests")
    @Test
    fun bottomSheet_midDismissUpwardTakeover_midAndHighDetents_singleDismissCallback() {
        var open by mutableStateOf(true)
        var dismissCount by mutableIntStateOf(0)
        var lastExpanded by mutableStateOf(false)
        var underlyingBackCount by mutableIntStateOf(0)
        lateinit var dispatcher: OnBackPressedDispatcher

        compose.mainClock.autoAdvance = false
        try {
            compose.setContent {
                dispatcher = requireNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
                val sheetCoord = remember { AppSheetCoordinator() }
                val haptics = rememberAppHaptics()
                PilgrimageTheme {
                    CompositionLocalProvider(
                        LocalPhysicalMotion provides true,
                        LocalSheetCoordinator provides sheetCoord,
                        LocalAppHaptics provides haptics,
                    ) {
                        RoomWall(Modifier.fillMaxSize()) {
                            AppPredictiveBackHandler(enabled = true, onBack = { underlyingBackCount++ })
                            AppBottomSheet(
                                visible = open,
                                busy = false,
                                allowExpandDetent = true,
                                onDismissRequest = {
                                    dismissCount++
                                    open = false
                                },
                            ) { isExpanded, _ ->
                                lastExpanded = isExpanded
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .padding(20.dp)
                                        .testTag("takeover-sheet-body"),
                                ) {
                                    Text(if (isExpanded) "已展开高挡" else "默认中挡")
                                }
                            }
                            sheetCoord.entries.forEach { portalEntry ->
                                key(portalEntry.id) { portalEntry.content() }
                            }
                        }
                    }
                }
            }

            compose.mainClock.advanceTimeBy(900)
            compose.waitForIdle()
            val surface = compose.onNodeWithTag("app-bottom-sheet-surface")
            surface.assertIsDisplayed()
            val midHeight = surface.fetchSemanticsNode().size.height
            assertFalse(lastExpanded)
            assertEquals(0, dismissCount)

            // Start dismissing downward (~215px -> DISMISSING), then grab midway and pull gently up -> settles back to MID detent.
            surface.performTouchInput {
                swipeDown(startY = 20f, endY = 240f, durationMillis = 100L)
            }
            compose.mainClock.advanceTimeBy(16)
            assertEquals(0, dismissCount)
            assertTrue(open)

            surface.performTouchInput {
                down(Offset(centerX, midHeight * 0.85f))
                repeat(8) {
                    advanceEventTime(90L)
                    moveBy(Offset(0f, -45f))
                }
                up()
            }
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            assertTrue(open)
            assertEquals(0, dismissCount)
            assertFalse(lastExpanded)
            compose.onNodeWithText("默认中挡").assertIsDisplayed()

            // Start dismissing again, grab midway and pull strongly upward -> settles to HIGH detent.
            surface.performTouchInput {
                swipeDown(startY = 20f, endY = 240f, durationMillis = 100L)
            }
            compose.mainClock.advanceTimeBy(16)
            assertEquals(0, dismissCount)

            surface.performTouchInput {
                down(Offset(centerX, midHeight * 0.85f))
                repeat(11) {
                    advanceEventTime(16L)
                    moveBy(Offset(0f, -85f))
                }
                up()
            }
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            assertTrue(open)
            assertEquals(0, dismissCount)
            assertTrue(lastExpanded)
            val highHeight = surface.fetchSemanticsNode().size.height
            assertTrue("Expected high detent > mid detent ($highHeight vs $midHeight)", highHeight > midHeight + 100)
            compose.onNodeWithText("已展开高挡").assertIsDisplayed()

            // Fire repeated Back presses while sheet is closing -> consumes all Back presses and fires dismiss once.
            compose.runOnUiThread {
                dispatcher.onBackPressed()
                dispatcher.onBackPressed()
            }
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            assertFalse(open)
            assertEquals(1, dismissCount)
            assertEquals(0, underlyingBackCount)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun bottomSheet_openInterrupt_nestedScrollTop_busyRecovery_andCompactBounds() {
        var open by mutableStateOf(true)
        var busy by mutableStateOf(false)
        var dismissCount by mutableIntStateOf(0)
        var compactOpen by mutableStateOf(false)
        var listStateRef: androidx.compose.foundation.lazy.LazyListState? = null

        compose.mainClock.autoAdvance = false
        try {
            compose.setContent {
                val sheetCoord = remember { AppSheetCoordinator() }
                val haptics = rememberAppHaptics()
                PilgrimageTheme {
                    CompositionLocalProvider(
                        LocalPhysicalMotion provides true,
                        LocalSheetCoordinator provides sheetCoord,
                        LocalAppHaptics provides haptics,
                    ) {
                        RoomWall(Modifier.fillMaxSize()) {
                            if (!compactOpen) {
                                AppBottomSheet(
                                    visible = open,
                                    busy = busy,
                                    allowExpandDetent = true,
                                    onDismissRequest = {
                                        dismissCount++
                                        open = false
                                    },
                                ) { _, _ ->
                                    val listState = rememberLazyListState()
                                    listStateRef = listState
                                    LazyColumn(
                                        state = listState,
                                        modifier = Modifier.fillMaxSize().testTag("sheet-nested-list"),
                                    ) {
                                        items(28) { idx ->
                                            Text(
                                                "行程条目 $idx",
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 20.dp),
                                            )
                                        }
                                    }
                                }
                                sheetCoord.entries.forEach { portalEntry ->
                                    key(portalEntry.id) { portalEntry.content() }
                                }
                            } else {
                                Box(Modifier.width(640.dp).height(340.dp).testTag("landscape-viewport")) {
                                    val compactCoord = remember { AppSheetCoordinator() }
                                    CompositionLocalProvider(LocalSheetCoordinator provides compactCoord) {
                                        AppBottomSheet(
                                            visible = true,
                                            busy = false,
                                            allowExpandDetent = true,
                                            onDismissRequest = {},
                                        ) { _, _ ->
                                            Box(Modifier.fillMaxSize().padding(16.dp)) {
                                                Text("横屏安全区域内容")
                                            }
                                        }
                                        compactCoord.entries.forEach { portalEntry ->
                                            key(portalEntry.id) { portalEntry.content() }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Interrupt opening animation midway -> touch visible part of opening sheet and swipe down to close once.
            compose.mainClock.advanceTimeBy(96)
            compose.onNodeWithTag("app-bottom-sheet-surface").performTouchInput {
                swipeDown(startY = height * 0.55f, endY = height * 0.96f, durationMillis = 100L)
            }
            compose.mainClock.advanceTimeBy(900)
            compose.waitForIdle()
            assertEquals(1, dismissCount)
            assertFalse(open)

            // Re-open, scroll list down -> downward swipe scrolls list first without dismissing.
            dismissCount = 0
            open = true
            compose.mainClock.advanceTimeBy(900)
            compose.waitForIdle()
            compose.runOnUiThread {
                kotlinx.coroutines.runBlocking { listStateRef?.scrollToItem(10) }
            }
            compose.mainClock.advanceTimeBy(64)
            compose.onNodeWithTag("sheet-nested-list").performTouchInput {
                swipeDown(startY = centerY - 60f, endY = centerY + 80f, durationMillis = 120L)
            }
            compose.mainClock.advanceTimeBy(600)
            compose.waitForIdle()
            assertEquals(0, dismissCount)
            assertTrue(open)

            // Scroll back to top index 0 -> swipe down transfers to sheet and triggers busy recovery mid-exit.
            compose.runOnUiThread {
                kotlinx.coroutines.runBlocking { listStateRef?.scrollToItem(0) }
            }
            compose.mainClock.advanceTimeBy(64)
            compose.onNodeWithTag("app-bottom-sheet-surface").performTouchInput {
                swipeDown(startY = 20f, endY = height * 0.42f, durationMillis = 120L)
            }
            compose.mainClock.advanceTimeBy(32)
            busy = true
            compose.mainClock.advanceTimeBy(800)
            compose.waitForIdle()
            assertEquals(0, dismissCount)
            assertTrue(open)

            // Compact landscape viewport keeps drag handle inside container vertical bounds.
            compactOpen = true
            compose.mainClock.advanceTimeBy(800)
            compose.waitForIdle()
            val viewportBounds = compose.onNodeWithTag("landscape-viewport").fetchSemanticsNode().boundsInRoot
            val handleNode = compose.onNodeWithTag("app-bottom-sheet-handle")
            handleNode.assertIsDisplayed()
            val handleBounds = handleNode.fetchSemanticsNode().boundsInRoot
            assertTrue(handleBounds.top >= viewportBounds.top)
            assertTrue(handleBounds.bottom <= viewportBounds.bottom)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @SuppressLint("VisibleForTests")
    @Test
    fun inPlacePhotoViewer_entranceCloseTap_repeatBackDuringClose_andPredictiveCancel() {
        var open by mutableStateOf(true)
        var instanceKey by mutableIntStateOf(0)
        var closedCount by mutableIntStateOf(0)
        var underlyingBackCount by mutableIntStateOf(0)
        lateinit var dispatcher: OnBackPressedDispatcher

        compose.mainClock.autoAdvance = false
        try {
            compose.setContent {
                dispatcher = requireNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
                val viewerCoord = remember { AppPhotoViewerCoordinator() }
                val haptics = rememberAppHaptics()
                PilgrimageTheme {
                    CompositionLocalProvider(
                        LocalPhysicalMotion provides true,
                        LocalPhotoViewerCoordinator provides viewerCoord,
                        LocalAppHaptics provides haptics,
                    ) {
                        RoomWall(Modifier.fillMaxSize()) {
                            AppPredictiveBackHandler(enabled = true, onBack = { underlyingBackCount++ })
                            if (open) {
                                key(instanceKey) {
                                    RoomCommunityPhotoViewer(
                                        model = null,
                                        description = "街角唱片店实景",
                                        credit = "测试摄影",
                                        sourceBoundsInRoot = Rect(40f, 220f, 340f, 460f),
                                        sourceAspectRatio = 1.25f,
                                        sourceIsCroppedCover = false,
                                        sourceId = "photo-$instanceKey",
                                        onClosed = {
                                            closedCount++
                                            open = false
                                        },
                                    )
                                }
                            }
                            AppPhotoViewerPortal(viewerCoord)
                        }
                    }
                }
            }

            // Close button is mounted and clickable right during OPENING entrance animation.
            compose.mainClock.advanceTimeBy(48)
            compose.onNodeWithContentDescription("关闭大图").performClick()
            compose.mainClock.advanceTimeBy(800)
            compose.waitForIdle()
            assertEquals(1, closedCount)
            assertEquals(0, underlyingBackCount)

            // Re-open, settle, cancel predictive back -> viewer remains open, then repeat Back during CLOSING stays single-pop.
            instanceKey++
            open = true
            compose.mainClock.advanceTimeBy(700)
            compose.waitForIdle()
            compose.onNodeWithTag("room-community-photo-viewer").assertIsDisplayed()

            compose.runOnUiThread {
                dispatcher.dispatchOnBackStarted(BackEventCompat(16f, 400f, 0.1f, BackEventCompat.EDGE_LEFT))
                dispatcher.dispatchOnBackProgressed(BackEventCompat(180f, 400f, 0.48f, BackEventCompat.EDGE_LEFT))
                dispatcher.dispatchOnBackCancelled()
            }
            compose.mainClock.advanceTimeBy(400)
            compose.waitForIdle()
            assertEquals(1, closedCount)
            compose.onNodeWithTag("room-community-photo-viewer").assertIsDisplayed()

            compose.runOnUiThread {
                dispatcher.onBackPressed()
                dispatcher.onBackPressed()
            }
            compose.mainClock.advanceTimeBy(800)
            compose.waitForIdle()
            assertEquals(2, closedCount)
            assertEquals(0, underlyingBackCount)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun performanceResult_instantExploreAction_andReduceMotionDigitsSync() {
        var generation by mutableIntStateOf(0)
        var exploreCount by mutableIntStateOf(0)
        var digit by mutableStateOf("3")
        val song = SongPackage(
            id = "test-track-2",
            version = 1,
            title = "前奏曲",
            artist = "示例音乐人",
            referenceEdition = "标准版",
            durationUs = 10_000_000L,
            events = emptyList(),
            easy = emptyList(),
            standard = emptyList(),
            sections = emptyList(),
            placeIds = emptyList(),
            sources = JSONObject(),
        )
        val ui = PerformanceUi(
            state = PlayState.FINISHED,
            score = 8420,
            bestCombo = 16,
            judgments = listOf(Judgment.PERFECT, Judgment.GOOD),
            newRecord = false,
        )

        compose.mainClock.autoAdvance = false
        try {
            compose.setContent {
                val haptics = rememberAppHaptics()
                PilgrimageTheme {
                    CompositionLocalProvider(
                        LocalPhysicalMotion provides false,
                        LocalAppHaptics provides haptics,
                    ) {
                        RoomWall(Modifier.fillMaxSize()) {
                            Column(Modifier.fillMaxSize()) {
                                RollingTabularDigits(
                                    valueText = digit,
                                    fontSize = 36.sp,
                                    color = Color.Black,
                                    motion = false,
                                )
                                RoomPerformanceResult(
                                    song = song,
                                    ui = ui,
                                    artwork = ReleaseArtwork("rel-2", "前奏曲", 2024, "Single", null),
                                    preview = false,
                                    onAgain = { generation++ },
                                    onExplore = { exploreCount++ },
                                    onRetrySave = {},
                                    attemptGeneration = generation,
                                )
                            }
                        }
                    }
                }
            }

            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithTag("performance-again").performClick()
            compose.onNodeWithTag("performance-explore").performClick()
            assertEquals(1, generation)
            assertEquals(1, exploreCount)

            digit = "2"
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithText("2").assertExists()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @ExtendedDeviceTest
    @Test
    fun detailMedia_placeObjectFitAndClipBounds_jsContract() {
        val posted = CopyOnWriteArrayList<JSONObject>()
        var webRef: WebView? = null
        val pageReady = CountDownLatch(1)
        lateinit var adapterJs: String

        compose.setContent {
            val context = LocalContext.current
            adapterJs = remember {
                context.assets.open("design/swiss-details/native-adapter.js").bufferedReader().use { it.readText() }
            }
            AndroidView(
                modifier = Modifier.width(360.dp).height(480.dp),
                factory = { ctx ->
                    WebView(ctx).apply {
                        setBackgroundColor(AndroidColor.WHITE)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        addJavascriptInterface(
                            object {
                                @JavascriptInterface
                                fun postMessage(raw: String) {
                                    posted.add(JSONObject(raw))
                                }
                            },
                            "TingzongDetail",
                        )
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                pageReady.countDown()
                            }
                        }
                        val svgWide = "data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='200' height='100'><rect width='200' height='100' fill='gray'/></svg>"
                        val svgTall = "data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='100' height='400'><rect width='100' height='400' fill='silver'/></svg>"
                        val html = """
                            <!doctype html>
                            <html><head><style>
                              body{margin:0}
                              .media-btn{display:block;padding:0;border:0;width:300px;height:300px;max-height:300px;overflow:hidden}
                              .fit-contain{width:300px;height:300px;object-fit:contain;display:block}
                              .fit-cover{width:300px;height:300px;object-fit:cover;display:block}
                            </style></head>
                            <body>
                              <div id="app-view"><div class="scroll-content">
                                <button id="btn-contain" class="media-btn" data-action="image" data-src="$svgWide" data-credit="Tester">
                                  <img id="img-contain" class="fit-contain" src="$svgWide" alt="contain">
                                </button>
                                <button id="btn-cover" class="media-btn" data-action="image" data-src="$svgTall" data-credit="Tester">
                                  <img id="img-cover" class="fit-cover" src="$svgTall" alt="cover">
                                </button>
                                <button id="btn-retry" data-action="hero-retry">Retry</button>
                              </div></div>
                              <div id="overlay-root"></div>
                            </body></html>
                        """.trimIndent()
                        loadDataWithBaseURL("https://example.invalid/", html, "text/html", "utf-8", null)
                        webRef = this
                    }
                },
            )
        }

        compose.waitForIdle()
        assertTrue(pageReady.await(20, TimeUnit.SECONDS))
        val scriptInjected = CountDownLatch(1)
        compose.runOnUiThread {
            val stubHelpers = "window.shortCredit=function(x){return x||'';};window.sizeDetail=function(){};window.esc=function(x){return x||'';};window.icon=function(){return '';};\n"
            webRef?.evaluateJavascript(
                stubHelpers + adapterJs + "\n;D = { page: 'place', places: [{ id: 'place-1' }], payload: { id: 'place-1' } }; Boolean(window.__setHeroHidden);",
            ) {
                scriptInjected.countDown()
            } ?: scriptInjected.countDown()
        }
        assertTrue(scriptInjected.await(5, TimeUnit.SECONDS))
        compose.waitUntil(10_000) {
            var imagesDone = false
            val latch = CountDownLatch(1)
            compose.runOnUiThread {
                webRef?.evaluateJavascript(
                    "Boolean(document.getElementById('img-contain')?.complete && document.getElementById('img-cover')?.complete)",
                ) {
                    imagesDone = it == "true"
                    latch.countDown()
                } ?: latch.countDown()
            }
            latch.await(2, TimeUnit.SECONDS)
            imagesDone
        }

        posted.clear()
        compose.runOnUiThread {
            webRef?.evaluateJavascript("document.getElementById('btn-contain').click();", null)
        }
        compose.waitUntil(3_000) { posted.any { it.optString("action") == "archive-image" && it.optString("description") == "contain" } }
        val containMsg = posted.first { it.optString("action") == "archive-image" && it.optString("description") == "contain" }
        assertFalse(containMsg.getBoolean("cropped"))
        val containRect = containMsg.getJSONObject("rect")
        val containHeight = containRect.getDouble("bottom") - containRect.getDouble("top")
        // 200x100 natural inside 300x300 object-fit:contain paints a 300x150 centered box, not 300x300!
        assertTrue("Expected letterboxed painted height (~150), got $containHeight", containHeight in 135.0..165.0)

        compose.runOnUiThread {
            webRef?.evaluateJavascript("document.getElementById('btn-cover').click();", null)
        }
        compose.waitUntil(3_000) { posted.any { it.optString("action") == "archive-image" && it.optString("description") == "cover" } }
        val coverMsg = posted.first { it.optString("action") == "archive-image" && it.optString("description") == "cover" }
        assertTrue(coverMsg.getBoolean("cropped"))

        compose.runOnUiThread {
            webRef?.evaluateJavascript("document.getElementById('btn-retry').click();", null)
        }
        compose.waitUntil(3_000) { posted.any { it.optString("action") == "hero-retry" } }
    }

    private fun createTestPngFile(dir: File, name: String, width: Int = 96, height: Int = 96): File {
        val file = File(dir, name)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(AndroidColor.rgb(42, 68, 92))
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return file
    }

    private fun findFirstWebView(root: View?): WebView? {
        if (root is WebView) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findFirstWebView(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun evalSync(web: WebView, script: String): String {
        val latch = CountDownLatch(1)
        var out = ""
        compose.runOnUiThread {
            web.evaluateJavascript(script) { raw ->
                out = raw ?: ""
                latch.countDown()
            }
        }
        check(latch.await(5, TimeUnit.SECONDS)) { "Timed out waiting for WebView evaluateJavascript" }
        return out
    }

    private fun decodeJsonString(raw: String): JSONObject {
        val unquoted = org.json.JSONTokener(raw).nextValue() as String
        return JSONObject(unquoted)
    }

    @Test
    fun coverTransition_p2_1_slowTimeout_degrade_lateArrival_retry_and_droppedVisualConfirm() {
        val badUrl = "data:image/png;base64,INVALID"
        val fastAsset = "file:///android_asset/design/swiss-details/media/full-samaritaine-jay-1.jpg"
        val lateAsset = "file:///android_asset/design/swiss-details/media/full-le-train-bleu-jay-1.jpg"
        val actions = CopyOnWriteArrayList<String>()

        fun makeAlbumData(relId: String, imageUrl: String): JSONObject {
            val row = JSONObject()
                .put("id", relId)
                .put("title", "测试专辑 $relId")
                .put("artist", "测试音乐人")
                .put("year", "2026")
                .put("image", imageUrl)
                .put("credit", "测试封面来源")
                .put("imageWidth", 96)
                .put("imageHeight", 96)
                .put("works", JSONArray().put(JSONObject().put("title", "曲目一").put("places", JSONArray())))
                .put("places", JSONArray())
            return swissDetailData("album", relId)
                .put("releases", JSONArray().put(row))
                .put("fallbacks", JSONObject())
        }

        val coordinator = CoverTransitionCoordinator().apply {
            waitTimeoutMs = 1_500L
            revealConfirmTimeoutMs = 150L
        }
        var currentData by mutableStateOf(makeAlbumData("retry-rel", badUrl))
        var entryId by mutableStateOf("album-retry-1")
        var showDetail by mutableStateOf(false)
        var hostView: View? = null

        compose.setContent {
            val haptics = rememberAppHaptics()
            coordinator.updateViewportBounds(Rect(0f, 0f, 720f, 1616f))
            coordinator.updateShelfState("shelf-1", 0, 1L)
            listOf("retry-rel", "fast-rel", "late-rel").forEach { rel ->
                coordinator.updateSource(
                    key = "release:$rel",
                    model = fastAsset,
                    aspectRatio = 1f,
                    bounds = Rect(36f, 220f, 216f, 400f),
                    sleeve = true,
                    visible = true,
                    ownerEntryId = "shelf-1",
                    page = 0,
                    layoutVersion = 1L,
                    imageLoaded = true,
                )
            }
            PilgrimageTheme {
                CompositionLocalProvider(
                    LocalPhysicalMotion provides true,
                    LocalAppHaptics provides haptics,
                    LocalCoverCoordinator provides coordinator,
                    LocalRouteActive provides true,
                    LocalRouteIsCurrent provides { true },
                    LocalRouteEntryId provides entryId,
                ) {
                    RoomWall(Modifier.fillMaxSize()) {
                        hostView = LocalView.current
                        if (showDetail) {
                            SwissWebDetail(currentData) { action, _ -> actions.add(action) }
                        }
                        CoverTransitionOverlay(coordinator)
                    }
                }
            }
        }
        compose.waitForIdle()
        assertTrue(
            coordinator.startEnter(
                key = "release:retry-rel",
                route = "album/retry-rel",
                predictedToRect = Rect(0f, 120f, 720f, 840f),
                physicalMotion = true,
                sourceNavEntryId = "shelf-1",
                releaseId = "retry-rel",
            ),
        )
        showDetail = true

        compose.waitUntil(20_000) {
            var web: WebView? = null
            compose.runOnUiThread { web = findFirstWebView(hostView) }
            web != null && evalSync(web!!, "Boolean(document.querySelector('.hero-retry-button'))") == "true"
        }
        val web = checkNotNull(run {
            var w: WebView? = null
            compose.runOnUiThread { w = findFirstWebView(hostView) }
            w
        })
        compose.waitUntil(8_000) { coordinator.activeMorph == null }
        assertNull("Failing hero must clear morph overlay rather than hanging", coordinator.activeMorph)

        // Wrap updateNativeDetail so clicking hero-retry proves Kotlin bumped loadAttempt to 2, then supply valid asset.
        evalSync(
            web,
            """
            (() => {
              window.__retryAttempts = [];
              const orig = window.updateNativeDetail;
              window.updateNativeDetail = function() {
                const args = Array.from(arguments);
                const attempt = Number(args[4] || (args[1] && args[1].loadAttempt) || 0);
                if (attempt) window.__retryAttempts.push(attempt);
                return orig.apply(this, arguments);
              };
              document.querySelector('[data-action="hero-retry"]').click();
              return true;
            })()
            """.trimIndent(),
        )
        compose.waitUntil(5_000) {
            evalSync(web, "Boolean((window.__retryAttempts || []).some(a => a >= 2))") == "true"
        }
        currentData = makeAlbumData("retry-rel", fastAsset)
        compose.waitUntil(8_000) {
            evalSync(web, "Boolean(document.querySelector('.hero-image') && document.querySelector('.hero-image').complete && document.querySelector('.hero-image').naturalWidth > 0 && !document.querySelector('.hero-retry-card'))") == "true"
        }
        compose.waitUntil(5_000) {
            coordinator.destinationHeroRect("album:retry-rel") != null &&
                evalSync(web, "Boolean(document.querySelector('.hero-image') && !document.querySelector('.hero-retry-card'))") == "true"
        }

        // Part B: Fast load settles before timeout with no degraded fallback & overlay clears immediately.
        assertTrue(
            coordinator.startEnter(
                key = "release:fast-rel",
                route = "album/fast-rel",
                predictedToRect = Rect(0f, 120f, 720f, 840f),
                physicalMotion = true,
                sourceNavEntryId = "shelf-1",
                releaseId = "fast-rel",
            ),
        )
        entryId = "album-fast-1"
        currentData = makeAlbumData("fast-rel", fastAsset)
        compose.waitUntil(8_000) {
            coordinator.activeMorph == null &&
                coordinator.destinationHeroRect("album:fast-rel") != null &&
                evalSync(web, "Boolean(!window.__heroDegraded && !document.querySelector('.hero-timeout-overlay'))") == "true"
        }
        val fastDom = decodeJsonString(
            evalSync(
                web,
                """JSON.stringify({hidden: Boolean(window.__heroHidden), degraded: Boolean(window.__heroDegraded), opacity: getComputedStyle(document.querySelector('.hero-image')).opacity})""",
            ),
        )
        assertFalse(fastDom.getBoolean("hidden"))
        assertFalse(fastDom.getBoolean("degraded"))

        // Part C: Slow image exceeding waitTimeoutMs degrades in-place to timeout card, clears overlay, and completes late arrival without morph replay.
        coordinator.waitTimeoutMs = 220L
        evalSync(
            web,
            """
            (() => {
              if (!window.__origDecode) window.__origDecode = HTMLImageElement.prototype.decode;
              window.__slowDecodeResolvers = [];
              HTMLImageElement.prototype.decode = function() {
                if (this.src && this.src.includes('full-le-train-bleu-jay-1')) {
                  return new Promise(res => { window.__slowDecodeResolvers.push(res); });
                }
                return window.__origDecode.call(this);
              };
              return true;
            })()
            """.trimIndent(),
        )
        assertTrue(
            coordinator.startEnter(
                key = "release:late-rel",
                route = "album/late-rel",
                predictedToRect = Rect(0f, 120f, 720f, 840f),
                physicalMotion = true,
                sourceNavEntryId = "shelf-1",
                releaseId = "late-rel",
            ),
        )
        entryId = "album-late-1"
        currentData = makeAlbumData("late-rel", lateAsset)
        // Wait for timeout degradation inside DOM and bounded overlay clear.
        compose.waitUntil(8_000) {
            coordinator.activeMorph == null &&
                evalSync(web, "Boolean(window.__heroDegraded && document.querySelector('.hero-timeout-overlay') && document.querySelector('.hero-preview')?.naturalWidth > 0)") == "true"
        }
        // Now resolve slow decode -> timeout overlay disappears and hero completes in-place with NO morph replay.
        evalSync(web, "(() => { window.__slowDecodeResolvers.splice(0).forEach(r => r()); if (window.__origDecode) HTMLImageElement.prototype.decode = window.__origDecode; return true; })()")
        compose.waitUntil(8_000) {
            coordinator.activeMorph == null &&
                coordinator.destinationHeroRect("album:late-rel") != null &&
                evalSync(web, "Boolean(!window.__heroDegraded && !document.querySelector('.hero-timeout-overlay, .hero-preview'))") == "true"
        }
        assertNull("Late image arrival must not replay cover morph", coordinator.activeMorph)

        // Part D: Dropped VisualStateCallback safety timeout clears overlay deterministically when confirm never fires.
        showDetail = false
        assertTrue(
            coordinator.startEnter(
                key = "release:fast-rel",
                route = "album/dropped-confirm",
                predictedToRect = Rect(0f, 120f, 720f, 840f),
                physicalMotion = true,
                sourceNavEntryId = "shelf-1",
                releaseId = "dropped-confirm",
            ),
        )
        val droppedGen = coordinator.bindDestinationEntry("album/dropped-confirm", "release:fast-rel", "album-dropped-1")!!
        coordinator.registerWebInstance("album-dropped-1", 77L)
        coordinator.reportDestinationHero(
            route = "album/dropped-confirm",
            key = "release:fast-rel",
            heroRectInRoot = Rect(0f, 120f, 720f, 840f),
            firstPaint = true,
            navEntryId = "album-dropped-1",
            identity = "album:dropped-confirm",
            renderToken = 1L,
            decoded = true,
            drawn = true,
            webInstanceId = 77L,
            loadAttempt = 1,
            expectedGeneration = droppedGen,
        )
        // CoverTransitionOverlay animates (~270ms) and sets revealDispatched=true without any host calling markRevealConfirmed;
        // revealConfirmTimeoutMs (150ms) clears the overlay instead of leaving it stuck.
        compose.waitUntil(3_000) { coordinator.activeMorph == null }
        assertTrue(coordinator.lastOverlayClearEvent.orEmpty().contains("confirmTimeout=true"))

        // The entry timeout also runs while there is no target layout at all.
        assertTrue(coordinator.startEnter("release:fast-rel", "album/no-target", null, true))
        checkNotNull(coordinator.bindDestinationEntry("album/no-target", "release:fast-rel", "album-no-target"))
        compose.waitUntil(3_000) { coordinator.activeMorph == null }
        assertTrue(coordinator.lastOverlayClearEvent.orEmpty().contains("waitTimeout=true"))
        assertTrue(coordinator.needsCoverRecovery("album-no-target"))

        // Part E: Early back while still waiting cancels morph cleanly (does not return a fake cover morph).
        assertTrue(
            coordinator.startEnter(
                key = "release:late-rel",
                route = "album/early-back",
                predictedToRect = Rect(0f, 120f, 720f, 840f),
                physicalMotion = true,
                sourceNavEntryId = "shelf-1",
                releaseId = "early-back",
            ),
        )
        val earlyReturn = coordinator.beginReturnSession(
            key = "release:late-rel",
            route = "album/early-back",
            identity = "album:early-back",
            physicalMotion = true,
            sourceNavEntryId = "album-early",
            targetNavEntryId = "shelf-1",
            interactive = false,
        )
        assertNull(earlyReturn)
        assertNull(coordinator.activeMorph)
    }

    @Test
    fun nativeWorld_p2_2_programmaticCameraTargetAndSummaryMotion() {
        val ctx = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val scene = Scene("scene", "artist", "艺人", "测试作品", imageStatus = "approved", verificationStatus = "verified")
        val catalog = Catalog(
            listOf(
                Place("p1", "地点甲", "巴黎", "法国", 48.8566, 2.3522, scenes = listOf(scene)),
                Place("p2", "地点乙", "维也纳", "奥地利", 48.2082, 16.3738, scenes = listOf(scene.copy(id = "scene2"))),
            )
        )
        var motion by mutableStateOf(true)
        lateinit var browse: RoomBrowseState

        compose.setContent {
            val currentCtx = LocalContext.current
            val rememberedBrowse = remember(currentCtx) { RoomBrowseState(currentCtx) }
            browse = rememberedBrowse
            androidx.compose.runtime.DisposableEffect(rememberedBrowse) {
                onDispose { rememberedBrowse.worldController?.destroy() }
            }
            PilgrimageTheme {
                CompositionLocalProvider(LocalPhysicalMotion provides motion) {
                    SwissWorldWall(
                        catalog = catalog,
                        browse = rememberedBrowse,
                        favorites = emptySet(),
                        favoriteBusy = false,
                        onPlace = {},
                        onFavorite = {},
                        onAdd = {},
                        onSearch = {},
                        onBack = {},
                        visits = emptySet(),
                        active = true,
                    )
                }
            }
        }

        compose.waitUntil(15_000) {
            compose.runOnUiThread {
                val diag = browse.worldController?.diagnostics()
                diag != null && diag.ready && diag.hasVisual
            }
        }
        val ctrl = checkNotNull(browse.worldController)

        // 1) Programmatic camera target + Reduce Motion toggled mid-flight settles immediately at p1.
        compose.runOnUiThread { browse.focusWorld("p1", WorldFocusKind.Street) }
        compose.waitForIdle()
        compose.runOnUiThread { motion = false }
        compose.waitUntil(5_000) {
            compose.runOnUiThread {
                val d = ctrl.diagnostics()
                !ctrl.hasPendingCameraTarget() &&
                    kotlin.math.abs(d.latitude - 48.8566) < 0.05 &&
                    kotlin.math.abs(d.longitude - 2.3522) < 0.05 &&
                    d.zoom >= 14.5
            }
        }

        // 2) Switch Place p1 -> p2 mid-flight, then toggle Reduce Motion -> lands at p2 (NOT p1).
        compose.runOnUiThread {
            motion = true
            ctrl.jumpTo(0.0, 0.0, 1.0)
        }
        compose.waitForIdle()
        compose.runOnUiThread {
            browse.focusWorld("p1", WorldFocusKind.Street)
            browse.focusWorld("p2", WorldFocusKind.Street)
            motion = false
        }
        compose.waitUntil(5_000) {
            compose.runOnUiThread {
                val d = ctrl.diagnostics()
                !ctrl.hasPendingCameraTarget() &&
                    kotlin.math.abs(d.latitude - 48.2082) < 0.05 &&
                    kotlin.math.abs(d.longitude - 16.3738) < 0.05 &&
                    d.zoom >= 14.5
            }
        }

        // 3) Summary open, swipe dismiss, and immediate reopen/swap onto p1 keeps summary open on p1.
        compose.runOnUiThread { motion = true }
        compose.onNodeWithTag("world-place-summary").assertIsDisplayed()
        compose.onNodeWithTag("world-summary-handle").performTouchInput { swipeDown() }
        compose.runOnUiThread { browse.focusWorld("p1", WorldFocusKind.List) }
        compose.onNodeWithTag("world-place-summary").assertIsDisplayed()
        compose.onNodeWithTag("world-summary-title").assertIsDisplayed()
    }

    @ExtendedDeviceTest
    @Test
    fun detailViewer_p2_3_instanceLifecycle_doubleBack_cancel_reduceMotion_sources() {
        val assetUrl = "file:///android_asset/design/swiss-details/media/full-samaritaine-jay-1.jpg"
        val actions = CopyOnWriteArrayList<String>()
        var backCalls by mutableIntStateOf(0)
        var motion by mutableStateOf(true)
        var hostView: View? = null
        lateinit var dispatcher: OnBackPressedDispatcher

        val placeRow = JSONObject()
            .put("id", "pl-p23")
            .put("name", "莎玛丽丹")
            .put("city", "巴黎")
            .put("country", "法国")
            .put("image", assetUrl)
            .put("credit", "测试实景来源")
            .put("reason", "代表场景实景")
        val releaseRow = JSONObject()
            .put("id", "rel-p23")
            .put("title", "图鉴测试专辑")
            .put("artist", "测试音乐人")
            .put("year", "2026")
            .put("image", assetUrl)
            .put("credit", "测试版权标注")
            .put("imageWidth", 96)
            .put("imageHeight", 96)
            .put(
                "works",
                JSONArray()
                    .put(JSONObject().put("title", "曲目一").put("places", JSONArray().put("pl-p23")))
                    .put(JSONObject().put("title", "曲目二").put("places", JSONArray()))
                    .put(JSONObject().put("title", "曲目三").put("places", JSONArray()))
                    .put(JSONObject().put("title", "曲目四").put("places", JSONArray()))
                    .put(JSONObject().put("title", "曲目五").put("places", JSONArray()))
                    .put(JSONObject().put("title", "曲目六").put("places", JSONArray())),
            )
            .put("places", JSONArray().put("pl-p23"))
        val detailData = swissDetailData("album", "rel-p23")
            .put("releases", JSONArray().put(releaseRow))
            .put("places", JSONArray().put(placeRow))
            .put("fallbacks", JSONObject())

        compose.setContent {
            dispatcher = requireNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            val haptics = rememberAppHaptics()
            PilgrimageTheme {
                CompositionLocalProvider(
                    LocalPhysicalMotion provides motion,
                    LocalAppHaptics provides haptics,
                    LocalRouteActive provides true,
                    LocalRouteIsCurrent provides { true },
                    LocalRouteEntryId provides "detail-p23",
                ) {
                    RoomWall(Modifier.fillMaxSize()) {
                        hostView = LocalView.current
                        AppPredictiveBackHandler(enabled = true) { backCalls++ }
                        SwissWebDetail(detailData) { action, _ ->
                            actions.add(action)
                            if (action == "back") backCalls++
                        }
                    }
                }
            }
        }

        compose.waitUntil(8_000) {
            var w: WebView? = null
            compose.runOnUiThread { w = findFirstWebView(hostView) }
            w != null && evalSync(
                w!!,
                "Boolean(document.querySelector('.hero-image') && document.querySelector('.hero-image').complete && document.querySelector('.hero-image').naturalWidth > 0 && document.querySelector('button[data-action=\"image\"]') && !document.querySelector('button[data-action=\"image\"]').disabled)",
            ) == "true"
        }
        val web = checkNotNull(run {
            var w: WebView? = null
            compose.runOnUiThread { w = findFirstWebView(hostView) }
            w
        })

        // Scroll archive body cleanly and verify scroll position preserved across viewer double-back.
        evalSync(web, "(() => { const s = document.querySelector('.scroll-content'); s.scrollTop = 180; return s.scrollTop; })()")
        val initialScroll = evalSync(web, "document.querySelector('.scroll-content').scrollTop").toDouble()
        assertTrue("Expected scrollable archive height ($initialScroll)", initialScroll > 0.0)

        // 1) Open image viewer -> double-Back within closing animation must stay inside archive; 3rd Back after removal exits archive once.
        evalSync(web, "document.querySelector('.hero-image').closest('button[data-action=\"image\"]').click(); true")
        compose.waitUntil(5_000) {
            evalSync(web, "document.querySelectorAll('.image-shade').length") == "1"
        }
        SystemClock.sleep(80)
        compose.waitForIdle()
        compose.runOnUiThread {
            dispatcher.onBackPressed()
            dispatcher.onBackPressed()
        }
        compose.waitForIdle()
        assertEquals("Double back while image viewer closing must NOT pop underlying archive", 0, backCalls)
        compose.waitUntil(5_000) {
            evalSync(web, "document.querySelectorAll('.image-shade').length") == "0"
        }
        SystemClock.sleep(80)
        compose.waitForIdle()
        assertEquals(initialScroll, evalSync(web, "document.querySelector('.scroll-content').scrollTop").toDouble(), 2.0)

        // Now after viewer DOM removal, Back exits archive cleanly.
        compose.runOnUiThread { dispatcher.onBackPressed() }
        compose.waitUntil(3_000) { backCalls == 1 }

        // 2) Predictive Back cancel keeps image viewer open; then Reduce Motion during close clears viewer immediately.
        evalSync(web, "document.querySelector('.hero-image').closest('button[data-action=\"image\"]').click(); true")
        compose.waitUntil(5_000) {
            evalSync(web, "document.querySelectorAll('.image-shade').length") == "1"
        }
        SystemClock.sleep(80)
        compose.waitForIdle()
        compose.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 300f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(80f, 300f, 0.55f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackCancelled()
        }
        compose.waitForIdle()
        assertEquals("1", evalSync(web, "document.querySelectorAll('.image-shade').length"))
        assertEquals(1, backCalls)

        // Close and immediately toggle Reduce Motion ON -> removes DOM synchronously without orphan nodes.
        compose.runOnUiThread {
            dispatcher.onBackPressed()
            motion = false
        }
        compose.waitUntil(3_000) {
            evalSync(web, "document.querySelectorAll('.image-shade').length") == "0"
        }
        assertEquals(1, backCalls)

        evalSync(web, "document.querySelector('.hero-image').closest('button[data-action=\"image\"]').click(); true")
        compose.waitUntil(3_000) { evalSync(web, "document.querySelectorAll('.image-shade').length") == "1" }
        assertEquals("false", evalSync(web, "window.__previewNativeImageBack(.6)"))
        compose.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 300f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(80f, 300f, 0.6f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        assertEquals("true", evalSync(web, "(() => { const s = document.querySelector('.image-shade'); return s.style.opacity === '' && s.querySelector('img').style.transform === ''; })()"))
        compose.runOnUiThread { dispatcher.dispatchOnBackCancelled(); dispatcher.onBackPressed() }
        compose.waitUntil(3_000) { evalSync(web, "document.querySelectorAll('.image-shade').length") == "0" }
        assertEquals(1, backCalls)

        // 3) Rapid close + reopen same image ignores old viewer's completion callback.
        motion = true
        evalSync(web, "document.querySelector('.hero-image').closest('button[data-action=\"image\"]').click(); true")
        compose.waitUntil(3_000) { evalSync(web, "document.querySelectorAll('.image-shade').length") == "1" }
        evalSync(
            web,
            """
            (() => {
              window.closeNativeImage();
              document.querySelector('.hero-image').closest('button[data-action="image"]').click();
              return true;
            })()
            """.trimIndent(),
        )
        SystemClock.sleep(300)
        assertEquals("1", evalSync(web, "document.querySelectorAll('.image-shade').length"))

        // 4) Clicking '资料来源 ↗' inside .image-shade closes & removes viewer DOM BEFORE posting 'sources' and keeps scrollTop.
        actions.clear()
        evalSync(web, "document.querySelector('.image-shade a.source-link').click(); true")
        compose.waitUntil(5_000) {
            actions.contains("sources") && evalSync(web, "document.querySelectorAll('.image-shade').length") == "0"
        }
        assertEquals(initialScroll, evalSync(web, "document.querySelector('.scroll-content').scrollTop").toDouble(), 2.0)
    }

    @Test
    fun performanceScreen_reduceMotionParity_and_staticLiveScore_withRollingSettlement() {
        val ctx = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val uid = UUID.randomUUID().toString()
        val events = listOf(
            MusicEvent(0, 1_500_000L, 250_000L, 69, 100, 3),
            MusicEvent(1, 3_000_000L, 900_000L, 72, 100, 3),
            MusicEvent(2, 5_300_000L, 250_000L, 76, 100, 3),
        )
        val targets = listOf(
            Target(0, listOf(0), 0, 1_500_000L, 250_000L, false),
            Target(1, listOf(1), 1, 3_000_000L, 900_000L, true),
            Target(2, listOf(2), 2, 5_300_000L, 250_000L, false),
        )
        val fixtureSong = SongPackage(
            id = "parity-song-$uid",
            version = 1,
            title = "动效同条件验证曲目",
            artist = "测试演奏家",
            referenceEdition = "标准版",
            durationUs = 7_500_000L,
            events = events,
            easy = emptyList(),
            standard = targets,
            sections = listOf(
                SongSection("前奏", 0L, 2_300_000L, "scene-1", 120.0),
                SongSection("主部", 2_300_000L, 7_500_000L, "scene-2", 120.0),
            ),
            placeIds = emptyList(),
            sources = JSONObject(),
        )

        data class RunSummary(val score: Int, val bestCombo: Int, val judgments: List<Judgment>)

        var motion by mutableStateOf(true)
        var activePassKey by mutableIntStateOf(0)
        var activeController by mutableStateOf<PerformanceController?>(null)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val haptics = rememberAppHaptics()
            PilgrimageTheme {
                CompositionLocalProvider(
                    LocalPhysicalMotion provides motion,
                    LocalAppHaptics provides haptics,
                ) {
                    RoomWall(Modifier.fillMaxSize()) {
                        activeController?.let { controller ->
                            key(activePassKey) {
                                PerformanceScreen(controller, onPrepare = {})
                            }
                        }
                    }
                }
            }
        }

        fun runPass(passIndex: Int, toggleReduceMotionMidSong: Boolean): RunSummary {
            val owner = "parity-owner-$uid-$passIndex"
            val store = ViewModelStore()
            val controller = ViewModelProvider(
                store,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                        PerformanceController(ctx.applicationContext as Application, fixtureSong, owner, PlayOptions()) as T
                },
            )[PerformanceController::class.java]

            motion = true
            activeController = controller
            activePassKey = passIndex
            try {
                fun awaitState(label: String, cond: () -> Boolean) {
                    val deadline = SystemClock.elapsedRealtime() + 15_000L
                    while (!cond() && SystemClock.elapsedRealtime() < deadline) {
                        compose.mainClock.advanceTimeByFrame()
                        Thread.sleep(10)
                    }
                    val ui = controller.ui.value
                    assertTrue(
                        "Timed out awaiting [$label]: state=${ui.state}, countIn=${ui.countIn}, posUs=${ui.positionUs}, msg=${ui.message}",
                        cond(),
                    )
                    compose.mainClock.advanceTimeBy(32)
                }

                awaitState("READY") { controller.ui.value.state == PlayState.READY }
                compose.onNodeWithTag("performance-start").performClick()

                awaitState("STARTING") { controller.ui.value.state == PlayState.STARTING }
                compose.onNodeWithTag("performance-countdown").assertIsDisplayed()
                if (toggleReduceMotionMidSong) motion = false

                awaitState("note0") { controller.ui.value.countIn == 0 && controller.ui.value.positionUs >= 1_470_000L }
                compose.onNodeWithTag("performance-score").assertIsDisplayed()
                compose.onNodeWithTag("performance-lane-0").performTouchInput { click(center) }
                if (toggleReduceMotionMidSong) motion = true

                awaitState("note1_down") { controller.ui.value.positionUs >= 2_970_000L }
                compose.onNodeWithTag("performance-lane-1").performTouchInput { down(center) }
                awaitState("note1_up") { controller.ui.value.positionUs >= 3_890_000L }
                compose.onNodeWithTag("performance-lane-1").performTouchInput { up() }

                awaitState("before_pause") { controller.ui.value.positionUs >= 4_150_000L }
                compose.onNodeWithTag("performance-pause").performSemanticsAction(SemanticsActions.OnClick)
                awaitState("PAUSED") { controller.ui.value.state == PlayState.PAUSED }
                if (toggleReduceMotionMidSong) motion = false
                compose.onNodeWithTag("performance-start").performClick()

                awaitState("note2") { controller.ui.value.countIn == 0 && controller.ui.value.positionUs >= 5_270_000L }
                compose.onNodeWithTag("performance-lane-2").performTouchInput { click(center) }

                awaitState("FINISHED") { controller.ui.value.state == PlayState.FINISHED }
                compose.onNodeWithTag("performance-result").assertIsDisplayed()
                compose.onNodeWithTag("performance-result-score").assertIsDisplayed()
                compose.onNodeWithTag("performance-again").assertIsDisplayed()
                return RunSummary(
                    score = controller.ui.value.score,
                    bestCombo = controller.ui.value.bestCombo,
                    judgments = controller.ui.value.judgments.toList(),
                )
            } finally {
                compose.runOnUiThread {
                    activeController = null
                    store.clear()
                }
                runCatching {
                    runBlocking(Dispatchers.IO) {
                        LibraryDatabase.open(ctx).openHelper.writableDatabase.apply {
                            execSQL("delete from performance_checkpoints where owner=?", arrayOf(owner))
                            execSQL("delete from performance_runs where owner=?", arrayOf(owner))
                            execSQL("delete from performance_results where owner=?", arrayOf(owner))
                        }
                    }
                }
            }
        }

        try {
            val fullMotionRun = runPass(passIndex = 1, toggleReduceMotionMidSong = false)
            val toggledMotionRun = runPass(passIndex = 2, toggleReduceMotionMidSong = true)
            assertEquals(3, fullMotionRun.judgments.size)
            assertEquals(fullMotionRun.judgments.size, toggledMotionRun.judgments.size)
            assertTrue(
                "Full motion must hit every target cleanly: ${fullMotionRun.judgments}",
                fullMotionRun.judgments.all { it == Judgment.PERFECT || it == Judgment.GOOD },
            )
            assertTrue(
                "Reduce Motion toggle must hit every target identically without skips/misses: ${toggledMotionRun.judgments}",
                toggledMotionRun.judgments.all { it == Judgment.PERFECT || it == Judgment.GOOD },
            )
            assertEquals(fullMotionRun.bestCombo, toggledMotionRun.bestCombo)
            assertEquals(3, fullMotionRun.bestCombo)
            assertTrue(fullMotionRun.score > 0 && toggledMotionRun.score > 0)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }
}
