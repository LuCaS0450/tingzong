package cn.musicpilgrimage.app.data

import java.text.Normalizer
import java.util.Locale

// Match reviewed catalogue names within their country. Some cities share a name, while
// neighboring cities such as Taipei and New Taipei need separate destinations.
private val destinationNames = mapOf(
    "中国台湾" to mapOf(
        "基隆" to listOf("基隆市"),
        "台北" to listOf("台北市", "台北市松山区", "台北市松山區", "台北／新北"), // Yangmingshan visitor center, Beitou.
        "新北" to listOf("新北市", "新北淡水", "新北市淡水区", "新北市淡水區", "新北市八里区", "新北市八里區"),
        "花莲" to listOf("花蓮", "花莲市", "花蓮市", "花莲县", "花蓮縣", "花莲县丰滨乡", "花蓮縣豐濱鄉"),
        "台南" to listOf("台南市", "台南新营", "台南市新营区", "台南市新營區"),
        "宜兰" to listOf("宜蘭", "宜兰县", "宜蘭縣", "宜兰头城", "宜兰县头城镇", "宜蘭縣頭城鎮"),
        "新竹县" to listOf("新竹縣", "新竹县关西镇", "新竹縣關西鎮"),
        "台中" to listOf("台中市"),
        "桃园" to listOf("桃園", "桃园市", "桃園市"),
        "高雄" to listOf("高雄市"),
    ),
    "美国" to mapOf(
        "洛杉矶" to listOf("Los Angeles", "洛杉磯"),
        "纽约" to listOf("New York City", "纽约市", "紐約市", "纽约布鲁克林", "紐約布魯克林"),
    ),
    "日本" to mapOf(
        "东京" to listOf("Tokyo", "東京"),
        "府中" to listOf("府中市", "东京都府中市", "東京都府中市"),
        "尾道" to listOf("尾道市", "广岛尾道", "広島県尾道市"),
        "今治" to listOf("今治市", "爱媛今治", "愛媛県今治市"),
        "富士宫" to listOf("富士宫市", "静冈 · 富士宫", "静岡県富士宮市"),
    ),
    "意大利" to mapOf(
        "罗马" to listOf("Roma", "Rome", "羅馬"),
        "巴勒莫" to listOf("Palermo", "巴勒莫／西西里"),
    ),
    "中国" to mapOf("洪洞县" to listOf("山西 · 临汾 · 洪洞")),
    "英国" to mapOf("多塞特" to listOf("多塞特 · 斯塔德兰")),
    "埃及" to mapOf("吉萨" to listOf("Giza", "开罗/吉萨")),
    "伊拉克" to mapOf("希拉" to listOf("巴比伦省希拉")),
)

private fun tripNameKey(value: String): String =
    Normalizer.normalize(value.trim(), Normalizer.Form.NFKC).lowercase(Locale.ROOT).replace('臺', '台')

private val canonicalNames = destinationNames.mapValues { (_, names) ->
    buildMap {
        names.forEach { (name, aliases) ->
            put(tripNameKey(name), name)
            aliases.forEach { put(tripNameKey(it), name) }
        }
    }
}

internal fun canonicalTripCityName(name: String, country: String): String =
    canonicalNames[country]?.get(tripNameKey(name)) ?: name

internal fun MusicTripCity.matchesDestinationName(query: String): Boolean {
    val term = tripNameKey(query)
    return tripNameKey(label).contains(term) ||
        destinationNames[canonicalRegionName(country)]?.get(label).orEmpty().any { alias ->
            val alternative = tripNameKey(alias)
            // Combined catalogue labels are searchable without matching either city separately.
            if ('/' in alternative) alternative == term else alternative.contains(term)
        }
}

internal fun tripCatalogueCities(catalog: Catalog): List<MusicTripCity> =
    musicMapCities(catalog, null).flatMap { group ->
        group.places.map { MusicTripCity(it.city, it.country) }
    }.distinctBy { it.key }
