package cn.musicpilgrimage.app

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import cn.musicpilgrimage.app.data.MusicCatalogue
import cn.musicpilgrimage.app.data.MusicFavoriteStore
import cn.musicpilgrimage.app.performance.LibraryDatabase
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class MusicArchiveFlowTest {
    @get:Rule val compose=createAndroidComposeRule<MusicSpacePreviewActivity>()
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs=context.getSharedPreferences("music-space-favorites",Context.MODE_PRIVATE)
    private var previous:Map<String,String?>?=null
    private lateinit var owner:String
    private lateinit var first:String
    private lateinit var second:String
    private lateinit var album:String

    @Before fun seedIsolatedGuestExamples(){
        compose.waitUntil(10000){compose.onAllNodesWithTag("room-home").fetchSemanticsNodes().isNotEmpty()}
        val services=(context.applicationContext as PilgrimageApplication).services
        assumeTrue("Do not alter a signed-in account during UI checks",services.userId.value==null)
        owner=LibraryDatabase.guestOwner(context)
        val keys=listOf("$owner:songs","$owner:releases","$owner:added-at")
        previous=keys.associateWith{prefs.getString(it,null)}
        val catalog=MusicCatalogue.from(services.catalog.value)
        val works=catalog.works.take(2)
        first=works[0].id;second=works[1].id;album=catalog.releases.first().id
        prefs.edit().remove(keys[0]).remove(keys[1]).remove(keys[2]).commit()
        compose.runOnIdle {
            val music=ViewModelProvider(compose.activity)[MusicSpaceViewModel::class.java]
            music.toggleFavorite(second);music.toggleFavorite(first);music.toggleFavorite(album,true)
        }
    }
    @After fun restoreGuestData(){previous?.let{values->prefs.edit().apply{values.forEach{(key,value)->if(value==null)remove(key)else putString(key,value)}}.commit()}}
    private fun capture(name:String){
        compose.waitForIdle()
        val directory=File(context.getExternalFilesDir(null),"music-space-stage2").apply{mkdirs()}
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let{bitmap->File(directory,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
    }
    private fun archive(){compose.onNodeWithTag("room-space-button").performClick();compose.onNodeWithTag("room-module-library").performClick();compose.onNodeWithTag("room-archive").assertIsDisplayed()}
    @Test fun cabinetMusicUndoCategoryAndSourceReturn(){
        archive();capture("archive")
        compose.onNodeWithTag("room-drawer-songs").performScrollTo().performClick()
        capture("songs")
        compose.onNodeWithTag("archive-card-$first").assertIsDisplayed()
        compose.onNodeWithTag("archive-remove-$first").performClick()
        compose.onNodeWithTag("archive-card-$first").assertDoesNotExist()
        capture("undo")
        compose.onNodeWithTag("archive-tab-releases").performClick()
        compose.onNodeWithTag("archive-undo-button").performClick()
        compose.onNodeWithTag("archive-tab-releases").assertIsSelected()
        compose.onNodeWithTag("archive-card-$album").assertIsDisplayed()
        capture("albums")
        compose.onNodeWithTag("archive-tab-songs").performClick()
        compose.onNodeWithTag("archive-card-$first").assertIsDisplayed()
        compose.onNodeWithTag("archive-remove-$first").performClick()
        compose.onNodeWithTag("archive-remove-$second").performClick()
        compose.onNodeWithTag("archive-undo-button").performClick()
        compose.onNodeWithTag("archive-card-$first").assertDoesNotExist()
        compose.onNodeWithTag("archive-card-$second").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        // Configuration recreation must retain the selected drawer and current collection state.
        compose.onNodeWithTag("archive-card-$first").assertDoesNotExist()
        compose.onNodeWithTag("archive-card-$second").assertIsDisplayed()
        compose.onNodeWithTag("room-back").performClick()
        compose.onNodeWithTag("room-drawer-places").performClick()
        compose.onNodeWithText("登录查看地点收藏").assertIsDisplayed()
        capture("places-empty")
        compose.onNodeWithTag("archive-tab-footprints").performClick()
        compose.onNodeWithText("登录查看认证足迹").assertIsDisplayed()
        capture("footprints-empty")
        compose.onNodeWithTag("room-back").performClick()
        compose.onNodeWithTag("room-archive").assertIsDisplayed()
    }
}
