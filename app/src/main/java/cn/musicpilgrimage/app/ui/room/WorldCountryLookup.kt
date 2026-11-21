package cn.musicpilgrimage.app.ui.room

import android.content.Context
import org.json.JSONObject

internal data class CountryArea(
    val minLon: Double,
    val minLat: Double,
    val maxLon: Double,
    val maxLat: Double,
    val wrap: Boolean,
    val rings: List<DoubleArray>,
)

internal data class CountryPolygon(
    val name: String,
    val areas: List<CountryArea>,
)

/** Offline Natural Earth polygon lookup driving the World header region breadcrumb. */
internal object WorldCountryLookup {
    @Volatile
    private var cached: List<CountryPolygon>? = null

    fun load(context: Context): List<CountryPolygon> {
        cached?.let { return it }
        val parsed = runCatching {
            context.applicationContext.assets.open("design/swiss-world/countries.json").bufferedReader().use {
                parseCountries(it.readText())
            }
        }.getOrDefault(emptyList())
        if (parsed.isNotEmpty()) cached = parsed
        return parsed
    }

    internal fun parseCountries(jsonText: String): List<CountryPolygon> {
        val root = JSONObject(jsonText)
        val array = root.optJSONArray("countries") ?: return emptyList()
        return buildList(array.length()) {
            for (cIndex in 0 until array.length()) {
                val cObj = array.optJSONObject(cIndex) ?: continue
                val name = cObj.optString("name").trim()
                if (name.isEmpty()) continue
                val areasArr = cObj.optJSONArray("areas") ?: continue
                val areas = buildList(areasArr.length()) {
                    for (aIndex in 0 until areasArr.length()) {
                        val aObj = areasArr.optJSONObject(aIndex) ?: continue
                        val bounds = aObj.optJSONArray("bounds") ?: continue
                        if (bounds.length() < 4) continue
                        val ringsArr = aObj.optJSONArray("rings") ?: continue
                        val rings = buildList(ringsArr.length()) {
                            for (rIndex in 0 until ringsArr.length()) {
                                val rArr = ringsArr.optJSONArray(rIndex) ?: continue
                                val coords = DoubleArray(rArr.length() * 2)
                                for (pIndex in 0 until rArr.length()) {
                                    val pt = rArr.optJSONArray(pIndex) ?: continue
                                    coords[pIndex * 2] = pt.optDouble(0, 0.0)
                                    coords[pIndex * 2 + 1] = pt.optDouble(1, 0.0)
                                }
                                if (coords.size >= 6) add(coords)
                            }
                        }
                        if (rings.isNotEmpty()) {
                            add(
                                CountryArea(
                                    minLon = bounds.optDouble(0, 0.0),
                                    minLat = bounds.optDouble(1, 0.0),
                                    maxLon = bounds.optDouble(2, 0.0),
                                    maxLat = bounds.optDouble(3, 0.0),
                                    wrap = aObj.optBoolean("wrap", false),
                                    rings = rings,
                                )
                            )
                        }
                    }
                }
                if (areas.isNotEmpty()) add(CountryPolygon(name, areas))
            }
        }
    }

    fun countryAt(countries: List<CountryPolygon>, longitude: Double, latitude: Double): String {
        for (country in countries) {
            for (area in country.areas) {
                val x = if (area.wrap && longitude < 0.0) longitude + 360.0 else longitude
                if (x < area.minLon || x > area.maxLon || latitude < area.minLat || latitude > area.maxLat) continue
                if (pointInRing(area.rings[0], x, latitude) &&
                    (1 until area.rings.size).none { pointInRing(area.rings[it], x, latitude) }
                ) {
                    return country.name
                }
            }
        }
        return ""
    }

    private fun pointInRing(ring: DoubleArray, x: Double, y: Double): Boolean {
        val count = ring.size / 2
        if (count < 3) return false
        var hit = false
        var j = count - 1
        for (i in 0 until count) {
            val ax = ring[i * 2]
            val ay = ring[i * 2 + 1]
            val bx = ring[j * 2]
            val by = ring[j * 2 + 1]
            if ((ay > y) != (by > y) && x < (bx - ax) * (y - ay) / (by - ay) + ax) {
                hit = !hit
            }
            j = i
        }
        return hit
    }
}
