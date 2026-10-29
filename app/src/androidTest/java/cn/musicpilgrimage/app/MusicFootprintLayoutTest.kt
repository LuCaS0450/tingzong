package cn.musicpilgrimage.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import cn.musicpilgrimage.app.data.ContentItem
import cn.musicpilgrimage.app.data.publicOnly
import cn.musicpilgrimage.app.ui.PilgrimageTheme
import cn.musicpilgrimage.app.ui.room.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** In-memory layout fixture only: no visit is created, certified, uploaded or published. */
class MusicFootprintLayoutTest {
    @get:Rule val compose=createComposeRule()
    @Test fun timelineLongPressAndSwipeKeepCertificationSeparate(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val services=(context.applicationContext as PilgrimageApplication).services
        val places=services.catalog.value.publicOnly().places
        val place=places.first()
        val record=ContentItem(JSONObject().put("id","layout-only-record").put("kind","visit")
            .put("user_id","isolated-layout-owner").put("location_id",place.id).put("location_name",place.name)
            .put("created_at","2026-10-19T10:00:00Z").put("verified_at","2026-10-19T10:00:00Z")
            .put("visibility","private").put("body","测试用文字，用于检查时间线与管理操作。"))
        var edited=false;var deleted=false
        compose.setContent {PilgrimageTheme {RoomWall(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),baseColor=RoomColors.ArchiveWall) {
            Column {RoomHeader("资料册",onSpace={});RoomPlaceFavorites(MusicArchiveState(owner="isolated-layout-owner",visits=listOf(record)),places,"footprints",{}, {}, {}, {},{edited=true},{deleted=true},services)}
        }}}
        compose.onNodeWithTag("archive-expand-${place.id}").performClick()
        compose.onNodeWithText("2026-10-19").assertIsDisplayed()
        val directory=File(context.getExternalFilesDir(null),"music-space-stage2").apply{mkdirs()}
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let{bitmap->File(directory,"footprints-layout-fixture.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
        compose.onNodeWithTag("archive-visit-layout-only-record").performTouchInput{longClick()}
        compose.onNodeWithText("编辑").performClick()
        compose.runOnIdle {assertTrue(edited);assertFalse(deleted)}
        compose.onNodeWithTag("archive-visit-layout-only-record").performTouchInput{swipeLeft()}
        compose.onNodeWithContentDescription("删除感想与照片").performClick()
        compose.onNodeWithText("到访日期与已经获得的认证会保留。").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle {assertFalse(deleted)}
    }
}
