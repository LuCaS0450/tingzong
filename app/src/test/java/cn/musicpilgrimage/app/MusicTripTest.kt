package cn.musicpilgrimage.app

import cn.musicpilgrimage.app.data.*
import cn.musicpilgrimage.app.ui.room.searchTripDestinations
import cn.musicpilgrimage.app.ui.room.tripDestinationOptions
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

class MusicTripTest {
    @Test fun generatedCopyUsesPlainTitleWhileKeepingItsStoredName() {
        val name = "巴黎周末（同步副本）"
        val id = UUID.nameUUIDFromBytes("trip-copy".toByteArray()).toString()
        val trip = plan().copy(id = id, name = name)
        assertEquals("巴黎周末（副本）", trip.displayName)
        assertEquals(trip, MusicTripCodec.decode(MusicTripCodec.encode(listOf(trip))).single())
        assertEquals(name, trip.copy(id = UUID.randomUUID().toString()).displayName)
    }

    @Test fun hongKongSearchOffersOneDestinationAndKeepsOtherCityAndCountryChoices() {
        val hongKong=MusicTripCity("香港","中国香港")
        val lantau=MusicTripCity("香港 · 大屿山","中国香港")
        val region=MusicTripCity.country("中国香港")
        val paris=MusicTripCity("巴黎","法国")
        val inputs=listOf(region,hongKong,lantau,paris)
        val options=tripDestinationOptions(inputs)
        for(query in listOf("香港","中国香港","中國香港","Hong Kong")) {
            assertEquals(listOf(region),searchTripDestinations(options,query,inputs))
        }
        assertEquals(listOf(region),searchTripDestinations(options,"大屿山",inputs))
        assertEquals(setOf(paris,MusicTripCity.country("法国")),searchTripDestinations(options,"法国").toSet())
    }

    @Test fun hongKongStopsUseTheExistingDestinationEvenWithALegacyCityName() {
        val lantern=Place("lantau","昂坪","香港 · 大屿山","中国香港",22.25,113.91)
        for(destination in listOf(MusicTripCity("香港","中国香港"),MusicTripCity.country("中国香港"))) {
            val trip=plan().copy(cities=listOf(destination)).add(lantern,1)
            assertEquals(listOf(destination),trip.cities)
            assertEquals(destination.name,trip.stops.single().city)
            assertEquals("中国香港",trip.stops.single().country)
            assertEquals(trip,MusicTripCodec.decode(MusicTripCodec.encode(listOf(trip))).single())
        }
        assertEquals(listOf("中国香港"),geographicParts("香港","中国香港"))
        assertEquals(listOf("中国香港"),geographicParts("中国香港（全境）","中国香港"))
        assertEquals(listOf("中国香港","大屿山"),geographicParts(lantern.city,lantern.country))
        assertEquals(musicMapCityKey("香港","中国香港"),musicMapCityKey(lantern.city,lantern.country))
        assertEquals("中国香港",lantern.regionalTitle("香港（中环公共区域城市参考）"))
        assertEquals("香港大会堂",lantern.regionalTitle("香港大会堂"))
    }

    @Test fun cityAndCountyAliasesOfferOneChoiceWithoutMergingNeighboringCities() {
        val taiwan = "中国台湾"
        val keelung = MusicTripCity("基隆", taiwan)
        val taipei = MusicTripCity("台北", taiwan)
        val newTaipei = MusicTripCity("新北", taiwan)
        val hualien = MusicTripCity("花莲", taiwan)
        val tokyo = MusicTripCity("东京", "日本")
        val fuchu = MusicTripCity("府中", "日本")
        val losAngeles = MusicTripCity("洛杉矶", "美国")
        val catalogue = listOf(
            keelung, MusicTripCity("基隆市", taiwan),
            taipei, MusicTripCity("台北／新北", taiwan), MusicTripCity("台北市松山区", taiwan),
            newTaipei, MusicTripCity("新北市淡水区", taiwan),
            hualien, MusicTripCity("花莲县丰滨乡", taiwan),
            tokyo, MusicTripCity("Tokyo", "日本"), MusicTripCity("东京都府中市", "日本"),
            losAngeles, MusicTripCity("Los Angeles", "美国"),
        )
        val choices = tripDestinationOptions(catalogue)
        assertEquals(listOf(keelung, taipei, newTaipei, hualien, tokyo, fuchu, losAngeles), choices.filterNot { it.isCountry })

        val searches = mapOf(
            "基隆市" to keelung, "臺北市松山區" to taipei, "台北／新北" to taipei,
            "新北" to newTaipei, "淡水" to newTaipei, "豐濱" to hualien,
            "Tokyo" to tokyo, "府中" to fuchu, "Los Angeles" to losAngeles,
        )
        searches.forEach { (query, expected) ->
            assertEquals(query, listOf(expected), searchTripDestinations(choices, query, catalogue))
        }
    }

