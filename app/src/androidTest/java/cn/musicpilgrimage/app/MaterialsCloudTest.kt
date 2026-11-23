package cn.musicpilgrimage.app

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import cn.musicpilgrimage.app.data.*
import cn.musicpilgrimage.app.ui.*
import cn.musicpilgrimage.app.ui.room.RoomBrowseState
import cn.musicpilgrimage.app.ui.room.RoomPlaceDetail
import cn.musicpilgrimage.app.ui.room.SwissWorldWall
import cn.musicpilgrimage.app.ui.room.WorldFocusKind
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.request.ErrorResult
import coil.size.Size
import org.json.JSONObject
import androidx.lifecycle.ViewModelProvider

/** Opt-in read-only check against the actual imported catalog, never quiz fixtures. */
@ExtendedDeviceTest
class MaterialsCloudTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun showWorld(catalog:Catalog, place:Place) {
        compose.activityRule.scenario.onActivity { activity ->
            val browse=RoomBrowseState(activity).apply { focusWorld(place.id, WorldFocusKind.List) }
            activity.setContent {
                PilgrimageTheme {
                    DisposableEffect(browse) { onDispose { browse.worldController?.destroy() } }
                    SwissWorldWall(catalog,browse,emptySet(),false,{},{},{},{},{},emptySet(),true)
                }
            }
        }
        compose.onNodeWithTag("world-summary-title").assertIsDisplayed()
        compose.onNodeWithTag("world-summary-detail").assertIsDisplayed()
    }
    @Test fun committedCatalogRendersCoverMapPinAndNewTypes() {
        val ins=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        assumeTrue(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("materialsLive")=="true")
        assertTrue(BuildConfig.MAPTILER_KEY.isNotBlank())
        val services=(compose.activity.application as PilgrimageApplication).services
        runBlocking{services.ready();services.refreshCatalog()}
        val catalog=services.catalog.value
        assertTrue(services.cloudCatalog.value)
        val shipped=catalogFromJson(JSONObject(ins.targetContext.assets.open("catalog.json").bufferedReader().use{it.readText()}))
        assertEquals(shipped.places.filter{it.verified}.map{it.id}.toSet(),catalog.places.map{it.id}.toSet())
        assertFalse(catalog.places.any{it.id=="shuinandong-port"})
        val havana=catalog.places.first{it.id=="malecon"}
        val kay=catalog.places.first{it.id=="lee-tung-street"}
        val greenwich=catalog.places.first{it.id=="greenwich"}
        assertEquals("release_cover",havana.primaryScene()!!.referenceKind)
        assertTrue(havana.scenes.any{it.kind=="mv"})
        assertTrue(kay.associationLabels().contains("歌名关联"))
        assertTrue(greenwich.associationLabels().contains("歌词提及"))
        showWorld(catalog,havana)
        val folder=File(ins.targetContext.getExternalFilesDir(null),"screenshots").apply{mkdirs()}
        val bitmap=requireNotNull(ins.uiAutomation.takeScreenshot())
        File(folder,"10-material-live-map.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }

    @Test fun importedSheSaysUsesApprovedFrameAndDisablesRecreation() {
        val ins=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        assumeTrue(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("materialsLive")=="true")
        val services=(compose.activity.application as PilgrimageApplication).services
        runBlocking{services.ready();services.refreshCatalog()}
        assertTrue(services.cloudCatalog.value)
        val place=services.catalog.value.places.single{it.id=="familymart-sanfu"}
        val scene=place.scenes.single{it.id=="familymart-sanfu-jj-she-says"}
        assertEquals(200,place.radiusMeters)
        assertEquals(25.0610814,place.latitude,1e-8)
        assertEquals(121.5633485,place.longitude,1e-8)
        assertEquals(300,scene.timestampSeconds)
        assertEquals(2010,scene.releaseYear)
        assertEquals("building",scene.associationPrecision)
        assertTrue(scene.imageApproved);assertFalse(scene.canRecreate)
        val cover=requireNotNull(scene.displayArtwork)
        assertTrue(cover.approved)
        assertEquals("她說",cover.releaseTitle)
        assertEquals(2010,cover.releaseYear)
        assertEquals("album",cover.releaseType)
        assertNotEquals(scene.imageUrl,cover.imageUrl)
        assertEquals("mv_frame",scene.referenceKind)
        assertEquals(cover,place.displayCover())
        val loader=ImageLoader(ins.targetContext)
        try {
            runBlocking {
                listOf(scene.imageUrl,scene.thumbnailUrl).forEachIndexed { index,url->
                    val result=loader.execute(ImageRequest.Builder(ins.targetContext).data(requireNotNull(url))
                        .size(Size.ORIGINAL).allowHardware(false).build())
                    assertTrue("Imported She Says image must decode",result is SuccessResult)
                    val drawable=(result as SuccessResult).drawable
                    assertEquals(if(index==0)1280 else 320,drawable.intrinsicWidth)
                    assertEquals(if(index==0)720 else 180,drawable.intrinsicHeight)
                }
                listOf(cover.imageUrl,cover.thumbnailUrl).forEachIndexed { index,url->
                    val result=loader.execute(ImageRequest.Builder(ins.targetContext).data(requireNotNull(url))
                        .size(Size.ORIGINAL).allowHardware(false).build())
                    assertTrue("Release artwork must decode independently of the MV frame",result is SuccessResult)
                    val drawable=(result as SuccessResult).drawable
                    assertEquals(if(index==0)1280 else 320,drawable.intrinsicWidth)
                    assertEquals(if(index==0)1280 else 320,drawable.intrinsicHeight)
                }
            }
            val sourceCatalog=services.catalog.value
            showWorld(sourceCatalog,place)
            val folder=File(ins.targetContext.getExternalFilesDir(null),"screenshots").apply{mkdirs()}
            fun screenshot(name:String) {
                ins.waitForIdleSync()
                val bitmap=requireNotNull(ins.uiAutomation.takeScreenshot())
                File(folder,name).outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                bitmap.recycle()
            }
            screenshot("18-she-says-release-cover-map.png")
            compose.activityRule.scenario.onActivity{activity->
                val session=ViewModelProvider(activity)[SessionViewModel::class.java]
                val placeVm=ViewModelProvider(activity)[PlaceViewModel::class.java]
                activity.setContent{PilgrimageTheme{Surface(Modifier.fillMaxSize().systemBarsPadding()){
                    RoomPlaceDetail(place,setOf(scene.id),placeVm,session,false,false,{},{},
                        {_,_,_->},{},{},MusicCatalogue.from(sourceCatalog),{},{})
                }}}
            }
            compose.onNodeWithTag("native-place-archive").assertIsDisplayed()
            assertFalse(place.scenes.any(Scene::canRecreate))
            screenshot("19-she-says-release-cover-detail.png")
            compose.onAllNodesWithText("复刻这个镜头").assertCountEquals(0)
            screenshot("20-she-says-release-cover-overlay-closed.png")
        } finally {loader.shutdown()}
    }

    @Test fun approvedJjAdditionDecodesAndKeepsReferenceBoundary() {
        val ins=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        assumeTrue(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("materialsLive")=="true")
        val services=(compose.activity.application as PilgrimageApplication).services
        runBlocking{services.ready();services.refreshCatalog()}
        assertTrue(services.cloudCatalog.value)
        val ids=setOf("shihsanhang-jj-river-south","tatung-jj-stories-untold","tjfsu-jj-jinsha-summer",
            "hualapai-jj-keep-going","yangmingshan-jj-night-day","charles-bridge-jj-never-learn",
            "swiss-alps-jj-never-learn","wanhua-jj-not-tonight-remix")
        val scenes=services.catalog.value.places.flatMap{it.scenes}.filter{it.id in ids}
        assertEquals(ids,scenes.map{it.id}.toSet())
        val loader=ImageLoader(ins.targetContext)
        runBlocking {
            scenes.forEach { scene->
                assertTrue(scene.imageApproved);assertFalse(scene.canRecreate)
                assertTrue(scene.arrivalAnchorNote.isNotBlank())
                listOf(scene.imageUrl,scene.thumbnailUrl).forEach { url->
                    val result=loader.execute(ImageRequest.Builder(ins.targetContext).data(requireNotNull(url))
                        .size(Size.ORIGINAL).allowHardware(false).build())
                    val failure=(result as? ErrorResult)?.throwable?.let { error->
                        generateSequence(error){it.cause}.joinToString(" -> "){"${it.javaClass.simpleName}: ${it.message}"}
                    }
                    assertTrue("${scene.id}: actual cloud image must decode; $failure",result is SuccessResult)
                    if(url==scene.imageUrl) {
                        val drawable=(result as SuccessResult).drawable
                        assertEquals(scene.imageWidth,drawable.intrinsicWidth)
                        assertEquals(scene.imageHeight,drawable.intrinsicHeight)
                    }
                }
            }
        }
        val museum=services.catalog.value.places.first{it.id=="shihsanhang-museum"}
        val mountain=services.catalog.value.places.first{it.id=="yangmingshan-reference"}
        assertEquals(200,mountain.radiusMeters)
        assertEquals("region",mountain.scenes.single{it.id=="yangmingshan-jj-night-day"}.associationPrecision)
        compose.activityRule.scenario.onActivity{activity->activity.setContent{PilgrimageTheme{Surface(Modifier.fillMaxSize()){
            Column(Modifier.systemBarsPadding().padding(20.dp)){
                PageTitle("林俊杰新增原作","云端真实图片 · 地标与区域参考")
                PlaceRow(museum,onClick={});PlaceRow(mountain,onClick={})
            }
        }}}}
        compose.waitUntil(30000){compose.onAllNodesWithText("图片加载中…").fetchSemanticsNodes().isEmpty()}
        compose.onAllNodesWithText("图片加载失败").assertCountEquals(0)
        compose.onNodeWithText("十三行博物馆").assertIsDisplayed()
        ins.waitForIdleSync()
        val folder=File(ins.targetContext.getExternalFilesDir(null),"screenshots").apply{mkdirs()}
        val bitmap=requireNotNull(ins.uiAutomation.takeScreenshot())
        File(folder,"11-jj-approved-materials.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        loader.shutdown()
    }
}
