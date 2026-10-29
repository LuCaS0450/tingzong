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
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Real reference bytes are pushed by the runner; no original artwork is packaged in either APK. */
class MaterialsUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun fixture(name:String)=File(compose.activity.getExternalFilesDir(null),"material-fixtures/$name").also{assertTrue("Push the real image fixture first",it.isFile)}
    private fun show(content:@androidx.compose.runtime.Composable ()->Unit) {
        compose.activityRule.scenario.onActivity{it.setContent{PilgrimageTheme{androidx.compose.material3.Surface(Modifier.fillMaxSize()){Box(Modifier.systemBarsPadding()){content()}}}}}
    }
    private fun screenshot(name:String) {
        compose.waitForIdle()
        val ins=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val dir=File(ins.targetContext.getExternalFilesDir(null),"screenshots").apply{mkdirs()}
        val bitmap=requireNotNull(ins.uiAutomation.takeScreenshot())
        File(dir,name).outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    @Test fun originalLandscapeCoverDecodesWithCompleteFrame() {
        val file=fixture("mont.jpg");val ready=AtomicBoolean(false)
        show{Column(Modifier.fillMaxSize().padding(20.dp)){PageTitle("真实原作画幅","圣米歇尔山 · 2022专辑封面")
            ReferenceImage(Uri.fromFile(file).toString(),"完整横幅专辑封面",Modifier.fillMaxWidth().aspectRatio(1280f/863f),onReady={ready.set(it)})}}
        compose.waitUntil(15000){ready.get()}
        compose.onNodeWithContentDescription("完整横幅专辑封面").assertIsDisplayed()
        screenshot("06-material-landscape.png")
    }
    @Test fun failedOriginalCannotOpenOverlayOrCapture() {
        val missing=Uri.fromFile(File(compose.activity.cacheDir,"original-that-does-not-exist.jpg")).toString()
        val scene=Scene("isolated-failure","jay","周杰伦","说好不哭",imageUrl=missing,imageStatus="approved",imageWidth=1280,imageHeight=720,verificationStatus="verified",associationPrecision="landmark",overlayEligible=true)
        show{CameraScreen(scene,{},{})}
        compose.waitUntil(15000){compose.onAllNodesWithText("原图加载失败，叠图已关闭").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("重试原图").assertIsDisplayed()
        compose.onAllNodesWithText("拍摄").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("原作叠图参考").assertCountEquals(0)
        screenshot("07-material-failed-overlay.png")
    }
    @Test fun realMvDecodesBeforeCameraAndCaptureKeepsOriginalRatio() {
        val image=fixture("tower.jpg");val saved=AtomicReference<Uri?>(null)
        val ins=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        ins.uiAutomation.grantRuntimePermission(ins.targetContext.packageName,android.Manifest.permission.CAMERA)
        val scene=Scene("isolated-camera","jay","周杰伦","说好不哭",imageUrl=Uri.fromFile(image).toString(),imageStatus="approved",imageWidth=1280,imageHeight=720,verificationStatus="verified",associationPrecision="landmark",overlayEligible=true)
        show{CameraScreen(scene,{},{saved.set(it)})}
        try {compose.waitUntil(30000){compose.onAllNodesWithText("拍摄").fetchSemanticsNodes().any{isEnabled().matches(it)}}}
        catch(failure:Exception){screenshot("09-material-camera-failure.png");throw failure}
        compose.onNodeWithContentDescription("原作叠图参考").assertIsDisplayed()
        screenshot("09-material-camera-frame.png")
        compose.onNodeWithText("拍摄").performClick()
        compose.waitUntil(20000){saved.get()!=null}
        val file=File(requireNotNull(saved.get()).path!!)
        try {
            val bounds=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true}
            android.graphics.BitmapFactory.decodeFile(file.absolutePath,bounds)
            val orientation=androidx.exifinterface.media.ExifInterface(file).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,1)
            val rotated=orientation in setOf(5,6,7,8)
            val width=if(rotated)bounds.outHeight else bounds.outWidth
            val height=if(rotated)bounds.outWidth else bounds.outHeight
            assertEquals("JPEG including EXIF rotation must use the original MV frame ratio",1280f/720f,width.toFloat()/height,.01f)
        } finally {file.delete()}
    }
    @Test fun titleAndLyricRowsUseRealCoversAndVisibleTypeLabels() {
        val binary=Uri.fromFile(fixture("binary.jpg")).toString();val twelve=Uri.fromFile(fixture("twelve.jpg")).toString()
        val kay=Scene("isolated-title","kay","谢安琪","囍帖街",kind="song_title",imageUrl=binary,imageStatus="approved",referenceKind="release_cover",imageWidth=1280,imageHeight=1148,associationPrecision="landmark",verificationStatus="verified")
        val lyric=Scene("isolated-lyric","jay","周杰伦","爱你没差",kind="lyric",imageUrl=twelve,imageStatus="approved",referenceKind="release_cover",imageWidth=1280,imageHeight=1280,associationPrecision="region",verificationStatus="verified")
        show{Column(Modifier.fillMaxSize().padding(20.dp)){PageTitle("歌里的地点")
            PlaceRow(Place("lee-tung","利东街（囍帖街）","香港","中国香港",22.275,114.172,scenes=listOf(kay)),onClick={})
            PlaceRow(Place("greenwich","格林威治观景台","伦敦","英国",51.4779,-0.0009,scenes=listOf(lyric)),onClick={})}}
        compose.onNodeWithText("歌名关联").assertIsDisplayed();compose.onNodeWithText("歌词提及").assertIsDisplayed()
        compose.waitUntil(15000){compose.onAllNodesWithText("图片加载中…").fetchSemanticsNodes().isEmpty()}
        compose.onNodeWithContentDescription("利东街（囍帖街）发行封面").assertIsDisplayed()
        compose.onNodeWithContentDescription("格林威治观景台发行封面").assertIsDisplayed()
        screenshot("08-material-song-lyric.png")
    }
}
