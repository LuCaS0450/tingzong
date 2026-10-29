package cn.musicpilgrimage.app

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.espresso.Espresso.closeSoftKeyboard
import cn.musicpilgrimage.app.data.*
import cn.musicpilgrimage.app.ui.PilgrimageTheme
import cn.musicpilgrimage.app.ui.room.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Local transport fixture: verifies real Compose focus/feedback without requesting any email. */
class MusicOtpFeedbackLayoutTest {
    @get:Rule val compose=createComposeRule()
    @Test fun helpSendWaitAndCodeFailureDoNotLoseFocusOrEmail(){
        val completion=CompletableDeferred<Unit>()
        var sends=0
        val gateway=object:EmailOtpGateway {
            override suspend fun send(email:String,register:Boolean){sends++;completion.await()}
            override suspend fun verify(email:String,code:String){throw EmailOtpFailure(OtpFailureKind.CODE,"验证码已失效，请重新获取")}
        }
        compose.setContent {
            val scope=rememberCoroutineScope();val controller=remember{EmailOtpController(gateway,scope)};val state by controller.state.collectAsState()
            PilgrimageTheme{RoomWall(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)){
                RoomEmailLogin(state,controller::email,controller::code,controller::send,controller::verify,controller::registration,controller::help)
            }}
        }
        compose.onNodeWithTag("account-email").performTextInput("layout@example.invalid")
        closeSoftKeyboard()
        compose.onNodeWithTag("account-code-help").performScrollTo().performClick()
        compose.onNodeWithTag("account-help-resend").performClick()
        compose.onNodeWithText("发送中").assertIsDisplayed()
        compose.onNodeWithTag("account-help-resend").assertIsNotEnabled()
        compose.runOnIdle{assertEquals(1,sends);completion.complete(Unit)}
        compose.onNodeWithTag("account-email").assertTextContains("layout@example.invalid")
        compose.onNodeWithTag("account-code").assertIsFocused()
        closeSoftKeyboard()
        captureAccountScreen("otp-sent-layout-fixture",compose)
        compose.onNodeWithTag("account-code").performTextInput("123456")
        compose.onNodeWithTag("account-sign-in").performScrollTo().performClick()
        compose.onNodeWithText("验证码已失效，请重新获取").assertIsDisplayed()
        closeSoftKeyboard()
        captureAccountScreen("otp-code-error-layout-fixture",compose)
    }
}
