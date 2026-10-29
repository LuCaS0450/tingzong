package cn.musicpilgrimage.app

import cn.musicpilgrimage.app.data.*
import cn.musicpilgrimage.app.ui.room.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class MusicTripDragTest {
    private val start=LocalDate.of(2026,11,1)
    private val a=MusicTripStop("a","place-a","甲","巴黎","法国",1)
    private val b=MusicTripStop("b","place-b","乙","巴黎","法国",1)
    private val c=MusicTripStop("c","place-c","丙","巴黎","法国",2)
    private fun plan(stops:List<MusicTripStop> = listOf(a,b,c))=MusicTrip("trip","旅行",start,start.plusDays(2),listOf(MusicTripCity("巴黎","法国")),stops)
    private val items=listOf(TripDragItem("header",0f,40f),TripDragItem("day:1",50f,40f),TripDragItem("a",100f,100f),TripDragItem("b",210f,100f),TripDragItem("day:2",320f,40f),TripDragItem("c",370f,100f),TripDragItem("day:3",480f,40f),TripDragItem("empty:3",530f,48f),TripDragItem("day:null",590f,40f))
    @Test fun movingDownUsesAnIndexWithoutTheLiftedStop() {
        val trip=plan();val target=resolveTripDrop(trip,a,290f,items)!!
        assertEquals(TripDropTarget(1,1),target)
        assertEquals(listOf("b","a"),trip.move(a.id,target.day,target.index).stops.filter{it.day==1}.map{it.id})
    }
    @Test fun movingUpAndDroppingOverOwnCardKeepCorrectIndex() {
        assertEquals(TripDropTarget(1,0),resolveTripDrop(plan(),b,110f,items))
        assertEquals(TripDropTarget(1,1),resolveTripDrop(plan(),b,280f,items))
    }
    @Test fun headersEmptyDaysAndUnscheduledAreValidDestinations() {
        assertEquals(TripDropTarget(2,0),resolveTripDrop(plan(),a,330f,items))
        assertEquals(TripDropTarget(3,0),resolveTripDrop(plan(),a,550f,items))
        assertEquals(TripDropTarget(null,0),resolveTripDrop(plan(),a,610f,items))
    }
    @Test fun aDuplicateDayIsRejectedWithoutLosingEitherOccurrence() {
        val repeated=a.copy(id="repeat",day=2);val trip=plan(listOf(a,b,repeated))
        val target=resolveTripDrop(trip,a,330f,items)!!
        assertTrue(target.duplicate)
        assertThrows(IllegalArgumentException::class.java){trip.move(a.id,target.day,target.index)}
        assertEquals(listOf(a,b,repeated),trip.stops)
        assertFalse(resolveTripDrop(trip,a,610f,items)!!.duplicate)
    }
    @Test fun onlyTheEdgeBandScrollsAndVelocityIsBounded() {
        assertEquals(0f,tripDragScrollSpeed(250f,500f,64f,520f),0f)
        assertEquals(-520f,tripDragScrollSpeed(-50f,500f,64f,520f),0f)
        assertEquals(520f,tripDragScrollSpeed(550f,500f,64f,520f),0f)
        assertEquals(260f,tripDragScrollSpeed(468f,500f,64f,520f),.01f)
        assertEquals(0f,tripDragScrollSpeed(10f,0f,64f,520f),0f)
    }
}
