package cn.musicpilgrimage.app

import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cn.musicpilgrimage.app.data.MusicCatalogue
import cn.musicpilgrimage.app.design.AppPreferences
import cn.musicpilgrimage.app.performance.*
import cn.musicpilgrimage.app.ui.PilgrimageTheme
import cn.musicpilgrimage.app.ui.room.RoomWall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Actual original-speed recordings; scoped test owners never alter the signed-in user's results. */
class AddedPerformanceTracksTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val ids = PerformanceCatalog.ORDERED_SONG_IDS
    private val playTestIds = listOf("love-confession", "she-says", "you-complete-me")
    private val evidence get() = File(context.getExternalFilesDir(null), "added-performance").apply { mkdirs() }
    private lateinit var controller: PerformanceController
    private var ready = false

    @Before fun useNativeClock() { compose.mainClock.autoAdvance = false }

    private fun await(timeout: Long = 45000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < deadline) {
            compose.mainClock.advanceTimeByFrame(); Thread.sleep(16)
        }
        assertTrue(if (ready) "state=${controller.ui.value.state}, position=${controller.ui.value.positionUs}, message=${controller.ui.value.message}" else "Controller not ready", condition())
        compose.mainClock.advanceTimeBy(32); compose.waitForIdle()
    }

    private fun catalogue() = MusicCatalogue.from((context.applicationContext as PilgrimageApplication).services.catalog.value)
    private fun source(id: String): File {
        val runId=InstrumentationRegistry.getArguments().getString("performanceRunId", "direct")!!
        require(Regex("[a-z0-9-]+").matches(runId))
        return File(context.filesDir,"performance-test-imports/$runId/$id-full-audio.zip")
    }
    private fun importSong(song: SongPackage) {
        assertTrue("Missing local authorized audio: ${song.id}", source(song.id).isFile)
        source(song.id).inputStream().use { PerformanceAudioStore(context).import(song, it) }
    }
    private fun clearOwner(owner: String) = runBlocking(Dispatchers.IO) {
        LibraryDatabase.open(context).openHelper.writableDatabase.apply {
            execSQL("delete from performance_checkpoints where owner=?", arrayOf(owner))
            execSQL("delete from performance_runs where owner=?", arrayOf(owner))
            execSQL("delete from performance_results where owner=?", arrayOf(owner))
        }
    }
    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(64); compose.waitForIdle(); instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            File(evidence, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun catalogueImportsWrongSongProtectionAndResultIsolation() {
        ActivityScenario.launch(MainActivity::class.java).use {
            await { catalogue().works.isNotEmpty() }
            val songs = PerformanceCatalog.load(context)
            assertEquals(PerformanceCatalog.ORDERED_SONG_IDS, songs.map { song -> song.id })
            val background = context.assets.open("performance/greatest-works/background.webp").use { stream -> stream.readBytes() }
            val imported = mutableListOf<String>()
            ids.forEach { id -> assertTrue("Missing local authorized audio: $id", source(id).isFile) }
            ids.forEach { id ->
                val song = songs.single { song -> song.id == id }
                val work = catalogue().works.singleOrNull { work -> work.title == song.title && work.artist == song.artist }
                val release = catalogue().releases.singleOrNull { rel -> rel.title == song.title && rel.artist == song.artist }
                assertTrue(work != null || release != null)
                val artwork = work?.artwork ?: release?.artwork
                assertTrue(artwork?.approved == true)
                val placeIds = if (work != null) {
                    work.places.map { link -> link.place.id }.toSet()
                } else {
                    release!!.places.filter { link -> link.scenes.any { it.work == song.title } }.map { link -> link.place.id }.toSet()
                }
                if (id == PerformanceCatalog.SHARED_BACKGROUND_FOLDER) {
                    // The original package links a selected subset of the work's places.
                    assertTrue(placeIds.containsAll(song.placeIds))
                } else {
                    assertEquals(placeIds, song.placeIds.toSet())
                    assertFalse("Added tracks share the primary background asset", song.background in context.assets.list("performance/$id").orEmpty())
                }
                assertArrayEquals(background, context.assets.open(PerformanceCatalog.backgroundAssetPath(context, song)).use { stream -> stream.readBytes() })
                assertTrue(song.recording!!.local)
                assertEquals("pcm16-delta", song.recording!!.encoding)
                assertTrue(song.durationUs in 90_000_000..120_000_000)
                assertEquals(setOf(1.0), song.recording!!.variants.keys)
                assertTrue(song.standard.any { target -> target.hold })
                assertTrue(song.standard.zipWithNext().all { (a, b) -> b.onsetUs - a.onsetUs >= 250000 && (!a.hold || b.onsetUs >= a.endUs) })
                val json = context.assets.open("performance/$id/song.json").bufferedReader().use { stream -> JSONObject(stream.readText()) }
                assertFalse(json.getJSONObject("charts").has("easy"))
                importSong(song)
                assertNotNull("Import did not produce ready audio: $id", PerformanceAudioStore(context).directory(song))
                imported += id
            }
            val song = songs.single { song -> song.id == playTestIds[0] }
            val store = PerformanceAudioStore(context)
            assertThrows(Exception::class.java) { source(playTestIds[1]).inputStream().use { stream -> store.import(song, stream) } }
            val audio = File(requireNotNull(store.directory(song)), "original.pcmz")
            val digest = MessageDigest.getInstance("SHA-256")
            audio.inputStream().use { stream -> val buffer = ByteArray(65536); while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
            assertEquals(song.recording!!.files.getValue("original.pcmz").sha256, digest.digest().joinToString("") { byte -> "%02x".format(byte) })
            val owner = "test:added-isolation:${UUID.randomUUID()}"
            try {
                runBlocking(Dispatchers.IO) {
                    val dao = LibraryDatabase.open(context).performance()
                    ids.take(2).forEachIndexed { index, id -> dao.finish(PerformanceResult(owner, UUID.randomUUID().toString(), id, 1, 2, 1000 + index * 500, 2, 1, 1, 0, System.currentTimeMillis())) }
                    assertEquals(2, dao.results(owner).first().size)
                    assertEquals(1000, dao.bestResult(owner, ids[0], 2)!!.score)
                    assertEquals(1500, dao.bestResult(owner, ids[1], 2)!!.score)
                }
            } finally { clearOwner(owner) }
            File(evidence, "imports-and-isolation.json").writeText(JSONObject().put("passed", true)
                .put("runId", InstrumentationRegistry.getArguments().getString("performanceRunId", "direct"))
                .put("importedSongs", org.json.JSONArray(imported)).put("wrongSongPreservedExistingAudio", true).put("isolatedResults", true).toString())
        }
    }

    @Test fun mainNavigationListsSongsAndConnectsExistingSongDetails() {
        ActivityScenario.launch(MainActivity::class.java).use {
            await { compose.onAllNodesWithTag("room-space-button").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("room-space-button").performClick()
            compose.mainClock.advanceTimeBy(700)
            compose.onNodeWithTag("room-module-performance").performClick()
            await { compose.onAllNodesWithTag("performance-list").fetchSemanticsNodes().isNotEmpty() }
            capture("fifteen-song-list-top")
            PerformanceCatalog.ORDERED_SONG_IDS.forEach { id ->
                compose.onNodeWithTag("performance-list").performScrollToKey(id)
                compose.onNodeWithTag("performance-song-$id").assertIsDisplayed()
            }
            capture("fifteen-song-list-bottom")
            val sampleIds = listOf("greatest-works", "common-jasmine-orange", "river-south", "under-mount-fuji", "he-r", "love-me-or-him", "wedding-card-street", "hotel-california")
            sampleIds.forEach { id ->
                await { compose.onAllNodesWithTag("performance-list").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("performance-list").performScrollToKey(id)
                compose.onNodeWithTag("performance-song-$id").performClick()
                await { compose.onAllNodesWithTag("performance-preparation").fetchSemanticsNodes().isNotEmpty() }
                if (id in listOf("common-jasmine-orange", "hotel-california")) capture("$id-preparation")
                compose.onNodeWithTag("room-back").performClick()
                compose.mainClock.advanceTimeBy(400)
                await { compose.onAllNodesWithTag("performance-list").fetchSemanticsNodes().isNotEmpty() }
            }
            compose.onNodeWithTag("room-space-button").performClick()
            compose.mainClock.advanceTimeBy(700)
            compose.onNodeWithTag("room-module-catalogue").performClick()
            await { compose.onAllNodesWithTag("room-search-button").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("room-search-button").performClick()
            await { compose.onAllNodesWithTag("room-search-input").fetchSemanticsNodes().isNotEmpty() }
            val songs = PerformanceCatalog.load(context)
            listOf("secret", "under-mount-fuji", "hotel-california").forEach { id ->
                val song = songs.single { entry -> entry.id == id }
                val work = catalogue().works.single { entry -> entry.title == song.title && entry.artist == song.artist }
                compose.onNodeWithTag("room-search-input").performTextReplacement(song.title)
                await { compose.onAllNodesWithTag("room-work-${work.id}").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("room-work-${work.id}").performClick()
                await { compose.onAllNodesWithTag("room-song").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("演奏", substring = false).performScrollTo().performClick()
                compose.mainClock.advanceTimeBy(400)
                await { compose.onAllNodesWithTag("performance-preparation").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("performance-preparation").assertIsDisplayed()
                assertTrue(compose.onAllNodesWithText(song.title, substring = false).fetchSemanticsNodes().isNotEmpty())
                compose.onNodeWithTag("room-back").performClick()
                compose.mainClock.advanceTimeBy(400)
                await { compose.onAllNodesWithTag("room-song").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("room-back").performClick()
                compose.mainClock.advanceTimeBy(400)
                await { compose.onAllNodesWithTag("room-search-input").fetchSemanticsNodes().isNotEmpty() }
            }
        }
    }

    private fun wholeSong(id: String) {
        val song = PerformanceCatalog.load(context).single { it.id == id }
        if (PerformanceAudioStore(context).directory(song) == null) importSong(song)
        val owner = "test:added-full:$id:${UUID.randomUUID()}"
        val preferences = AppPreferences(context)
        val wasMuted = runBlocking { preferences.read().muted }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                await { catalogue().works.isNotEmpty() }
                val artwork = catalogue().works.singleOrNull { it.title == song.title && it.artist == song.artist }?.artwork
                    ?: catalogue().releases.singleOrNull { it.title == song.title && it.artist == song.artist }?.artwork
                activity.onActivity { host ->
                    var immersive by mutableStateOf(false)
                    val entry = PerformanceEntry(song, audioDirectory = requireNotNull(PerformanceAudioStore(host).directory(song)))
                    host.setContent { PilgrimageTheme { RoomWall(Modifier.fillMaxSize().then(if (immersive) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing))) {
                        PerformanceFlow(entry, owner, {}, artwork, onFullscreen = { immersive = it }, onController = { if (it != null) { controller = it; ready = true } })
                    } } }
                }
                await { compose.onAllNodesWithTag("performance-preparation").fetchSemanticsNodes().isNotEmpty() }
                capture("$id-preparation")
                compose.onNodeWithTag("performance-enter").performScrollTo().performClick()
                await { ready && controller.ui.value.countIn == 3 }
                assertEquals(PlayMode.PLAY, controller.options.mode)
                assertEquals(2, controller.options.difficulty)
                assertEquals(1.0, controller.options.speed, 0.0)
                val tap = controller.targets.first { !it.hold }
                await { controller.ui.value.countIn == 0 && controller.ui.value.positionUs >= tap.onsetUs - 70000 }
                compose.onNodeWithTag("performance-lane-${tap.lane}").performTouchInput { click(center) }
                assertTrue(controller.ui.value.judgments[tap.id] in listOf(Judgment.PERFECT, Judgment.GOOD))
                try {
                    runBlocking { preferences.setMuted(false) }
                    await { (controller.audioOutputPeak() ?: 0f) > .001f }
                } finally { runBlocking { preferences.setMuted(wasMuted) } }
                val hold = controller.targets.first { it.hold }
                await { controller.ui.value.positionUs >= hold.onsetUs - 70000 }
                compose.onNodeWithTag("performance-lane-${hold.lane}").performTouchInput { down(center) }
                assertEquals(Judgment.HOLDING, controller.ui.value.judgments[hold.id])
                await { controller.ui.value.positionUs >= hold.onsetUs + 350000 }
                capture("$id-hold")
                await { controller.ui.value.positionUs >= hold.endUs - 40000 }
                compose.onNodeWithTag("performance-lane-${hold.lane}").performTouchInput { up() }
                assertTrue(controller.ui.value.judgments[hold.id] in listOf(Judgment.PERFECT, Judgment.GOOD))
                compose.onNodeWithTag("performance-pause").performClick()
                await { controller.ui.value.state == PlayState.PAUSED }
                compose.onNodeWithTag("performance-pause-panel").assertIsDisplayed()
                compose.onNodeWithTag("performance-start").performClick()
                await { controller.ui.value.countIn == 0 && controller.ui.value.state == PlayState.PLAYING }
                val beganAt = SystemClock.elapsedRealtime()
                val sourceAt = controller.ui.value.positionUs
                await(song.durationUs / 1000 + 30000) { controller.ui.value.state == PlayState.FINISHED && controller.ui.value.saved }
                val elapsed = SystemClock.elapsedRealtime() - beganAt
                assertTrue("Full recording must finish at original speed", kotlin.math.abs(elapsed - (song.durationUs - sourceAt) / 1000) < 3500)
                assertEquals(song.durationUs, controller.ui.value.positionUs)
                assertTrue(controller.ui.value.judgments.none { it in listOf(Judgment.WAITING, Judgment.HOLDING) })
                compose.onNodeWithTag("performance-result").assertIsDisplayed()
                val dao = LibraryDatabase.open(context).performance()
                val rows = runBlocking(Dispatchers.IO) { dao.results(owner).first() }
                assertEquals(1, rows.size)
                assertEquals(song.id, rows.single().songId)
                assertTrue(rows.single().score > 0)
                assertNull(runBlocking(Dispatchers.IO) { dao.checkpoint(owner, song.id, song.version, 2, "play") })
                capture("$id-result")
                File(evidence, "$id-result.json").writeText(JSONObject().put("passed", true).put("songId", id)
                    .put("durationUs", song.durationUs).put("targets", song.standard.size).put("holds", song.standard.count { it.hold })
                    .put("score", rows.single().score).put("resumedAtUs", sourceAt).put("elapsedAfterResumeMs", elapsed)
                    .put("tapHoldPauseResumeAndWholeRecording", true).toString())
            }
        } finally {
            runBlocking { preferences.setMuted(wasMuted) }
            clearOwner(owner)
        }
    }

    @Test fun loveConfessionWholeRecording() = wholeSong("love-confession")
    @Test fun sheSaysWholeRecording() = wholeSong("she-says")
    @Test fun youCompleteMeWholeRecording() = wholeSong("you-complete-me")
    @Test fun commonJasmineOrangeWholeRecording() = wholeSong("common-jasmine-orange")
}