    @Test fun addingAnAliasedPlaceKeepsTheExistingDestinationAndItsSavedActivities() {
        val previous = MusicTripCity("基隆市", "中国台湾")
        val canonical = MusicTripCity("基隆", "中国台湾")
        val localPlace = place("keelung").copy(city = canonical.name, country = canonical.country)
        val station = localPlace.copy(id = "station", city = previous.name)
        val legacy = plan().copy(cities = listOf(previous)).add(station, 1)
        val updated = legacy.add(localPlace, 2)
        assertEquals(listOf(previous), updated.cities)
        assertEquals(legacy.stops.single(), updated.stops.first())
        assertEquals(listOf(previous.name, previous.name), updated.stops.map { it.city })
        assertEquals(updated, MusicTripCodec.decode(MusicTripCodec.encode(listOf(updated))).single())
        assertEquals(listOf(paris, canonical), plan().add(station, 1).cities)
    }

    private val start=LocalDate.of(2026,11,1)
    private val paris=MusicTripCity("巴黎","法国")
    private fun plan()=MusicTrip("trip","出发",start,start.plusDays(2),listOf(paris))
    private fun place(id:String)=Place(id,id,"巴黎","法国",48.85,2.35)
    @Test fun shorteningDatesRequiresMovingOverflowFirst() {
        val trip=plan().add(place("a"),1).add(place("b"),3)
        assertEquals(listOf("b"),trip.stopsBeyond(2).map{it.placeId})
        assertThrows(IllegalArgumentException::class.java){trip.changeDates(start,start.plusDays(1))}
        val moved=trip.move(trip.stops.last().id,2,0)
        val short=moved.changeDates(start.plusDays(10),start.plusDays(11))
        assertEquals(listOf(1,2),short.stops.map{it.day})
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
    @Test fun addingAcrossCitiesAppendsDestinationOnlyOnce() {
        val brighton=MusicTripCity("布莱顿","英国")
        val place=place("a").copy(city=brighton.name,country=brighton.country)
        val updated=plan().add(place,1).add(place,2)
        assertEquals(listOf(paris,brighton),updated.cities)
        assertEquals(listOf(1,2),updated.stops.map{it.day})
    }
    @Test fun lastDayCountsAsAnActiveTrip() {
        assertTrue(plan().acceptsNewPlacesOn(start.minusDays(1)))
        assertTrue(plan().acceptsNewPlacesOn(plan().end))
        assertFalse(plan().acceptsNewPlacesOn(plan().end.plusDays(1)))
    }
    @Test fun countryDestinationStaysDistinctFromSameNamedCity() {
        val country=MusicTripCity.country("新加坡")
        val city=MusicTripCity("新加坡","新加坡")
        assertEquals("新加坡",country.label)
        assertTrue(country.isCountry)
        assertFalse(city.isCountry)
        val trip=plan().copy(cities=listOf(country)).add(place("a").copy(city=city.name,country=city.country),1)
        assertEquals(listOf(country,city),trip.cities)
    }
    @Test fun legacyTaiwanDestinationsAndStopsReadTogetherWithoutMarkingTheTripEdited() {
        val document = JSONObject("""
            {"trips":[{"id":"trip","name":"旅行","start":"2026-11-01","end":"2026-11-01",
            "cities":[{"name":"台湾（全境）","country":"台湾"},{"name":"台北","country":"中華民國"},
                      {"name":"台北","country":"中国台湾"}],
            "stops":[{"id":"stop","name":"散步","city":"台湾（全境）","country":"台湾","day":1}]}]}
        """.trimIndent())
        val trip = MusicTripCodec.decode(document).single()
        val region = MusicTripCity.country("中国台湾")
        assertEquals(listOf(region, MusicTripCity("台北", "中国台湾")), trip.cities)
        assertEquals(region.name, trip.stops.single().city)
        assertEquals(region.country, trip.stops.single().country)
        assertTrue(libraryJsonEqual(document, MusicTripCodec.encode(listOf(trip), document.getJSONArray("trips"))))
    }
    @Test fun invalidDayAndDateCannotBeSaved() {
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
