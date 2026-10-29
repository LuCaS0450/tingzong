package cn.musicpilgrimage.app

import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import cn.musicpilgrimage.app.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class MusicMapTripStorageTest {
    @Test fun providerAliasesUseOneSimplifiedLabelWithoutRemovingDistinctNames() {
        val labels=cn.musicpilgrimage.app.design.ChineseMapLabels.load(InstrumentationRegistry.getInstrumentation().targetContext)
        assertEquals("第八区",labels.simplify("第八区;第八區"))
        assertEquals("东京都",labels.simplify("东京都/東京都"))
        assertEquals("巴黎/Paris",labels.simplify("巴黎;Paris"))
        val style=labels.style("""{"version":8,"sources":{},"layers":[{"id":"City labels","type":"symbol","layout":{"text-field":["coalesce",["get","name:zh"],["get","name"]]}}]}""".toByteArray())
        val expression=org.json.JSONObject(style.toString(Charsets.UTF_8)).getJSONArray("layers").getJSONObject(0).getJSONObject("layout").getJSONArray("text-field")
        assertEquals("let",expression.getString(0));assertEquals("to-string",expression.getJSONArray(2).getString(0));assertEquals("coalesce",expression.getJSONArray(2).getJSONArray(1).getString(0))
    }
    @Test fun disposedCityCannotOverwriteActiveCameraAndSavedStateRestoresSelection() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=instrumentation.targetContext.applicationContext as PilgrimageApplication
        instrumentation.runOnMainSync {
            val saved=SavedStateHandle();val vm=MusicMapViewModel(app,saved)
            vm.scope("user:test:work");vm.chooseCity("法国::巴黎");vm.select("法国::巴黎","place-a");vm.full(true)
            vm.camera("user:test:work","法国::巴黎",listOf(48.85,2.35,13.0))
            vm.back();vm.back();vm.chooseCity("日本::东京")
            vm.camera("user:test:work","法国::巴黎",listOf(48.86,2.36,14.0))
            assertNull(vm.camera.value)
            vm.back();vm.chooseCity("法国::巴黎")
            assertEquals("place-a",vm.selected.value);assertEquals(14.0,vm.camera.value!![2],.001)
            val restored=MusicMapViewModel(app,SavedStateHandle(saved.keys().associateWith{saved.get<Any?>(it)}))
            assertEquals(vm.selected.value,restored.selected.value);assertEquals(vm.camera.value,restored.camera.value)
            restored.camera("user:test:work","法国::巴黎",listOf(Double.NaN,2.35,13.0))
            assertEquals(vm.camera.value,restored.camera.value)
            restored.scope("user:other:work");assertEquals("",restored.city.value);assertEquals("",restored.selected.value)
        }
    }
    @Test fun tripRoundTripPreservesPendingAndCustomPlacesAndSeparatesOwners() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val preferences=context.getSharedPreferences("test-map-trips",0)
        val store=MusicTripStore(preferences)
        try {
            val date=LocalDate.of(2026,11,1)
            val trip=MusicTrip("test","法国与日本",date,date.plusDays(2),listOf(MusicTripCity("巴黎","法国"),MusicTripCity("东京","日本")),listOf(MusicTripStop("a",null,"咖啡馆","巴黎","法国",null,"街角")))
            store.write("user:a",listOf(trip));assertEquals(listOf(trip),store.read("user:a"));assertTrue(store.read("user:b").isEmpty())
            preferences.edit().putString("user:b:trips","[null,{},3]").commit()
            assertTrue(store.read("user:b").isEmpty());assertEquals(listOf(trip),store.read("user:a"))
        } finally {preferences.edit().clear().commit()}
    }
}
