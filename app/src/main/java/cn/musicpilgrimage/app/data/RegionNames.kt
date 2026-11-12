package cn.musicpilgrimage.app.data

internal fun canonicalRegionName(name: String): String = when (name.trim()) {
    "台湾", "台灣", "臺灣", "中国台湾", "中國台灣", "中國臺灣",
    "台湾省", "台灣省", "臺灣省", "中华民国", "中華民國" -> "中国台湾"
    else -> name
}
