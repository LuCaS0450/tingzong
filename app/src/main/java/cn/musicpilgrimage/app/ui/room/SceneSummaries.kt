package cn.musicpilgrimage.app.ui.room

import android.content.Context
import cn.musicpilgrimage.app.data.Scene
import org.json.JSONObject

/** Curated display copy keyed by stable scene IDs; original evidence is never overwritten. */
internal object SceneSummaries {
 private var copy:JSONObject?=null
 @Synchronized fun forScene(context:Context,scene:Scene):String {
  val values=copy ?: context.assets.open("design/music-space/scene-summaries.json").bufferedReader().use{JSONObject(it.readText())}.also{copy=it}
  return values.optString(scene.id).ifBlank{scene.note}
 }
}
