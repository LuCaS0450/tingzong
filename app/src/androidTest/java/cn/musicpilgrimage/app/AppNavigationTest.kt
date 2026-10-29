package cn.musicpilgrimage.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class AppNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val folder = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        java.io.File(folder, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun nativeMapInitializesWithoutOnlineTiles() {
        org.junit.Assume.assumeTrue("MapLibre needs GLES 3; this device uses the tested list fallback",cn.musicpilgrimage.app.data.MapSupport.isAvailable(compose.activity))
        val loaded = java.util.concurrent.CountDownLatch(1)
        var view: org.maplibre.android.maps.MapView? = null
        compose.activityRule.scenario.onActivity { activity ->
            org.maplibre.android.MapLibre.getInstance(activity)
            view = org.maplibre.android.maps.MapView(activity).apply {
                onCreate(null); onStart(); onResume()
                activity.addContentView(this, android.view.ViewGroup.LayoutParams(320, 320))
                getMapAsync { it.setStyle(org.maplibre.android.maps.Style.Builder().fromJson("""{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#102128"}}]}""")) { loaded.countDown() } }
            }
        }
        org.junit.Assert.assertTrue("MapLibre native style must initialize", loaded.await(30, java.util.concurrent.TimeUnit.SECONDS))
        compose.activityRule.scenario.onActivity { view?.let { map -> (map.parent as? android.view.ViewGroup)?.removeView(map); map.onPause(); map.onStop(); map.onDestroy() } }
    }
    @Test fun searchAndNavigateWithoutCloudCredentials() {
        compose.waitUntil(15000) { compose.onAllNodesWithText("搜索歌手、歌曲、地点或城市").fetchSemanticsNodes().isNotEmpty() }
        screenshot("01-explore.png")
        if(!cn.musicpilgrimage.app.data.MapSupport.isAvailable(compose.activity)) compose.onNodeWithText("这台设备暂不支持在线地图").assertExists()
        compose.onNodeWithText("搜索歌手、歌曲、地点或城市").performTextInput("巴黎")
        compose.onNode(hasSetTextAction()).performImeAction()
        if(compose.onAllNodesWithContentDescription("切换为列表").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("切换为列表").performClick()
        compose.onNodeWithTag("place-list").performScrollToNode(hasText("莎玛丽丹百货", substring = true))
        compose.onNodeWithText("莎玛丽丹百货", substring = true).assertIsDisplayed().performClick()
        compose.onNodeWithText("坐标精度：", substring = true).assertExists()
        screenshot("02-place.png")
        androidx.test.espresso.Espresso.pressBack()
        compose.waitUntil(5000) { compose.onAllNodesWithText("挑战", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("挑战", useUnmergedTree = true).performClick()
        compose.onNodeWithText("邮箱登录").performScrollTo().assertIsDisplayed()
        screenshot("03-challenges.png")
        compose.onNodeWithText("收藏", useUnmergedTree = true).performClick()
        compose.onNodeWithText("邮箱登录").performScrollTo().assertIsDisplayed()
        screenshot("04-favorites.png")
        compose.onNodeWithText("我的", useUnmergedTree = true).performClick()
        compose.onNodeWithText("邮箱登录").performScrollTo().assertIsDisplayed()
        screenshot("05-me.png")
    }
}
