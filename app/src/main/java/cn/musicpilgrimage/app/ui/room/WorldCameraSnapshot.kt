package cn.musicpilgrimage.app.ui.room

import org.json.JSONObject

/** Camera and selection shared by the globe, streets and details within one app session. */
internal data class WorldCameraSnapshot(
    val longitude: Double, val latitude: Double, val zoom: Double,
    val selected: String = "", val summaryOpen: Boolean = false,
    val selectionRingVisible: Boolean = summaryOpen,
) {
    fun json() = JSONObject().put("version", 2).put("longitude", longitude).put("latitude", latitude)
        .put("zoom", zoom).put("selected", selected).put("summaryOpen", summaryOpen)
        .put("selectionRingVisible", selectionRingVisible)

    companion object {
        fun parse(value: JSONObject?): WorldCameraSnapshot? {
            value ?: return null
            val lon = value.optDouble("longitude", Double.NaN)
            val lat = value.optDouble("latitude", Double.NaN)
            val zoom = value.optDouble("zoom", Double.NaN)
            if (!lon.isFinite() || !lat.isFinite() || !zoom.isFinite() ||
                lon !in -180.0..180.0 || lat !in -85.051129..85.051129 || zoom !in -3.0..18.0) return null
            val selected = value.optString("selected").takeIf { it.length <= 180 } ?: return null
            val restoredZoom = if (value.optInt("version", 1) == 1) {
                // The old globe zoom was relative to latitude. Preserve the apparent size on restore.
                WorldMapMath.visualZoom(zoom, lat) - MapboxGlobeZoomOffset * WorldMapMath.globenessForZoom(zoom)
            } else zoom
            val summaryOpen = value.optBoolean("summaryOpen") && selected.isNotBlank()
            val selectionRingVisible = value.optBoolean("selectionRingVisible", summaryOpen) && selected.isNotBlank()
            return WorldCameraSnapshot(lon, lat, restoredZoom, selected, summaryOpen, selectionRingVisible)
        }
    }
}
