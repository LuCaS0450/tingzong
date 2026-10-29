package cn.musicpilgrimage.app

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import java.io.File

class MusicSpaceFlowTest {
    @get:Rule val compose=createAndroidComposeRule<MusicSpacePreviewActivity>()
    private fun capture(name:String) {
        compose.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val directory=File(instrumentation.targetContext.getExternalFilesDir(null),"music-space-stage1").apply{mkdirs()}
        instrumentation.uiAutomation.takeScreenshot().let {bitmap->
            File(directory,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
    }
    @Test fun roomCatalogueAlbumSearchAndSourceReturn() {
        compose.waitUntil(10000){compose.onAllNodesWithTag("room-home").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("room-home").assertIsDisplayed()
        capture("home")
        compose.onNodeWithTag("room-open-catalogue").performClick()
        compose.onNodeWithTag("room-catalogue").assertIsDisplayed()
        capture("catalogue")
        compose.onNodeWithTag("room-release-jay|reused-mont-saint-michel-jay-1").performClick()
        compose.onNodeWithTag("room-album").assertIsDisplayed()
        compose.onNodeWithTag("room-work-jay|最伟大的作品").performScrollTo().performClick()
        compose.onNodeWithTag("room-song").assertIsDisplayed()
        capture("song")
        compose.onNodeWithTag("room-place-mont-saint-michel").performScrollTo().performClick()
        compose.onNodeWithTag("room-back").performClick()
        compose.onNodeWithTag("room-song").assertIsDisplayed()
        compose.onNodeWithTag("room-space-button").performClick()
        compose.onNodeWithTag("room-space-menu").assertIsDisplayed()
        capture("space")
        compose.onNodeWithTag("room-module-me").performClick()
        compose.onNodeWithText("我的",useUnmergedTree=true).assertExists()
        compose.onNodeWithContentDescription("打开空间导航").performClick()
        compose.onNodeWithTag("room-module-catalogue").performClick()
        compose.onNodeWithTag("room-song").assertIsDisplayed()
        compose.onNodeWithTag("room-back").performClick()
        compose.onNodeWithTag("room-back").performClick()
        compose.onNodeWithTag("room-search-button").performClick()
        compose.onNodeWithTag("room-search-input").performTextInput("周杰伦")
        compose.onNodeWithTag("room-work-jay|最伟大的作品").performScrollTo().performClick()
        compose.onNodeWithTag("room-back").performClick()
        compose.onNodeWithTag("room-search-input").assertTextContains("周杰伦")
    }
}
