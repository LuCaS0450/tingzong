package cn.musicpilgrimage.app

import cn.musicpilgrimage.app.data.*
import org.junit.Assert.*
import org.junit.Test

class ReleaseArtworkTest {
 private val mv=Scene("mv","jj","林俊杰","她说",imageUrl="https://example.invalid/frame.jpg",imageStatus="approved",
  imageWidth=1280,imageHeight=720,verificationStatus="verified",associationPrecision="building",overlayEligible=false)
 private val cover=ReleaseArtwork("she-says","她说",2010,"album","https://example.invalid/cover.jpg",
  imageStatus="approved",imageWidth=1280,imageHeight=1280)
 private fun place(scene:Scene)=Place("place","三富店","台北","中国台湾",25.0,121.0,scenes=listOf(scene))

 @Test fun mvFrameNeverSubstitutesForMissingDisplayCover() {
  assertNotNull(place(mv).primaryScene())
  assertNull(place(mv).displayScene())
  assertNull(place(mv).displayCover())
  assertNull(place(mv.copy(displayArtwork=cover.copy(imageStatus="pending"))).displayCover())
  assertNull(place(mv.copy(displayArtwork=cover.copy(imageHeight=null))).displayCover())
 }
 @Test fun displayCoverDoesNotChangeAssociationImageOrOverlayPermission() {
  val withCover=mv.copy(displayArtwork=cover)
  assertEquals(cover,place(withCover).displayCover())
  assertEquals(mv.imageUrl,place(withCover).primaryScene()?.imageUrl)
  assertEquals("mv",withCover.kind)
  assertEquals("mv_frame",withCover.referenceKind)
  assertFalse(withCover.canRecreate)
 }
 @Test fun approvedExistingCoverSupportsOlderCatalogWithoutMvFallback() {
  val oldCover=mv.copy(referenceKind="release_cover",kind="album",imageWidth=500,imageHeight=500)
  assertEquals(oldCover.imageUrl,place(oldCover).displayCover()?.imageUrl)
  // An explicit unapproved replacement must not be bypassed by legacy fallback.
  assertNull(place(oldCover.copy(displayArtwork=cover.copy(imageStatus="rejected"))).displayCover())
 }
}
