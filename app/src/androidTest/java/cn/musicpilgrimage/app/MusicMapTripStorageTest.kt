package cn.musicpilgrimage.app

import androidx.test.platform.app.InstrumentationRegistry
import cn.musicpilgrimage.app.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class MusicMapTripStorageTest {
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
