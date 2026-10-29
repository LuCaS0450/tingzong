package cn.musicpilgrimage.app

import cn.musicpilgrimage.app.data.*
import org.junit.Assert.*
import org.junit.Test

class MusicMapCatalogueTest {
    private fun scene(id:String,work:String="歌曲甲")=Scene(id,"artist","歌手",work,imageUrl="https://example.org/$id.jpg",imageStatus="approved",verificationStatus="verified",imageWidth=1200,imageHeight=800)
    private fun place(id:String,city:String="巴黎",country:String="法国",vararg scenes:Scene)=Place(id,id,city,country,48.85,2.35,scenes=scenes.toList())
    @Test fun unreviewedAssociationsAndInvalidCoordinatesNeverBecomePins() {
        val catalog=Catalog(listOf(place("valid",scenes=arrayOf(scene("a"))),place("pending",scenes=arrayOf(scene("b").copy(verificationStatus="pending"))),place("bad",scenes=arrayOf(scene("c"))).copy(latitude=Double.NaN)))
        assertEquals(listOf("valid"),musicMapCities(catalog,null).flatMap{it.places}.map{it.id})
    }
    @Test fun selectedWorkKeepsItsOwnEvidenceWhileAllCitiesCanShowOtherWorks() {
        val a=scene("a");val b=scene("b","歌曲乙")
        val catalog=Catalog(listOf(place("shared",scenes=arrayOf(a,b)),place("other",city="东京",country="日本",scenes=arrayOf(b))))
        val work=MusicCatalogue.from(catalog).works.first{it.title=="歌曲甲"}
        val city=musicMapCities(catalog,work).single()
        assertEquals(listOf("a"),city.places.single().scenes.map{it.id})
        assertEquals("a",city.places.single().mapScene(work)?.id)
        assertEquals(2,musicMapCities(catalog,null).size)
    }
    @Test fun identicallyNamedCitiesInDifferentCountriesStaySeparate() {
        val rows=musicMapCities(Catalog(listOf(place("a","同名","甲国",scene("a")),place("b","同名","乙国",scene("b")))),null)
        assertEquals(2,rows.size);assertEquals(2,rows.map{it.key}.distinct().size)
    }
}
