package cn.musicpilgrimage.app

import cn.musicpilgrimage.app.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AssociationVisibilityTest {
 private val artists=listOf(Artist("jay","周杰伦"),Artist("jj","林俊杰"))
 private val cover=ReleaseArtwork("cover","专辑",2022,"album","https://example.invalid/cover.jpg",
  imageStatus="approved",imageWidth=500,imageHeight=500)
 private val jay=Scene("jay-scene","jay","周杰伦","失效作品",verificationStatus="pending",
  imageStatus="approved",imageUrl="https://example.invalid/frame.jpg",imageWidth=1280,imageHeight=720,
  displayArtwork=cover,aliases=listOf("失效别名"),associationExcerpt="失效地点线索")
 private val jj=jay.copy(id="jj-scene",artistId="jj",artistName="林俊杰",work="有效作品",
  verificationStatus="verified",aliases=emptyList(),associationExcerpt="")
 private fun place(id:String,vararg scenes:Scene)=Place(id,"共享地点","台北","中国台湾",25.0,121.0,scenes=scenes.toList())

 @Test fun sharedPlaceRetainsOnlyQualifyingArtistAndAllInvalidPlaceDisappears() {
  val raw=Catalog(listOf(place("shared",jay,jj),place("invalid",jay.copy(verificationStatus="rejected"))),artists)
  val visible=raw.publicOnly()
  assertEquals(listOf("shared"),visible.places.map {it.id})
  assertEquals(listOf("jj-scene"),visible.places.single().scenes.map {it.id})
  assertEquals(2,raw.places.single {it.id=="shared"}.scenes.size)
  assertEquals(jj,visible.places.single().primaryScene())
 }
 @Test fun invalidAssociationCannotContributeSearchTermsOrArtistFilterOrCover() {
  val shared=place("shared",jay,jj)
  for(q in listOf("失效作品","失效别名","失效地点线索","周杰伦"))
   assertTrue(q,CatalogSearch.filter(listOf(shared),artists,q).isEmpty())
  assertTrue(CatalogSearch.filter(listOf(shared),artists,"","jay").isEmpty())
  assertEquals(listOf("shared"),CatalogSearch.filter(listOf(shared),artists,"有效作品","jj").map {it.id})
  assertNull(place("invalid",jay).displayCover())
  assertNull(place("invalid",jay).primaryScene())
  assertTrue(CatalogSearch.filter(listOf(place("invalid",jj.copy(imageStatus="rejected"))),artists,"").isEmpty())
 }
 @Test fun jsonParserFiltersLocalAndServerCatalogsWithoutRemovingHistoricalInput() {
  val scene=JSONObject().put("id","s").put("artistId","jay").put("artistName","周杰伦")
   .put("work","世界末日").put("verificationStatus","pending").put("imageStatus","approved")
  val p=JSONObject().put("id","p").put("name","场馆").put("city","桃园").put("country","中国台湾")
   .put("latitude",25.0).put("longitude",121.0).put("scenes",org.json.JSONArray().put(scene))
  val raw=JSONObject().put("artists",org.json.JSONArray().put(JSONObject().put("id","jay").put("name","周杰伦")))
   .put("places",org.json.JSONArray().put(p))
  assertTrue(catalogFromJson(raw).places.isEmpty())
  scene.put("verificationStatus","verified")
  assertEquals("s",catalogFromJson(raw).places.single().scenes.single().id)
  assertEquals(1,p.getJSONArray("scenes").length())
 }
}
