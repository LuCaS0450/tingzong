package cn.musicpilgrimage.app

import cn.musicpilgrimage.app.data.*
import org.junit.Assert.*
import org.junit.Test

class ArrivalAndSearchTest {
    private val place = Place("paris", "莎玛丽丹百货", "巴黎", "法国", 48.8593, 2.3422, scenes = listOf(Scene("scene", "jay", "周杰伦", "最伟大的作品",verificationStatus="verified",imageStatus="approved")))
    @Test fun validArrivalAndBoundaryErrors() {
        val fix = LocationFix(place.latitude, place.longitude, 15f, 1000, 0)
        assertNull(ArrivalRules.rejection(place, fix))
        assertNotNull(ArrivalRules.rejection(place, fix.copy(ageMillis = 120001)))
        assertNull(ArrivalRules.rejection(place, fix.copy(accuracy = 100f, ageMillis = 120000)))
        assertNotNull(ArrivalRules.rejection(place, fix.copy(accuracy = 100.01f)))
        assertNotNull(ArrivalRules.rejection(place, fix.copy(isMock = true)))
        assertNotNull(ArrivalRules.rejection(place, fix.copy(accuracy = Float.NaN)))
        assertNotNull(ArrivalRules.rejection(place, fix.copy(latitude = place.latitude + .01)))
        assertNotNull(ArrivalRules.rejection(place, fix.copy(latitude = Double.NaN)))
    }
    @Test fun internationalDateLineDoesNotProduceHugeDistance() {
        assertTrue(ArrivalRules.distanceMeters(0.0, 179.9999, 0.0, -179.9999) < 23)
    }
    @Test fun searchesNamesAliasesWorksAndCitiesWithArtistFilter() {
        val artists = listOf(Artist("jay", "周杰伦", listOf("周杰倫", "Jay Chou")))
        listOf("周杰伦", "周杰倫", "JAY CHOU", "最伟大", "巴黎", "法国", "周杰伦 巴黎").forEach {
            assertEquals(it, 1, CatalogSearch.filter(listOf(place), artists, it).size)
        }
        assertTrue(CatalogSearch.filter(listOf(place), artists, "巴黎", "eason").isEmpty())
        assertTrue(CatalogSearch.filter(listOf(place), artists, "不存在的城市").isEmpty())
    }
    @Test fun certifiedRadiusUsesMetersAndRejectsOutside() {
        val fix = LocationFix(place.latitude + .001, place.longitude, 15f, 1000, 0)
        assertNull(ArrivalRules.rejection(place, fix))
        assertNotNull(ArrivalRules.rejection(place.copy(radiusMeters = 100), fix))
    }
    @Test fun pendingPlacesCannotBeFoundOrCertified() {
        val pending=place.copy(verified=false)
        assertTrue(CatalogSearch.filter(listOf(pending),emptyList(),"").isEmpty())
        assertNotNull(ArrivalRules.rejection(pending,LocationFix(place.latitude,place.longitude,10f,1000,0)))
    }
    @Test fun coverTakesPriorityOnlyAfterApprovalWithValidDimensions() {
        val mv=place.scenes[0].copy(imageUrl="https://example.invalid/mv.jpg",imageStatus="approved",imageWidth=1280,imageHeight=720)
        val cover=mv.copy(id="cover",kind="album",referenceKind="release_cover",imageUrl="https://example.invalid/cover.jpg",imageWidth=500,imageHeight=500)
        assertEquals("cover",place.copy(scenes=listOf(mv,cover)).primaryScene()?.id)
        assertEquals("scene",place.copy(scenes=listOf(mv,cover.copy(imageStatus="pending"))).primaryScene()?.id)
        assertEquals("scene",place.copy(scenes=listOf(mv,cover.copy(imageHeight=null))).primaryScene()?.id)
    }
    @Test fun overlayRejectsRegionLyricPendingAndMissingOriginals() {
        val valid=place.scenes[0].copy(imageUrl="https://example.invalid/real.jpg",imageStatus="approved",imageWidth=1280,imageHeight=720,verificationStatus="verified",overlayEligible=true,associationPrecision="landmark")
        assertTrue(valid.canRecreate)
        assertFalse(valid.copy(associationPrecision="city").canRecreate)
        assertFalse(valid.copy(associationPrecision="region").canRecreate)
        assertFalse(valid.copy(kind="song_title").canRecreate)
        assertFalse(valid.copy(kind="lyric").canRecreate)
        assertFalse(valid.copy(verificationStatus="pending").canRecreate)
        assertFalse(valid.copy(imageStatus="missing").canRecreate)
        assertFalse(valid.copy(imageUrl=null).canRecreate)
    }
    @Test fun fifthArtistTitleAliasAndLyricAreSearchable() {
        val kay=Artist("kay","谢安琪",listOf("謝安琪","Kay Tse"))
        val title=Scene("kay-title","kay","谢安琪","囍帖街",kind="song_title",aliases=listOf("喜帖街","利东街"),verificationStatus="verified",imageStatus="approved")
        val street=place.copy(scenes=listOf(title))
        assertEquals(1,CatalogSearch.filter(listOf(street),listOf(kay),"喜帖街","kay").size)
        assertEquals(1,CatalogSearch.filter(listOf(street),listOf(kay),"谢安琪","kay").size)
        val lyric=place.scenes[0].copy(kind="lyric",associationExcerpt="我占据 格林威治 守候着你")
        assertEquals(1,CatalogSearch.filter(listOf(place.copy(scenes=listOf(lyric))),emptyList(),"格林威治").size)
    }
}
