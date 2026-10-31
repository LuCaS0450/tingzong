package cn.musicpilgrimage.app.ui.room

import org.json.JSONObject

/** One camera and selection across globe, arbitrary streets, details and process recreation. */
internal data class WorldCameraSnapshot(
    val longitude: Double, val latitude: Double, val zoom: Double,
    val selected: String = "", val summaryOpen: Boolean = false,
) {
    fun json() = JSONObject().put("version", 1).put("longitude", longitude).put("latitude", latitude)
        .put("zoom", zoom).put("selected", selected).put("summaryOpen", summaryOpen)

    companion object {
        fun parse(value: JSONObject?): WorldCameraSnapshot? {
            value ?: return null
            val lon = value.optDouble("longitude", Double.NaN)
            val lat = value.optDouble("latitude", Double.NaN)
            val zoom = value.optDouble("zoom", Double.NaN)
            if (!lon.isFinite() || !lat.isFinite() || !zoom.isFinite() ||
                lon !in -180.0..180.0 || lat !in -85.051129..85.051129 || zoom !in -3.0..18.0) return null
            val selected = value.optString("selected").takeIf { it.length <= 180 } ?: return null
            return WorldCameraSnapshot(lon, lat, zoom, selected, value.optBoolean("summaryOpen") && selected.isNotBlank())
        }
    }
}
