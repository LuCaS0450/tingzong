package cn.musicpilgrimage.app

import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.musicpilgrimage.app.performance.PerformanceCatalog
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BauhausPlayWallUiTest {
    @get:Rule val compose = createAndroidComposeRule<MusicSpacePreviewActivity>()

    private fun awaitManualClock(timeoutMs: Long = 12_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition() && SystemClock.elapsedRealtime() < deadline) {
            compose.mainClock.advanceTimeByFrame()
            Thread.sleep(16)
        }
        assertTrue("Condition timed out while advancing Compose clock", condition())
    }

    @Test
    fun bauhausPianoWall_scrollsAllTracks_clipsInsideKeybed_andInterceptsBlackKeys() {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("three-walls-tabs", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("wall-tab-2", useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("performance-list", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Old generic header/status annotations must not appear on the Bauhaus piano wall.
        assertTrue(compose.onAllNodesWithText("可演奏曲目").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("15 TRACKS").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("离线就绪", substring = true).fetchSemanticsNodes().isEmpty())

        val keybedBounds = compose.onNodeWithTag("performance-list", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot

        PerformanceCatalog.ORDERED_SONG_IDS.forEach { id ->
            compose.onNodeWithTag("performance-list", useUnmergedTree = true).performScrollToKey(id)
            val node = compose.onNodeWithTag("performance-song-$id", useUnmergedTree = true)
            node.assertIsDisplayed()
            val itemBounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue(
                "Key $id escaped keybed top (${itemBounds.top} < ${keybedBounds.top})",
                itemBounds.top >= keybedBounds.top - 1f,
            )
            assertTrue(
                "Key $id escaped keybed bottom (${itemBounds.bottom} > ${keybedBounds.bottom})",
                itemBounds.bottom <= keybedBounds.bottom + 1f,
            )
        }

        // Return to top and verify tapping a right-hand black key does not launch the song behind it.
        compose.onNodeWithTag("performance-list", useUnmergedTree = true).performScrollToKey("greatest-works")
        val firstKey = compose.onNodeWithTag("performance-song-greatest-works", useUnmergedTree = true)
        val firstSize = firstKey.fetchSemanticsNode().size
        firstKey.performTouchInput {
            click(Offset(firstSize.width * 0.85f, firstSize.height * 0.96f))
        }
        compose.waitForIdle()
        compose.onNodeWithTag("performance-list", useUnmergedTree = true).assertIsDisplayed()

        // Tapping the white key center enters the song (manual clock avoids live stage loop idling block).
        compose.mainClock.autoAdvance = false
        try {
            firstKey.performTouchInput { click(center) }
            awaitManualClock {
                compose.onAllNodesWithTag("performance-list", useUnmergedTree = true)
                    .fetchSemanticsNodes().isEmpty()
            }
            compose.runOnUiThread {
                compose.activity.onBackPressedDispatcher.onBackPressed()
            }
            awaitManualClock {
                compose.onAllNodesWithTag("performance-list", useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.onNodeWithTag("performance-list", useUnmergedTree = true).assertIsDisplayed()
    }
}
