package cn.musicpilgrimage.app

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class AuthLinkTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun invalidColdStartEmailLinkShowsRecoveryActions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("musicpilgrimage://auth/callback?error_description=expired"))
        ActivityScenario.launch<MainActivity>(intent).use {
            compose.waitUntil(10000) { compose.onAllNodesWithText("验证链接已失效，请重新发送").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("验证链接已失效，请重新发送").assertExists()
            compose.onNodeWithText("重发验证邮件").performScrollTo().assertIsDisplayed()
        }
    }
}
