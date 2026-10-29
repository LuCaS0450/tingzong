package cn.musicpilgrimage.app

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import cn.musicpilgrimage.app.data.*
import cn.musicpilgrimage.app.ui.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Existing approved image bytes are pushed by the runner, never bundled. */
class ReleaseCoverUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun fixture(name:String)=Uri.fromFile(File(compose.activity.getExternalFilesDir(null),"material-fixtures/$name")).toString()
    private fun show(content:@androidx.compose.runtime.Composable ()->Unit) {
        compose.activityRule.scenario.onActivity{it.setContent{PilgrimageTheme{
            androidx.compose.material3.Surface(Modifier.fillMaxSize()){
                Column(Modifier.systemBarsPadding().padding(20.dp)){content()}
            }
        }}}
    }
    @Test fun mvPlaceRowUsesIndependentReleaseCoverAndKeepsMvType() {
        val cover=ReleaseArtwork("approved-existing-cover","说好不哭",2019,"single",fixture("saynocry-cover.jpg"),fixture("saynocry-cover.jpg"),"approved",500,500)
        val scene=Scene("separate-mv","jay","周杰伦","说好不哭",imageUrl=fixture("tower.jpg"),imageStatus="approved",imageWidth=1280,imageHeight=720,displayArtwork=cover)
        val place=Place("independent-artwork","发行封面展示验证","东京","日本",35.6,139.7,scenes=listOf(scene))
        show{PageTitle("发行封面与 MV 参考图");PlaceRow(place,onClick={})
            ReferenceImage(scene.imageUrl,"保留的 MV 参考图",Modifier.fillMaxWidth().aspectRatio(1280f/720f))}
        compose.waitUntil(15000){compose.onAllNodesWithText("图片加载中…").fetchSemanticsNodes().isEmpty()}
        compose.onAllNodesWithText("图片加载失败").assertCountEquals(0)
        compose.onNodeWithText("MV取景").assertIsDisplayed()
        compose.onNodeWithContentDescription("发行封面展示验证发行封面").assertIsDisplayed()
        compose.onNodeWithContentDescription("保留的 MV 参考图").assertIsDisplayed()
        val ins=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val folder=File(ins.targetContext.getExternalFilesDir(null),"screenshots").apply{mkdirs()}
        val bitmap=requireNotNull(ins.uiAutomation.takeScreenshot())
        File(folder,"21-independent-release-cover.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
    @Test fun missingReleaseCoverShowsTextAndNeverFallsBackToMv() {
        val scene=Scene("mv-only","jay","周杰伦","说好不哭",imageUrl=fixture("tower.jpg"),imageStatus="approved",imageWidth=1280,imageHeight=720)
        val place=Place("missing-artwork","缺少发行封面的地点","东京","日本",35.6,139.7,scenes=listOf(scene))
        show{PlaceRow(place,onClick={})}
        compose.onNodeWithText("封面待补").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("缺少发行封面的地点发行封面").assertCountEquals(0)
        compose.onAllNodesWithText("图片加载中…").assertCountEquals(0)
        compose.onNodeWithText("MV取景").assertIsDisplayed()
    }
}
