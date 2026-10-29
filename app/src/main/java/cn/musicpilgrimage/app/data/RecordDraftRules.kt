package cn.musicpilgrimage.app.data

import org.json.JSONObject

/** Only editable content participates in dirty checking. Certification dates are never edited. */
internal object RecordDraftRules {
    fun requiresLocation(kind:String,editing:Boolean,localPhoto:Boolean)=
        (!editing&&kind in listOf("visit","recreation"))||(editing&&kind=="recreation"&&localPhoto)
    fun publiclyVisible(item:ContentItem)=item.visibility=="public"&&!item.hidden&&
        (item.kind!="visit"||(item.raw.stringOrNull("verified_at")!=null&&(item.body.isNotBlank()||item.mediaId!=null)))
    fun hasPhoto(fields:JSONObject,existingMedia:String?) = fields.stringOrNull("local_photo")!=null ||
        (existingMedia!=null&&!fields.optBoolean("clear_media"))
    fun normalize(kind:String,fields:JSONObject,existingMedia:String?):JSONObject = JSONObject(fields.toString()).apply {
        if(kind=="visit"&&optString("body").isBlank()&&!hasPhoto(this,existingMedia))put("visibility","private")
    }
    fun signature(kind:String,fields:JSONObject,existingMedia:String?):String {
        val normalized=normalize(kind,fields,existingMedia)
        return JSONObject().put("body",normalized.optString("body"))
            .put("song",if(kind=="message")normalized.optString("song")else "")
            .put("artist",if(kind=="message")normalized.optString("artist")else "")
            .put("visibility",if(kind in listOf("visit","recreation"))normalized.optString("visibility","private")else "public")
            .put("photo",normalized.stringOrNull("local_photo") ?: if(hasPhoto(normalized,existingMedia))existingMedia else "")
            .toString()
    }
    fun importedPhoto(fields:JSONObject,path:String,thumbnail:String):JSONObject = JSONObject(fields.toString()).apply {
        put("local_photo",path);put("thumbnail",thumbnail);remove("clear_media")
    }
}
