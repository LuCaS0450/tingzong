package cn.musicpilgrimage.app

import cn.musicpilgrimage.app.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class MusicTripTest {
    private val start=LocalDate.of(2026,11,1)
    private val paris=MusicTripCity("巴黎","法国")
    private fun plan()=MusicTrip("trip","出发",start,start.plusDays(2),listOf(paris))
    private fun place(id:String)=Place(id,id,"巴黎","法国",48.85,2.35)
    @Test fun shortenedDatesKeepDayNumbersAndMoveOverflowToUnscheduled() {
        val trip=plan().add(place("a"),1).add(place("b"),3)
        val short=trip.changeDates(start.plusDays(10),start.plusDays(11))
        assertEquals(listOf(1,null),short.stops.map{it.day})
        assertEquals(2,short.days)
    }
    @Test fun samePlaceMayBeVisitedOnDifferentDaysButNotDuplicatedOnOneDay() {
        val trip=plan().add(place("a"),1).add(place("a"),2)
        assertThrows(IllegalArgumentException::class.java){trip.add(place("a"),1)}
        assertThrows(IllegalArgumentException::class.java){trip.move(trip.stops.last().id,1,0)}
        assertEquals(2,trip.stops.size)
    }
    @Test fun movingAcrossDaysAndReorderingDoesNotLoseOtherStops() {
        val trip=plan().add(place("a"),1).add(place("b"),1).add(place("c"),2)
        val reordered=trip.move(trip.stops[0].id,1,1)
        assertEquals(listOf("b","a"),reordered.stops.filter{it.day==1}.map{it.placeId})
        val moved=reordered.move(trip.stops[0].id,2,0)
        assertEquals(listOf("a","c"),moved.stops.filter{it.day==2}.map{it.placeId})
        assertEquals(trip.stops.map{it.id}.toSet(),moved.stops.map{it.id}.toSet())
    }
    @Test fun wrongCityAndInvalidDayCannotBeSaved() {
        assertThrows(IllegalArgumentException::class.java){plan().add(place("a").copy(city="东京"),1)}
        assertThrows(IllegalArgumentException::class.java){plan().add(place("a"),0)}
        assertThrows(IllegalArgumentException::class.java){plan().changeDates(start,start.minusDays(1))}
    }
    @Test fun staleDetailsRetainUntouchedCloudFieldsAndLatestStops() {
        val base=plan();val lyon=MusicTripCity("里昂","法国")
        val remote=base.add(place("a"),2).copy(name="云端名称",cities=listOf(paris,lyon))
        assertEquals(remote,remote.editDetails(base.name,base.cities,base.name,base.cities.map{it.key}))
        val renamed=base.copy(cities=listOf(paris,lyon)).editDetails("本机名称",base.cities,base.name,base.cities.map{it.key})
        assertEquals("本机名称",renamed.name);assertEquals(listOf(paris,lyon),renamed.cities)
    }
    @Test fun conflictingDetailsAndDatesRejectWithoutLosingCloudChanges() {
        val base=plan();val remote=base.copy(name="云端名称",start=start.plusDays(1),end=start.plusDays(3))
        assertThrows(IllegalArgumentException::class.java){remote.editDetails("本机名称",base.cities,base.name,base.cities.map{it.key})}
        assertThrows(IllegalArgumentException::class.java){remote.editDates(start,start.plusDays(4),base.start,base.end)}
        assertEquals(remote,remote.editDates(base.start,base.end,base.start,base.end))
        assertEquals("云端名称",remote.name)
    }
}
