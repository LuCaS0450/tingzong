package cn.musicpilgrimage.app.ui.room

import android.content.Context
import cn.musicpilgrimage.app.data.Place
import cn.musicpilgrimage.app.data.heading
import org.json.JSONObject

/** User-facing copy is separate from research notes and geolocation evidence. */
internal object PlaceEditorialCopy {
    private var values:JSONObject?=null
    @Synchronized private fun row(context:Context,id:String):JSONObject? {
        val all=values?:JSONObject(context.assets.open("design/swiss-details/place-copy.json").bufferedReader().use{it.readText()}).getJSONObject("places").also{values=it}
        return all.optJSONObject(id)
    }
    fun name(context:Context,place:Place)=row(context,place.id)?.optString("name")?.takeIf{it.isNotBlank()}?:place.heading().title
    fun intro(context:Context,place:Place):String = row(context,place.id)?.optString("intro")?.takeIf{it.isNotBlank()}?:run {
        val scene=place.scenes.firstOrNull{it.publiclyVisible}
        if(scene==null)""else when(scene.kind){"lyric"->"《${scene.work}》的歌词提到了${name(context,place)}。";"song_title"->"《${scene.work}》以${name(context,place)}为题。";"album"->"《${scene.work}》的发行封面与这里有关。";else->"《${scene.work}》的影像在这里取景。"}
    }
}
