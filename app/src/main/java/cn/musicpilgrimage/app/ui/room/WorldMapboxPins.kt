package cn.musicpilgrimage.app.ui.room

import android.graphics.Bitmap
import com.mapbox.bindgen.Value
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.Point
import com.mapbox.maps.Style
import com.mapbox.maps.extension.style.image.addImage
import com.mapbox.maps.extension.style.image.image
import com.mapbox.maps.extension.style.sources.generated.GeoJsonSource
import com.mapbox.maps.extension.style.sources.getSourceAs
import org.json.JSONArray
import org.json.JSONObject

internal object WorldMapboxPins {
    const val PLACES = "world-places-source"
    const val SELECTION = "world-selection-source"
    const val PLACES_SHADOW_LAYER = "world-place-pin-shadows"
    const val CLUSTER_SHADOW_LAYER = "world-cluster-pin-shadows"
    const val SELECTION_SHADOW_LAYER = "world-selected-pin-shadow"
    const val PLACES_LAYER = "world-place-pins"
    const val CLUSTER_LAYER = "world-cluster-pins"
    const val SELECTION_LAYER = "world-selected-pin"
    const val SELECTION_RING_LAYER = "world-selected-pin-ring"
    private const val GLOBE_PIN_SHADOW = "globe-pin-shadow"
    private const val GLOBE_CLUSTER_SHADOW = "globe-cluster-shadow"
    private const val SELECTED_PIN_SHADOW = "selected-pin-shadow"
    private const val SELECTED_PIN = "selected-pin"
    private const val SELECTED_RING = "selected-pin-ring"
    val layers = listOf(SELECTION_LAYER, PLACES_LAYER, CLUSTER_LAYER)

    fun appendSources(sources: JSONObject) {
        val empty = JSONObject().put("type", "FeatureCollection").put("features", JSONArray())
        sources.put(
            PLACES,
            JSONObject().put("type", "geojson").put("data", empty)
                .put("cluster", true).put("clusterRadius", 32).put("clusterMaxZoom", 17).put("clusterMinPoints", 2),
        )
        sources.put(SELECTION, JSONObject().put("type", "geojson").put("data", empty))
    }

    fun appendLayers(target: JSONArray) {
        val clusterFlag = JSONArray(listOf("has", "point_count"))
        val visited = JSONArray(listOf("get", "visited"))
        fun pinImage(prefix: String) = JSONArray(listOf("case", visited, "$prefix-visited", "$prefix-pin"))
        fun zoomChoice(threshold: Double, near: Any, far: Any) =
            JSONArray(listOf("step", JSONArray(listOf("zoom")), near, threshold, far))
        fun shadowOpacity(peak: Double) = JSONArray(
            listOf("interpolate", JSONArray(listOf("linear")), JSONArray(listOf("zoom")), 2.2, peak, 4.0, 0.0),
        )

        target.put(
            layer(SELECTION_RING_LAYER, SELECTION, SELECTED_RING).put(
                "paint", JSONObject().put("icon-opacity", 0.0)
                    .put("icon-opacity-transition", JSONObject().put("duration", 0)),
            ),
        )

        // Tangent-plane ground contact shadows projected natively by the GPU onto the 3D globe surface.
        val placeShadows = shadowLayer(PLACES_SHADOW_LAYER, PLACES, GLOBE_PIN_SHADOW)
        placeShadows.put("filter", JSONArray(listOf("!", clusterFlag)))
        placeShadows.put("paint", JSONObject().put("icon-opacity", shadowOpacity(0.92)))
        target.put(placeShadows)

        val clusterShadows = shadowLayer(CLUSTER_SHADOW_LAYER, PLACES, GLOBE_CLUSTER_SHADOW)
        clusterShadows.put("filter", clusterFlag)
        clusterShadows.put("paint", JSONObject().put("icon-opacity", shadowOpacity(0.92)))
        target.put(clusterShadows)

        val places = layer(PLACES_LAYER, PLACES, zoomChoice(5.0, pinImage("globe"), pinImage("street")))
        places.put("filter", JSONArray(listOf("!", clusterFlag)))
        target.put(places)

        val clusters = layer(CLUSTER_LAYER, PLACES, zoomChoice(2.5, "globe-cluster", "street-cluster"))
        clusters.put("filter", clusterFlag)
        clusters.getJSONObject("layout").apply {
            put("text-field", zoomChoice(2.5, "", JSONArray(listOf("to-string", JSONArray(listOf("get", "point_count"))))))
            put("text-size", 8.5)
            put("text-font", JSONArray(listOf("DIN Pro Medium")))
            put("text-offset", JSONArray(listOf(0, -0.64)))
            put("text-allow-overlap", true)
            put("text-ignore-placement", true)
            put("text-optional", true)
        }
        clusters.put("paint", JSONObject().put("text-color", "#242b27"))
        target.put(clusters)

        val selectedShadow = shadowLayer(SELECTION_SHADOW_LAYER, SELECTION, SELECTED_PIN_SHADOW)
        selectedShadow.put("paint", JSONObject().put("icon-opacity", shadowOpacity(0.96)))
        target.put(selectedShadow)

        target.put(layer(SELECTION_LAYER, SELECTION, SELECTED_PIN))
    }

