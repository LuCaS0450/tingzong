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
import kotlin.math.cos
import kotlin.math.sin

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
                .put("cluster", true).put("clusterRadius", 32).put("clusterMaxZoom", 17).put("clusterMinPoints", 2)
                .put(
                    "clusterProperties",
                    JSONObject()
                        .put("nxSum", JSONArray(listOf("+", JSONArray(listOf("get", "nx")))))
                        .put("nySum", JSONArray(listOf("+", JSONArray(listOf("get", "ny")))))
                        .put("nzSum", JSONArray(listOf("+", JSONArray(listOf("get", "nz"))))),
                ),
        )
        sources.put(SELECTION, JSONObject().put("type", "geojson").put("data", empty))
    }

    fun appendLayers(target: JSONArray) {
        val clusterFlag = JSONArray(listOf("has", "point_count"))
        val visited = JSONArray(listOf("get", "visited"))
        fun pinImage(prefix: String) = JSONArray(listOf("case", visited, "$prefix-visited", "$prefix-pin"))
        fun zoomChoice(threshold: Double, near: Any, far: Any) =
            JSONArray(listOf("step", JSONArray(listOf("zoom")), near, threshold, far))
        fun instantOpacityPaint(initial: Double = 1.0, includeText: Boolean = false) = JSONObject().apply {
            put("icon-opacity", initial)
            put("icon-opacity-transition", JSONObject().put("duration", 0))
            if (includeText) {
                put("text-opacity", initial)
                put("text-opacity-transition", JSONObject().put("duration", 0))
            }
        }

        target.put(
            layer(SELECTION_RING_LAYER, SELECTION, SELECTED_RING).put(
                "paint", instantOpacityPaint(0.0),
            ),
        )

        // Tangent-plane ground contact shadows projected onto the curved 3D globe surface.
        val placeShadows = shadowLayer(PLACES_SHADOW_LAYER, PLACES, GLOBE_PIN_SHADOW)
        placeShadows.put("filter", JSONArray(listOf("!", clusterFlag)))
        placeShadows.put("paint", instantOpacityPaint(0.95))
        target.put(placeShadows)

        val clusterShadows = shadowLayer(CLUSTER_SHADOW_LAYER, PLACES, GLOBE_CLUSTER_SHADOW)
        clusterShadows.put("filter", clusterFlag)
        clusterShadows.put("paint", instantOpacityPaint(0.95))
        target.put(clusterShadows)

        val places = layer(PLACES_LAYER, PLACES, zoomChoice(5.0, pinImage("globe"), pinImage("street")))
        places.put("filter", JSONArray(listOf("!", clusterFlag)))
        places.put("paint", instantOpacityPaint(1.0))
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
        clusters.put("paint", instantOpacityPaint(1.0, includeText = true).put("text-color", "#242b27"))
        target.put(clusters)

        val selectedShadow = shadowLayer(SELECTION_SHADOW_LAYER, SELECTION, SELECTED_PIN_SHADOW)
        selectedShadow.put("paint", instantOpacityPaint(0.95))
        target.put(selectedShadow)

        target.put(layer(SELECTION_LAYER, SELECTION, SELECTED_PIN).put("paint", instantOpacityPaint(1.0)))
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
        .put("maxzoom", 5.5)
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

    /**
     * Drives subtle spherical depth cues for pins on the 3D globe:
     * pins near the sphere limb gently scale down and soften into the Fresnel haze,
     * while tangent-plane ground shadows fade smoothly as the map flattens into street zoom.
     */
    fun updateGlobeDepth(style: Style, cameraLat: Double, cameraLon: Double, zoom: Double) {
        val globeWeight = ((4.2 - zoom) / 1.8).coerceIn(0.0, 1.0)
        if (globeWeight <= 0.001) {
            val one = Value.valueOf(1.0)
            val zero = Value.valueOf(0.0)
            style.setStyleLayerProperty(PLACES_LAYER, "icon-size", one)
            style.setStyleLayerProperty(PLACES_LAYER, "icon-opacity", one)
            style.setStyleLayerProperty(CLUSTER_LAYER, "icon-size", one)
            style.setStyleLayerProperty(CLUSTER_LAYER, "icon-opacity", one)
            style.setStyleLayerProperty(CLUSTER_LAYER, "text-opacity", one)
            style.setStyleLayerProperty(SELECTION_LAYER, "icon-size", one)
            style.setStyleLayerProperty(SELECTION_LAYER, "icon-opacity", one)
            style.setStyleLayerProperty(PLACES_SHADOW_LAYER, "icon-opacity", zero)
            style.setStyleLayerProperty(CLUSTER_SHADOW_LAYER, "icon-opacity", zero)
            style.setStyleLayerProperty(SELECTION_SHADOW_LAYER, "icon-opacity", zero)
            return
        }

        val latRad = Math.toRadians(cameraLat)
        val lonRad = Math.toRadians(cameraLon)
        val cosLat = cos(latRad)
        val cx = cosLat * cos(lonRad)
        val cy = cosLat * sin(lonRad)
        val cz = sin(latRad)

        val pointDot = clampedDotExpr(
            expr("+", expr("*", expr("get", "nx"), cx), expr("*", expr("get", "ny"), cy), expr("*", expr("get", "nz"), cz)),
        )
        val clusterDot = clampedDotExpr(
            expr(
                "/",
                expr("+", expr("*", expr("get", "nxSum"), cx), expr("*", expr("get", "nySum"), cy), expr("*", expr("get", "nzSum"), cz)),
                expr("max", 1.0, expr("get", "point_count")),
            ),
        )

        val placeSize = interpLinear(
            pointDot,
            0.0 to (1.0 - 0.24 * globeWeight),
            0.35 to (1.0 - 0.07 * globeWeight),
            0.85 to (1.0 + 0.05 * globeWeight),
        )
        val clusterSize = interpLinear(
            clusterDot,
            0.0 to (1.0 - 0.24 * globeWeight),
            0.35 to (1.0 - 0.07 * globeWeight),
            0.85 to (1.0 + 0.05 * globeWeight),
        )
        val selectedSize = interpLinear(
            pointDot,
            0.0 to (1.0 - 0.12 * globeWeight),
            0.40 to (1.0 - 0.02 * globeWeight),
            0.85 to (1.0 + 0.05 * globeWeight),
        )

        val placeOpacity = interpLinear(
            pointDot,
            0.0 to (1.0 - 0.16 * globeWeight),
            0.35 to (1.0 - 0.04 * globeWeight),
            0.75 to 1.0,
        )
        val clusterOpacity = interpLinear(
            clusterDot,
            0.0 to (1.0 - 0.16 * globeWeight),
            0.35 to (1.0 - 0.04 * globeWeight),
            0.75 to 1.0,
        )
        val selectedOpacity = interpLinear(
            pointDot,
            0.0 to (1.0 - 0.08 * globeWeight),
            0.40 to 1.0,
        )

        val placeShadowOpacity = interpLinear(
            pointDot,
            0.0 to (0.28 * globeWeight),
            0.30 to (0.82 * globeWeight),
            0.75 to (0.96 * globeWeight),
        )
        val clusterShadowOpacity = interpLinear(
            clusterDot,
            0.0 to (0.28 * globeWeight),
            0.30 to (0.82 * globeWeight),
            0.75 to (0.96 * globeWeight),
        )
        val selectedShadowOpacity = interpLinear(
            pointDot,
            0.0 to (0.36 * globeWeight),
            0.35 to (0.88 * globeWeight),
            0.75 to (0.98 * globeWeight),
        )

        style.setStyleLayerProperty(PLACES_LAYER, "icon-size", placeSize)
        style.setStyleLayerProperty(PLACES_LAYER, "icon-opacity", placeOpacity)
        style.setStyleLayerProperty(CLUSTER_LAYER, "icon-size", clusterSize)
        style.setStyleLayerProperty(CLUSTER_LAYER, "icon-opacity", clusterOpacity)
        style.setStyleLayerProperty(CLUSTER_LAYER, "text-opacity", clusterOpacity)
        style.setStyleLayerProperty(SELECTION_LAYER, "icon-size", selectedSize)
        style.setStyleLayerProperty(SELECTION_LAYER, "icon-opacity", selectedOpacity)
        style.setStyleLayerProperty(PLACES_SHADOW_LAYER, "icon-opacity", placeShadowOpacity)
        style.setStyleLayerProperty(CLUSTER_SHADOW_LAYER, "icon-opacity", clusterShadowOpacity)
        style.setStyleLayerProperty(SELECTION_SHADOW_LAYER, "icon-opacity", selectedShadowOpacity)
    }

    private fun clampedDotExpr(dot: Value): Value = expr("max", 0.0, expr("min", 1.0, dot))

    private fun interpLinear(input: Value, vararg stops: Pair<Double, Double>): Value {
        val args = ArrayList<Value>(3 + stops.size * 2)
        args.add(Value.valueOf("interpolate"))
        args.add(expr("linear"))
        args.add(input)
        for ((x, y) in stops) {
            args.add(Value.valueOf(x))
            args.add(Value.valueOf(y))
        }
        return Value.valueOf(args)
    }

    private fun expr(vararg items: Any): Value = Value.valueOf(
        items.map { item ->
            when (item) {
                is Value -> item
                is String -> Value.valueOf(item)
                is Double -> Value.valueOf(item)
                is Float -> Value.valueOf(item.toDouble())
                is Int -> Value.valueOf(item.toDouble())
                is Long -> Value.valueOf(item.toDouble())
                else -> Value.valueOf(item.toString())
            }
        },
    )

    fun updateData(style: Style, entries: List<WorldPlaceEntry>, selectedId: String, visits: Set<String>) {
        val places = ArrayList<Feature>(entries.size)
        var selected: Feature? = null
        for (entry in entries) {
            val place = entry.place
            val feature = Feature.fromGeometry(Point.fromLngLat(place.longitude, place.latitude), null, place.id)
            val latRad = Math.toRadians(place.latitude)
            val lonRad = Math.toRadians(place.longitude)
            val cosLat = cos(latRad)
            feature.addStringProperty("id", place.id)
            feature.addStringProperty("name", entry.name)
            feature.addBooleanProperty("visited", place.id in visits)
            feature.addNumberProperty("nx", cosLat * cos(lonRad))
            feature.addNumberProperty("ny", cosLat * sin(lonRad))
            feature.addNumberProperty("nz", sin(latRad))
            if (place.id == selectedId) selected = feature else places.add(feature)
        }
        style.getSourceAs<GeoJsonSource>(PLACES)?.featureCollection(FeatureCollection.fromFeatures(places))
        style.getSourceAs<GeoJsonSource>(SELECTION)?.featureCollection(FeatureCollection.fromFeatures(listOfNotNull(selected)))
    }
}
