package cn.musicpilgrimage.app

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import cn.musicpilgrimage.app.design.AppPreferencesState
import cn.musicpilgrimage.app.ui.PilgrimageTheme
import cn.musicpilgrimage.app.ui.room.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.json.JSONObject

/** Signed-in appearance fixtures only. No account, profile or server record is changed. */
class MusicAccountLayoutTest {
    @get:Rule val compose=createComposeRule()
    @Test fun compactProfileEditorAndSignOutCancelAreUsable(){
        var page by mutableStateOf("my")
        var nickname by mutableStateOf("音乐旅人")
        var signedOut=false
        var discard by mutableStateOf(false)
        compose.setContent {PilgrimageTheme {RoomWall(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Column {
                RoomHeader(if(page=="my")"我的" else if(page=="edit")"个人资料" else "设置",onBack=if(page=="edit")({discard=true})else null,onSpace={})
                when(page) {
                    "my"->RoomMyAccount(JSONObject().put("id","layout-owner").put("nickname",nickname),true,"layout@example.invalid",null,4,1,1,{}, {page="edit"},{page="settings"})
                    "edit"->RoomProfileEditor(MusicProfileDraft(owner="layout-owner",nickname=nickname,originalNickname="音乐旅人",active=true),{nickname=it},{},{},{page="my"},{})
                    else->RoomSettings(AppPreferencesState(),true,false,null,{},{},{},{signedOut=true})
                }
            }
            if(discard)RoomDiscardProfileDialog({discard=false},{discard=false;page="my"})
        }}}
        captureAccountScreen("my-signed-in-layout-fixture",compose)
        compose.onNodeWithTag("account-edit-profile").performClick()
        captureAccountScreen("profile-editor-layout-fixture",compose)
        compose.onNodeWithTag("profile-nickname").performTextReplacement("新的音乐旅人")
        compose.onNodeWithTag("profile-save").assertIsEnabled()
        compose.onNodeWithTag("room-back").performClick()
        compose.onNodeWithText("放弃修改？").assertIsDisplayed()
        captureAccountScreen("discard-profile-layout-fixture",compose)
        compose.onNodeWithText("继续编辑").performClick()
        compose.onNodeWithTag("profile-nickname").assertTextContains("新的音乐旅人")
        compose.onNodeWithTag("profile-save").performScrollTo().performClick()
        compose.onNodeWithText("新的音乐旅人").assertIsDisplayed()
        compose.onNodeWithText("设置").performScrollTo().performClick()
        compose.onNodeWithTag("account-sign-out").performClick()
        compose.onNodeWithText("退出登录？").assertIsDisplayed()
        captureAccountScreen("sign-out-layout-fixture",compose)
        compose.onNodeWithText("取消").performClick()
        assertFalse(signedOut)
        compose.onNodeWithTag("room-settings").assertIsDisplayed()
    }
}
