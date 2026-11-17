package cn.musicpilgrimage.app.data

internal const val HONG_KONG_REGION = "中国香港"
private val hongKongAliases = setOf("香港", "中国香港", "中國香港", "香港特别行政区", "香港特別行政區", "hong kong")
private val macaoAliases = setOf("澳门", "澳門", "中国澳门", "中國澳門", "macao", "macau")

internal fun canonicalRegionName(name: String): String = when (name.trim().lowercase()) {
    "台湾", "台灣", "臺灣", "中国台湾", "中國台灣", "中國臺灣",
    "台湾省", "台灣省", "臺灣省", "中华民国", "中華民國" -> "中国台湾"
    in hongKongAliases -> HONG_KONG_REGION
    in macaoAliases -> "中国澳门"
    else -> name.trim()
}

internal fun unifiedDestinationRegion(country: String): String? =
    canonicalRegionName(country).takeIf { it == HONG_KONG_REGION || it == "中国澳门" }

internal fun geographicParts(city: String, country: String): List<String> {
    val region = canonicalRegionName(country)
    val local = city.split('·', '・', '／', '/').map(String::trim)
        .filter { it.isNotEmpty() && !canonicalRegionName(it.removeSuffix("（全境）")).equals(region, ignoreCase = true) }
    val parts = if (unifiedDestinationRegion(country) != null) listOf(region) + local else local + region
    return parts.filter(String::isNotEmpty).distinct()
}

internal fun Place.locationLabel(): String = geographicParts(city, country).joinToString(" · ")
