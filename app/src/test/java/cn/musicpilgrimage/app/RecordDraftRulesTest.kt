package cn.musicpilgrimage.app

import cn.musicpilgrimage.app.data.RecordDraftRules
import cn.musicpilgrimage.app.data.ContentItem
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RecordDraftRulesTest {
    @Test fun replacingRecreationPhotoRequiresFreshLocationButTextEditsDoNot() {
        assertTrue(RecordDraftRules.requiresLocation("visit",false,false))
        assertTrue(RecordDraftRules.requiresLocation("recreation",true,true))
        assertFalse(RecordDraftRules.requiresLocation("recreation",true,false))
        assertFalse(RecordDraftRules.requiresLocation("visit",true,true))
        assertFalse(RecordDraftRules.requiresLocation("message",false,false))
    }
    @Test fun publicFeedsExcludePrivateHiddenAndCertificationOnlyVisits() {
        val raw=JSONObject().put("kind","visit").put("visibility","public").put("body","有感想").put("verified_at","2026-10-18")
        assertTrue(RecordDraftRules.publiclyVisible(ContentItem(raw)))
        assertFalse(RecordDraftRules.publiclyVisible(ContentItem(JSONObject(raw.toString()).put("is_hidden",true))))
        assertFalse(RecordDraftRules.publiclyVisible(ContentItem(JSONObject(raw.toString()).put("visibility","private"))))
        assertFalse(RecordDraftRules.publiclyVisible(ContentItem(JSONObject(raw.toString()).put("body",""))))
        assertFalse(RecordDraftRules.publiclyVisible(ContentItem(JSONObject(raw.toString()).put("verified_at",JSONObject.NULL))))
    }
    @Test fun certificationAndRequestMetadataDoNotCreateUnsavedChanges() {
        val initial=JSONObject().put("body","已有文字").put("visibility","private").put("verified_at","2026-10-18")
        val restored=JSONObject(initial.toString()).put("request_id","new-request").put("id","record-id").put("thumbnail","internal-cache").put("version",3)
        assertEquals(RecordDraftRules.signature("visit",initial,null),RecordDraftRules.signature("visit",restored,null))
        restored.put("body","新文字")
        assertNotEquals(RecordDraftRules.signature("visit",initial,null),RecordDraftRules.signature("visit",restored,null))
    }
    @Test fun emptyVisitRemainsPrivateAndDoesNotAlterCertification() {
        val draft=JSONObject().put("body","  ").put("visibility","public").put("verified_at","2026-10-18T10:00:00Z")
        val normalized=RecordDraftRules.normalize("visit",draft,null)
        assertEquals("private",normalized.getString("visibility"))
        assertEquals("2026-10-18T10:00:00Z",normalized.getString("verified_at"))
        assertEquals("public",draft.getString("visibility"))
    }
    @Test fun removingLastPhotoForcesEmptyVisitPrivateButKeepsOriginalMediaUntouched() {
        val draft=JSONObject().put("body","").put("visibility","public")
        assertEquals("public",RecordDraftRules.normalize("visit",draft,"original-media").getString("visibility"))
        draft.put("clear_media",true)
        assertEquals("private",RecordDraftRules.normalize("visit",draft,"original-media").getString("visibility"))
    }
    @Test fun selectingAfterRemovalClearsTheRemovalFlagAndKeepsTheSourceSnapshot() {
        val removed=JSONObject().put("clear_media",true).put("body","保留")
        val imported=RecordDraftRules.importedPhoto(removed,"new-photo","new-thumb")
        assertFalse(imported.has("clear_media"));assertTrue(removed.getBoolean("clear_media"))
        assertTrue(RecordDraftRules.hasPhoto(imported,"old-media"))
    }
    @Test fun addingThenRemovingANewPhotoReturnsToCleanEmptyDraft() {
        val initial=JSONObject()
        val removed=JSONObject().put("clear_media",true)
        assertEquals(RecordDraftRules.signature("visit",initial,null),RecordDraftRules.signature("visit",removed,null))
        assertNotEquals(RecordDraftRules.signature("visit",initial,"old-media"),RecordDraftRules.signature("visit",removed,"old-media"))
    }
    @Test fun aMessageTracksSongArtistAndBodyWithoutChangingTheCaller() {
        val before=JSONObject().put("body","旧留言").put("song","作品")
        val after=JSONObject(before.toString()).put("artist","周杰伦")
        assertNotEquals(RecordDraftRules.signature("message",before,null),RecordDraftRules.signature("message",after,null))
        assertFalse(before.has("artist"))
    }
}
