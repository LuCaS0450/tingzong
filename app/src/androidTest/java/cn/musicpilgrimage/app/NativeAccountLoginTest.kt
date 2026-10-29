package cn.musicpilgrimage.app

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import cn.musicpilgrimage.app.data.LibrarySyncStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Existing account, genuine SDK password login. Never imports a saved token or changes account credentials. */
class NativeAccountLoginTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun existingAccountSignsInAndReadsItsPrivateLibrary()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val services=(context.applicationContext as PilgrimageApplication).services
        val credentialFile=File(context.filesDir,"native-login-credentials.json")
        assertTrue("Stage an existing local login using the scoped host helper",credentialFile.isFile)
        val account=JSONObject(credentialFile.readText())
        assertTrue("Temporary staged credentials must be removed immediately",credentialFile.delete())
        try {
            services.authReady.first{it}
            val expected=account.getString("id")
            val current=services.userId.value
            assertTrue("An unrelated signed-in account is left untouched",current==null||current==expected)
            val usedPassword=current==null
            if(usedPassword) {
                try {services.signIn(account.getString("email"),account.getString("password"))}
                catch(error:Exception) {throw AssertionError("Existing-account SDK login failed: ${error.javaClass.simpleName}")}
            }
            withTimeout(30000){services.userId.first{it==expected}}
            assertTrue("SDK owner must match the existing credential record",services.client!=null&&services.userId.value==expected)
            val profile=services.call("self",expectedOwner=expected) as JSONObject
            assertTrue("Authenticated profile must belong to this SDK account",profile.optString("id")==expected)
            val library=services.call("library_state",expectedOwner=expected) as JSONObject
            assertTrue(library.getJSONObject("music_favorites").getJSONObject("data").has("songs"))
            assertTrue(library.getJSONObject("trips").getJSONObject("data").has("trips"))
            compose.waitUntil(20000){compose.onAllNodesWithTag("room-home").fetchSemanticsNodes().isNotEmpty()}
            val sync=withTimeout(65000){services.library.state.first{it.owner=="user:$expected"&&it.status in setOf(LibrarySyncStatus.SYNCED,LibrarySyncStatus.ERROR)}}
            assertTrue("Native background library sync must finish successfully",sync.status==LibrarySyncStatus.SYNCED)
            val folder=File(context.getExternalFilesDir(null),"native-account-login").apply{mkdirs()}
            compose.waitForIdle();InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let{bitmap->File(folder,"signed-in-home.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
            File(context.filesDir,"native-login-result.json").writeText(JSONObject()
                .put("real_sdk_auth",true).put("password_login_performed",usedPassword)
                .put("authenticated_profile_read",true).put("authenticated_private_library_read",true)
                .put("native_background_sync",true).put("temporary_credentials_removed",!credentialFile.exists())
                .put("session_left_signed_in",true).put("account_created_or_reset",false)
                .put("email_otp_ui_tested",false).toString())
        } finally {
            account.remove("password");account.remove("email");credentialFile.delete()
        }
    }
}
