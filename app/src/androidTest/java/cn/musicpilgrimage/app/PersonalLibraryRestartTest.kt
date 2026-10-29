package cn.musicpilgrimage.app

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import cn.musicpilgrimage.app.data.*
import org.junit.Assert.*
import org.junit.Test

/** Invoke separately with the preview process stopped between phases. No session is injected. */
class PersonalLibraryRestartTest {
    private val prefs=InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("personal-library-restart-probe",Context.MODE_PRIVATE)
    private val owner="user:isolated-restart-fixture"
    @Test fun writePendingBeforeRestart() {
        prefs.edit().clear().commit()
        val store=MusicFavoriteStore(prefs){42L}
        store.add(owner,"pending-song",false)
        val sent=store.snapshot(owner)
        store.remove(owner,"pending-song",false)
        store.reconcile(owner,LibraryCloudDocument(1,sent.data,true,sent.clientId),sent)
        assertTrue(store.snapshot(owner).pending)
        assertTrue(prefs.edit().putBoolean("probe-written",true).putString("probe-client",sent.clientId).commit())
    }
    @Test fun readPendingAfterRestart() {
        try {
            assertTrue("Write phase must run in a previous process",prefs.getBoolean("probe-written",false))
            val store=MusicFavoriteStore(prefs);val pending=store.snapshot(owner)
            assertTrue(pending.pending);assertTrue(store.read(owner).songs.isEmpty());assertEquals(1L,pending.revision)
            assertEquals(prefs.getString("probe-client",null),pending.clientId)
            store.reconcile(owner,LibraryCloudDocument(1,pending.base,clientId=pending.clientId))
            assertTrue(store.read(owner).songs.isEmpty());assertTrue(store.snapshot(owner).pending)
            assertTrue(store.read("user:another-fixture").songs.isEmpty())
        } finally {prefs.edit().clear().commit()}
    }
}
