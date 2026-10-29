package cn.musicpilgrimage.app

import cn.musicpilgrimage.app.data.*
import org.junit.Assert.*
import org.junit.Test

class MusicCatalogueTest {
    private val art=ReleaseArtwork("release-a","共同专辑",2020,"album","https://example.org/cover.jpg",imageStatus="approved",imageWidth=1200,imageHeight=800)
    private fun scene(id:String,work:String,verified:String="verified",kind:String="mv",cover:ReleaseArtwork=art)=
        Scene(id,"artist","歌手",work,kind=kind,imageUrl="https://example.org/$id.jpg",imageStatus="approved",verificationStatus=verified,imageWidth=1280,imageHeight=720,displayArtwork=cover)
    private fun place(id:String,vararg scenes:Scene)=Place(id,id,"城市","国家",1.0,2.0,scenes=scenes.toList())

    @Test fun releaseGroupingDeduplicatesSongsWithoutLosingPlaceEvidence() {
        val catalog=MusicCatalogue.from(Catalog(listOf(place("a",scene("1","歌曲甲"),scene("2","歌曲甲",kind="lyric")),place("b",scene("3","歌曲甲"),scene("4","歌曲乙")))))
        assertEquals(1,catalog.releases.size)
        assertEquals(listOf("歌曲甲","歌曲乙"),catalog.releases.single().works.map{it.title})
        val work=catalog.works.first()
        assertEquals(listOf("a","b"),work.places.map{it.place.id})
        assertEquals(2,work.places.first().scenes.size)
        assertNull(catalog.releases.single().directWork)
    }
    @Test fun pendingEvidenceCannotAppearThroughReleaseGroupingOrSearch() {
        val catalog=MusicCatalogue.from(Catalog(listOf(place("a",scene("1","隐藏歌曲","pending")),place("b",scene("2","可见歌曲")))))
        assertEquals(listOf("可见歌曲"),catalog.works.map{it.title})
        assertTrue(catalog.search("隐藏").isEmpty())
    }
    @Test fun albumCoverDoesNotInventASongAndSingleOpensItsOnlyWork() {
        val coverOnly=scene("cover","共同专辑",kind="album").copy(referenceKind="release_cover")
        val singleArt=art.copy(id="single",releaseTitle="单曲",releaseType="single")
        val catalog=MusicCatalogue.from(Catalog(listOf(place("a",coverOnly),place("b",scene("single-scene","单曲",cover=singleArt)))))
        assertTrue(catalog.releases.first().works.isEmpty())
        assertEquals("单曲",catalog.releases.last().directWork?.title)
    }
    @Test fun searchMatchesArtistAliasesAndAlbumButDoesNotReturnPlaceNamesAsSongs() {
        val catalog=MusicCatalogue.from(Catalog(listOf(place("somewhere",scene("1","曲名"))),listOf(Artist("artist","歌手",listOf("Singer")))))
        assertEquals(1,catalog.search("ＳＩＮＧＥＲ").size)
        assertEquals(1,catalog.search("共同专辑").size)
        assertTrue(catalog.search("somewhere").isEmpty())
    }
}
