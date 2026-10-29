package cn.musicpilgrimage.app

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import cn.musicpilgrimage.app.data.MusicFavoriteStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class MusicFavoriteStoreTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs=context.getSharedPreferences("music-store-test-${UUID.randomUUID()}",Context.MODE_PRIVATE)
    private var clock=1000L
    private val store=MusicFavoriteStore(prefs){clock++}
    @After fun cleanup(){prefs.edit().clear().commit()}

    @Test fun independentKindsAndUndoKeepOrderAndOriginalDate() {
        store.add("owner-a","old",false);store.add("owner-a","middle",false);store.add("owner-a","new",false)
        store.add("owner-a","album",true)
        val time=store.read("owner-a").time("middle",false)
        val removed=store.remove("owner-a","middle",false)!!
        store.add("owner-a","newest",false)
        assertTrue(store.undo("owner-a",removed))
        assertEquals(listOf("newest","new","middle","old"),store.read("owner-a").songs)
        assertEquals(time,store.read("owner-a").time("middle",false))
        store.remove("owner-a","album",true)
        assertEquals(4,store.read("owner-a").songs.size)
        assertTrue(store.read("owner-a").releases.isEmpty())
        assertFalse(store.undo("owner-a",removed))
    }
    @Test fun undoCannotCrossAccountsOrCopyAnotherOwnersFavorites() {
        store.add("owner-a","a-song",false);store.add("owner-b","b-song",false)
        val removed=store.remove("owner-a","a-song",false)!!
        assertFalse(store.undo("owner-b",removed))
        assertEquals(listOf("b-song"),store.read("owner-b").songs)
        assertTrue(store.read("owner-a").songs.isEmpty())
        assertTrue(store.undo("owner-a",removed))
        assertEquals(listOf("a-song"),store.read("owner-a").songs)
        store.clear("owner-a")
        assertTrue(store.read("owner-a").songs.isEmpty())
        assertEquals(listOf("b-song"),store.read("owner-b").songs)
    }
    @Test fun readsStageOneArraysAndOlderSetsWithoutLosingOtherKeys() {
        prefs.edit().putString("owner:songs","[\"first\",\"second\",\"first\"]")
            .putStringSet("owner:releases",setOf("album")).putString("other:songs","[\"private\"]").commit()
        assertEquals(listOf("first","second"),store.read("owner").songs)
        val removed=store.remove("owner","first",false)!!
        assertTrue(store.undo("owner",removed))
        val reopened=MusicFavoriteStore(prefs)
        assertEquals(listOf("first","second"),reopened.read("owner").songs)
        assertEquals(listOf("album"),reopened.read("owner").releases)
        assertEquals(listOf("private"),reopened.read("other").songs)
    }
}

/** Run these methods in separate instrumentation processes, with force-stop in between. */
class MusicFavoriteRestartTest {
    private val prefs=InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("music-store-restart-probe",Context.MODE_PRIVATE)
    @Test fun writeBeforeRestart(){
        prefs.edit().clear().commit()
        val store=MusicFavoriteStore(prefs){42L}
        store.add("isolated-test-owner","persisted-song",false)
        store.add("isolated-test-owner","persisted-album",true)
        assertTrue(prefs.edit().putBoolean("probe-written",true).commit())
    }
    @Test fun readAfterRestart(){
        try {
            assertTrue("Write probe must run in a previous process",prefs.getBoolean("probe-written",false))
            val state=MusicFavoriteStore(prefs).read("isolated-test-owner")
            assertEquals(listOf("persisted-song"),state.songs)
            assertEquals(listOf("persisted-album"),state.releases)
            assertEquals(42L,state.time("persisted-song",false))
            assertTrue(MusicFavoriteStore(prefs).read("another-owner").songs.isEmpty())
        } finally {prefs.edit().clear().commit()}
    }
}
