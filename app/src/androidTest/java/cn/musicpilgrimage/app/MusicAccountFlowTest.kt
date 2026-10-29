package cn.musicpilgrimage.app

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.platform.app.InstrumentationRegistry
import cn.musicpilgrimage.app.design.AppPreferences
import cn.musicpilgrimage.app.design.AppPreferencesState
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assume.assumeTrue
import java.io.File

internal fun captureAccountScreen(name:String,compose:ComposeTestRule) {
    compose.waitForIdle()
    val instrumentation=InstrumentationRegistry.getInstrumentation()
    instrumentation.waitForIdleSync()
    // Let the platform and local-art crossfade finish, rather than capturing a transitional frame.
    android.os.SystemClock.sleep(350)
    val folder=File(instrumentation.targetContext.getExternalFilesDir(null),"music-space-stage3").apply{mkdirs()}
    instrumentation.uiAutomation.takeScreenshot().let{bitmap->File(folder,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
}

class MusicFirstLoginTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun productionEntryRequiresAccountAndKeepsValidationInItsFields(){
        val services=(compose.activity.application as PilgrimageApplication).services
        runBlocking{services.ready()};assumeTrue(services.userId.value==null)
        compose.waitUntil(10000){compose.onAllNodesWithTag("room-email-login").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("room-home").assertDoesNotExist()
        captureAccountScreen("login",compose)
        compose.onNodeWithTag("account-email").performTextInput("wrong")
        compose.onNodeWithTag("account-send-code").performClick()
        compose.onNodeWithText("请输入完整的邮箱地址").assertIsDisplayed()
        closeSoftKeyboard()
        captureAccountScreen("login-email-error",compose)
        compose.onNodeWithTag("account-email").performTextReplacement("layout@example.invalid")
        compose.onNodeWithTag("account-sign-in").performClick()
        compose.onNodeWithText("请输入邮件中的完整验证码").assertIsDisplayed()
        closeSoftKeyboard()
        compose.onNodeWithTag("account-code-help").performScrollTo().performClick()
        compose.onNodeWithText("收不到验证码？").assertIsDisplayed()
        compose.onNodeWithTag("account-email").assertTextContains("layout@example.invalid")
        captureAccountScreen("code-help",compose)
        compose.onNodeWithContentDescription("返回登录").performClick()
        compose.onNodeWithTag("account-code").assertExists()
        compose.onNodeWithTag("account-registration").performScrollTo().performClick()
        compose.onNodeWithText("注册并进入").assertIsDisplayed()
        // No valid code is submitted and no send operation is invoked for a valid address.
    }
}

class MusicSettingsFlowTest {
    @get:Rule val compose=createAndroidComposeRule<MusicSpacePreviewActivity>()
    private lateinit var preferences:AppPreferences
    private var original:AppPreferencesState?=null
    @Before fun retainPreferences(){preferences=AppPreferences(InstrumentationRegistry.getInstrumentation().targetContext);original=runBlocking{preferences.read()}}
    @After fun restorePreferences(){original?.let{runBlocking{preferences.set(it)}}}
    @Test fun profileAndSettingsHeadersStayOnWallAndSwitchesPersistWithoutToasts(){
        compose.waitUntil(10000){compose.onAllNodesWithTag("room-home").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("room-space-button").performClick()
        compose.onNodeWithTag("room-module-me").performClick()
        compose.onNodeWithTag("room-my-account").assertIsDisplayed()
        captureAccountScreen("my",compose)
        compose.onNodeWithTag("room-space-button").performClick()
        compose.onNodeWithTag("room-module-settings").performClick()
        compose.onNodeWithTag("room-settings").assertIsDisplayed()
        compose.onNodeWithTag("room-back").performClick()
        compose.onNodeWithTag("room-my-account").assertIsDisplayed()
        compose.onNodeWithText("设置").performScrollTo().performClick()
        compose.onNodeWithTag("account-muted").performClick()
        compose.onNodeWithTag("account-reduced-motion").performClick()
        compose.waitUntil(5000){runBlocking{preferences.read()}.let{it.muted!=original!!.muted&&it.reducedMotion!=original!!.reducedMotion}}
        compose.onNodeWithText("已开启静音").assertDoesNotExist()
        compose.onNodeWithText("已关闭静音").assertDoesNotExist()
        captureAccountScreen("settings",compose)
        compose.onNodeWithText("邮箱账号").performClick()
        compose.onNodeWithText("登录方式：邮箱验证码").assertIsDisplayed()
        captureAccountScreen("email-account",compose)
        compose.onNodeWithTag("room-back").performClick()
        compose.onNodeWithTag("room-settings").assertIsDisplayed()
    }
}
