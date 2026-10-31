package cn.musicpilgrimage.app

import cn.musicpilgrimage.app.ui.room.WorldCameraSnapshot
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorldCameraSnapshotTest {
    @Test fun streetDetailsRoundTripPreservesTheExactCameraAndSelection() {
        val state = WorldCameraSnapshot(2.373333, 48.84298768033648, 15.125, "le-train-bleu", true)
        assertEquals(state, WorldCameraSnapshot.parse(JSONObject(state.json().toString())))
    }

    @Test fun arbitraryStreetDoesNotRequireASelectedMusicPlace() {
        val state = WorldCameraSnapshot(-73.98, 40.75, 17.0)
        assertEquals(state, WorldCameraSnapshot.parse(state.json()))
        assertFalse(WorldCameraSnapshot.parse(state.json().put("summaryOpen", true))!!.summaryOpen)
    }

    @Test fun corruptAndOutOfRangePersistedCamerasAreRejected() {
        assertNull(WorldCameraSnapshot.parse(JSONObject()))
        val valid = WorldCameraSnapshot(2.37, 48.84, 15.0).json()
        for ((field, value) in listOf("longitude" to 181.0, "latitude" to 90.0, "zoom" to 18.1, "zoom" to -1.0)) {
            assertNull(WorldCameraSnapshot.parse(JSONObject(valid.toString()).put(field, value)))
        }
        assertNull(WorldCameraSnapshot.parse(JSONObject(valid.toString()).put("zoom", "NaN")))
    }
}
