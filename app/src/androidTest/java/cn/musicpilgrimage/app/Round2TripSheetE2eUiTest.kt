package cn.musicpilgrimage.app

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.musicpilgrimage.app.data.MusicTrip
import cn.musicpilgrimage.app.data.MusicTripCity
import cn.musicpilgrimage.app.data.MusicTripStore
import cn.musicpilgrimage.app.performance.LibraryDatabase
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.io.File
import android.graphics.Bitmap
import java.util.UUID

/**
 * Full E2E verification on debug MusicSpacePreviewActivity with allowGuestTrips=true:
 * covers World & Place (加入行程) -> scrollable trip list -> IME input ->
 * nested scroll + mid-dismiss upward takeover + Mid/High detents -> save failure retry -> save success + confirmation.
 */
@RunWith(AndroidJUnit4::class)
class Round2TripSheetE2eUiTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val tripPrefs by lazy { context.getSharedPreferences("music-space-trips", Context.MODE_PRIVATE) }
    private lateinit var ownerKey: String
    private var previousTripsJson: String? = null

    @Before
    fun seedGuestTrips() {
        ownerKey = LibraryDatabase.guestOwner(context)
        previousTripsJson = tripPrefs.getString("$ownerKey:trips", null)
        val store = MusicTripStore(tripPrefs)
        val today = LocalDate.now()
        val city = MusicTripCity("巴黎", "法国")
        val seeded = buildList {
            // Multiple active trips so the trip choice list inside AppBottomSheet can scroll natively.
            add(MusicTrip("trip-fail-${UUID.randomUUID()}", "A-待校验行程", today, today.plusDays(4), listOf(city)))
            add(MusicTrip("trip-ok-${UUID.randomUUID()}", "B-巴黎音乐巡礼", today, today.plusDays(6), listOf(city)))
            for (i in 1..6) {
                add(MusicTrip("trip-zfiller-$i-${UUID.randomUUID()}", "C-巡礼线路 $i", today, today.plusDays(3), listOf(city)))
            }
        }
        store.write(ownerKey, seeded)
    }

    @After
    fun restoreGuestTrips() {
        val store = MusicTripStore(tripPrefs)
        val prev = previousTripsJson
        if (prev == null) {
            store.clear(ownerKey)
        } else {
            tripPrefs.edit().putString("$ownerKey:trips", prev).commit()
        }
    }

    private fun chooseWorldPlace() {
        compose.onNodeWithTag("world-place-list").performClick()
        compose.onNodeWithTag("world-place-query").performTextInput("敦煌")
        compose.onNodeWithTag("world-place-option-dunhuang-city-reference").performClick()
        compose.onNodeWithTag("world-place-summary").assertIsDisplayed()
    }

    @Test
    fun compactEmptySheetAndSingleHongKongResult() {
        MusicTripStore(tripPrefs).write(ownerKey, emptyList())
        val intent=Intent(context,MusicSpacePreviewActivity::class.java).putExtra("allowGuestTrips",true)
        ActivityScenario.launch<MusicSpacePreviewActivity>(intent).use { scenario ->
            lateinit var activity: MusicSpacePreviewActivity
            scenario.onActivity{activity=it}
            compose.waitUntil(10_000){compose.onAllNodesWithTag("wall-tab-1").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("wall-tab-1").performClick()
            chooseWorldPlace()
            compose.onNodeWithTag("world-summary-add").performClick()
            compose.waitUntil(8_000){
                compose.onAllNodes(hasText("新建行程后，为这个地点选择日期。")).fetchSemanticsNodes().isNotEmpty()
            }
            val sheetHeight=compose.onNodeWithTag("app-bottom-sheet-surface").fetchSemanticsNode().size.height
            val screenHeight=compose.runOnUiThread{activity.window.decorView.height}
            assertTrue("The empty sheet should stay compact ($sheetHeight/$screenHeight)",sheetHeight<screenHeight*0.4f)
            File(context.getExternalFilesDir(null),"motion-acceptance-empty-trip.png").outputStream().use {
                instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it)
            }
            compose.onNodeWithText("新建行程").performClick()
            compose.onNodeWithText("下一步").performClick()
            compose.onNodeWithTag("trip-destination-search").performTextInput("香港")
            compose.waitUntil(5_000){
                compose.onAllNodes(hasText("中国香港")).fetchSemanticsNodes().size==1
            }
            assertTrue(compose.onAllNodes(hasText("香港 · 中国香港")).fetchSemanticsNodes().isEmpty())
            assertTrue(compose.onAllNodes(hasText("中国香港 · 国家或地区")).fetchSemanticsNodes().isEmpty())
        }
    }

    @Test
    fun worldAndPlaceAddToTrip_gestures_ime_failureRetry_andSuccessToast() {
        val intent = Intent(context, MusicSpacePreviewActivity::class.java)
            .putExtra("allowGuestTrips", true)

        ActivityScenario.launch<MusicSpacePreviewActivity>(intent).use {
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasTestTag("wall-tab-1")).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("wall-tab-1").performClick()

            chooseWorldPlace()
            compose.onNodeWithTag("world-summary-add").performClick()

            compose.waitUntil(8_000) {
                compose.onAllNodesWithTag("app-bottom-sheet-surface").fetchSemanticsNodes().isNotEmpty()
            }
            val sheetSurface = compose.onNodeWithTag("app-bottom-sheet-surface")
            val midHeight = sheetSurface.fetchSemanticsNode().size.height

            // Test Mid -> High detent pull & mid-dismiss upward takeover on the real sheet.
            compose.mainClock.autoAdvance = false
            try {
                sheetSurface.performTouchInput {
                    down(Offset(centerX, 40f))
                    repeat(8) {
                        advanceEventTime(18)
                        moveBy(Offset(0f, 34f))
                    }
                    up()
                }
                compose.mainClock.advanceTimeBy(32)
                compose.waitForIdle()
                sheetSurface.performTouchInput {
                    down(Offset(centerX, (height - 120f).coerceAtLeast(centerY)))
                    repeat(10) {
                        advanceEventTime(16)
                        moveBy(Offset(0f, -75f))
                    }
                    up()
                }
            } finally {
                compose.mainClock.autoAdvance = true
            }
            compose.waitUntil(3_000) {
                sheetSurface.fetchSemanticsNode().size.height > midHeight + 80
            }

            // Test failure -> retry -> success flow:
            // Pick "A-待校验行程", remove it underneath from backing store before picking date -> triggers real save error & stays open.
            compose.onNodeWithTag("add-trip-choices").performScrollToNode(hasText("A-待校验行程"))
            compose.onNodeWithText("A-待校验行程").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("trip-date-1").fetchSemanticsNodes().isNotEmpty()
            }
            val store = MusicTripStore(tripPrefs)
            store.write(ownerKey, store.read(ownerKey).filterNot { it.name == "A-待校验行程" })
            compose.waitForIdle()
            compose.onNodeWithTag("trip-date-1").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasText("该行程已不存在")).fetchSemanticsNodes().isNotEmpty()
            }
            sheetSurface.assertIsDisplayed()

            // Return to trip picker, also open "新建行程" to verify real text field & IME input inside the sheet, then close create dialog.
            compose.onNode(hasContentDescription("返回选择行程")).performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasText("新建行程")).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("新建行程").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasText("下一步")).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("下一步").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("trip-name-input").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("trip-name-input").performTextInput("巴黎夜曲行")
            compose.onNodeWithText("取消").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasText("加入行程")).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(hasContentDescription("关闭加入行程")).performClick()

            // Wait for World again, re-open AddToTrip, pick valid active trip & retry -> saves cleanly, dismisses sheet, shows "已加入行程", stays on World tab.
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("app-bottom-sheet-surface").fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithTag("world-summary-add").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasText("B-巴黎音乐巡礼")).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("B-巴黎音乐巡礼").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("trip-date-1").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("trip-date-1").performClick()
            compose.waitUntil(8_000) {
                compose.onAllNodesWithTag("app-bottom-sheet-surface").fetchSemanticsNodes().isEmpty() &&
                    compose.onAllNodes(hasText("已加入行程")).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("world-place-summary").assertIsDisplayed()

            compose.onNodeWithTag("world-summary-detail").performClick()
            compose.onNodeWithTag("native-place-archive").assertIsDisplayed()
            compose.onNodeWithTag("place-archive-add-trip").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasText("B-巴黎音乐巡礼")).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("B-巴黎音乐巡礼").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("trip-date-2").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("trip-date-2").performClick()
            compose.waitUntil(8_000) {
                compose.onAllNodesWithTag("app-bottom-sheet-surface").fetchSemanticsNodes().isEmpty() &&
                    compose.onAllNodes(hasText("已加入行程")).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("room-place-detail").assertIsDisplayed()
            assertTrue("Expected two scheduled stops in local guest trip store", MusicTripStore(tripPrefs).read(ownerKey).any { it.stops.size >= 2 })
        }
    }
}
