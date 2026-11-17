package cn.musicpilgrimage.app.data

import java.text.Normalizer
import java.util.Locale
import kotlin.math.*

object CatalogSearch {
    private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT).trim()
    fun filter(places: List<Place>, artists: List<Artist>, query: String, artistId: String? = null): List<Place> {
        val words = normalize(query).split(Regex("\\s+")).filter(String::isNotBlank)
        return Catalog(places, artists).publicOnly().places.filter { p ->
            (artistId.isNullOrEmpty() || p.scenes.any { it.artistId == artistId }) &&
                words.all { word ->
                    val terms = listOf(p.name, p.city, p.country) + p.scenes.flatMap { s ->
                        listOf(s.work, s.artistName, s.associationExcerpt) + s.aliases + (artists.find { it.id == s.artistId }?.aliases ?: emptyList())
                    }
                    terms.any { normalize(it).contains(word) }
                }
        }
    }
}

object ArrivalRules {
    const val locationUpdateMessage = "地点位置资料更新中，暂无法验证到访。已有记录仍可查看。"
    private const val MAX_FIX_AGE_MILLIS = 120_000L

    internal data class Assessment(val distanceMeters: Double?, val rejection: String?)

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 6371008.8 * 2 * atan2(sqrt(a.coerceIn(0.0, 1.0)), sqrt((1 - a).coerceIn(0.0, 1.0)))
    }
    internal fun fixRejection(fix: LocationFix, maxAgeMillis: Long = MAX_FIX_AGE_MILLIS): String? = when {
        !fix.latitude.isFinite() || !fix.longitude.isFinite() || fix.latitude !in -90.0..90.0 || fix.longitude !in -180.0..180.0 -> "定位数据无效，请重新定位"
        fix.isMock -> "不能使用模拟位置进行认证"
        !fix.accuracy.isFinite() || fix.accuracy <= 0 || fix.accuracy > 100 -> "定位精度不足，请到开阔处重新定位"
        fix.ageMillis !in 0..maxAgeMillis -> "定位已过期，请重新定位"
        else -> null
    }

    internal fun assess(place: Place, fix: LocationFix): Assessment {
        val problem = when {
            !place.verified -> "此地点尚待核实，暂不开放到达认证"
            place.arrivalLocationPendingSync -> locationUpdateMessage
            else -> fixRejection(fix)
        }
        if (problem != null) return Assessment(null, problem)
        val distance = distanceMeters(fix.latitude, fix.longitude, place.latitude, place.longitude)
        if (!distance.isFinite()) return Assessment(null, locationUpdateMessage)
        val rejection = if (distance > place.radiusMeters) "还没有到达打卡范围，请到地点附近再试" else null
        return Assessment(distance, rejection)
    }

    fun rejection(place: Place, fix: LocationFix): String? = assess(place, fix).rejection
}
