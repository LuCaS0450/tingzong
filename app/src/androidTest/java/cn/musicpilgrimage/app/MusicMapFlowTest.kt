package cn.musicpilgrimage.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import org.junit.Rule
import org.junit.Test

@ExtendedDeviceTest
class MusicMapFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MusicSpacePreviewActivity>()

    @Test fun placeListOpensArchiveAndReturnsToTheSameSelection() {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("wall-tab-1").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("wall-tab-1").performClick()
        compose.onNodeWithTag("world-place-list").performClick()
        compose.onNodeWithTag("world-place-query").performTextInput("莎玛丽丹")
        compose.onNodeWithTag("world-place-option-samaritaine").performClick()
        compose.onNodeWithTag("world-summary-detail").performClick()
        compose.onNodeWithTag("native-place-archive").assertIsDisplayed()
        Espresso.pressBack()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("world-place-summary").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("world-summary-detail").assertIsDisplayed()
        compose.onNodeWithTag("world-summary-close").performClick()
        compose.onNodeWithTag("world-place-list").assertIsDisplayed()
    }
}