    private fun layer(id: String, source: String, sprite: Any) = JSONObject()
        .put("id", id).put("type", "symbol").put("source", source)
        .put(
            "layout",
            JSONObject().put("icon-image", sprite)
                .put("icon-anchor", "center").put("icon-pitch-alignment", "viewport")
                .put("icon-rotation-alignment", "viewport")
                .put("icon-allow-overlap", true).put("icon-ignore-placement", true),
        )

    private fun shadowLayer(id: String, source: String, sprite: String) = JSONObject()
        .put("id", id).put("type", "symbol").put("source", source)
        .put("maxzoom", 4.5)
        .put(
            "layout",
            JSONObject().put("icon-image", sprite)
                .put("icon-anchor", "center").put("icon-pitch-alignment", "map")
                .put("icon-rotation-alignment", "map")
                .put("icon-allow-overlap", true).put("icon-ignore-placement", true),
        )

    fun createArtwork(density: Float): Map<String, Bitmap> = buildMap {
        for ((prefix, street) in listOf("globe" to false, "street" to true)) {
            put("$prefix-pin", renderWorldPinArtwork(density, street))
            put("$prefix-visited", renderWorldPinArtwork(density, street, visited = true))
            put("$prefix-cluster", renderWorldPinArtwork(density, street, cluster = true))
        }
        put(GLOBE_PIN_SHADOW, renderWorldPinContactShadow(density))
        put(GLOBE_CLUSTER_SHADOW, renderWorldPinContactShadow(density, cluster = true))
        put(SELECTED_PIN_SHADOW, renderWorldPinContactShadow(density, selected = true))
        put(SELECTED_PIN, renderWorldPinArtwork(density, snapToStreet = true, selected = true))
        put(SELECTED_RING, renderWorldSelectionRing(density))
    }

    fun installArtwork(style: Style, artwork: Map<String, Bitmap>, density: Float) {
        for ((name, bitmap) in artwork) style.addImage(image(name, bitmap) { scale(density) })
    }

    fun setSelectionRingVisible(style: Style, visible: Boolean) {
        style.setStyleLayerProperty(SELECTION_RING_LAYER, "icon-opacity", Value.valueOf(if (visible) 1.0 else 0.0))
    }

    fun updateData(style: Style, entries: List<WorldPlaceEntry>, selectedId: String, visits: Set<String>) {
        val places = ArrayList<Feature>(entries.size)
        var selected: Feature? = null
        for (entry in entries) {
            val place = entry.place
            val feature = Feature.fromGeometry(Point.fromLngLat(place.longitude, place.latitude), null, place.id)
            feature.addStringProperty("id", place.id)
            feature.addStringProperty("name", entry.name)
            feature.addBooleanProperty("visited", place.id in visits)
            if (place.id == selectedId) selected = feature else places.add(feature)
        }
        style.getSourceAs<GeoJsonSource>(PLACES)?.featureCollection(FeatureCollection.fromFeatures(places))
        style.getSourceAs<GeoJsonSource>(SELECTION)?.featureCollection(FeatureCollection.fromFeatures(listOfNotNull(selected)))
    }
}
