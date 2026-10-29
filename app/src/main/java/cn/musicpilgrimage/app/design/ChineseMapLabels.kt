package cn.musicpilgrimage.app.design

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Preserve the provider's Chinese names, converting their script without retranslating them. */
internal class ChineseMapLabels private constructor(private val root: Node) {
    private class Node {
        val next = mutableMapOf<Char, Node>()
        var value: String? = null
    }

    fun simplify(name: String): String {
        val result = StringBuilder()
        var start = 0
        while (start < name.length) {
            var node = root
            var end = start
            var matchedEnd = start
            var replacement: String? = null
            while (end < name.length) {
                node = node.next[name[end]] ?: break
                end++
                node.value?.let { replacement = it; matchedEnd = end }
            }
            if (replacement == null) result.append(name[start++])
            else { result.append(replacement); start = matchedEnd }
        }
        // Some provider names contain both scripts, e.g. 东京都/東京都.
        return result.toString().split('/', '／', ';', '；').map{it.trim()}.filter{it.isNotEmpty()}.distinct().joinToString("/")
    }

    fun style(bytes: ByteArray): ByteArray {
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        json.optJSONArray("layers")?.let {layers->
            for(i in 0 until layers.length()) {
                val layout=layers.getJSONObject(i).optJSONObject("layout") ?: continue
                val label=layout.optJSONArray("text-field") ?: continue
                if(!label.toString().contains("name:zh"))continue
                // Provider aliases can also come from a cached tile. Keep one display
                // name in the style while the underlying source and coordinates stay intact.
                val clean=org.json.JSONArray("""["let","room_label",null,["let","room_separator",["index-of",";",["var","room_label"]],["case",[">=",["var","room_separator"],0],["slice",["var","room_label"],0,["var","room_separator"]],["var","room_label"]]]]""")
                clean.put(2,org.json.JSONArray().put("to-string").put(label))
                layout.put("text-field",clean)
            }
        }
        val sources = json.getJSONObject("sources")
        for (id in sources.keys()) {
            val source = sources.getJSONObject(id)
            if (source.optString("type") == "vector") {
                if (source.has("url")) source.put("url", cacheUrl(source.getString("url")))
                source.optJSONArray("tiles")?.let { tiles ->
                    for (i in 0 until tiles.length()) tiles.put(i, cacheUrl(tiles.getString(i)))
                }
            }
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    fun tileMetadata(bytes: ByteArray): ByteArray {
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        json.optJSONArray("tiles")?.let { tiles ->
            for (i in 0 until tiles.length()) tiles.put(i, cacheUrl(tiles.getString(i)))
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    /** MVT 2.1: only Chinese name tags get new value slots; geometry and IDs stay byte-identical. */
    fun tile(bytes: ByteArray): ByteArray = try {
        require(bytes.size <= 8 * 1024 * 1024)
        encode(fields(bytes).map { field ->
            if (field.number == 3 && field.wire == 2) field.withPayload(layer(field.payload)) else field
        })
    } catch (_: IllegalArgumentException) { bytes }

    private fun layer(bytes: ByteArray): ByteArray {
        val original = fields(bytes)
        val keys = original.filter { it.number == 3 && it.wire == 2 }.map { it.payload.toString(Charsets.UTF_8) }
        val values = original.filter { it.number == 4 && it.wire == 2 }
        val names = keys.indices.filter { keys[it] == "name" || keys[it] == "name:zh" || keys[it].startsWith("name:zh-") }.toSet()
        if (names.isEmpty()) return bytes
        val replacements = mutableMapOf<Int, Int>()
        val added = mutableListOf<Field>()
        fun replacement(index: Int): Int {
            replacements[index]?.let { return it }
            val value = values.getOrNull(index) ?: return index
            val parts = fields(value.payload)
            val string = parts.firstOrNull { it.number == 1 && it.wire == 2 } ?: return index
            val old = string.payload.toString(Charsets.UTF_8)
            val new = simplify(old)
            if (old == new) { replacements[index] = index; return index }
            val changed = encode(parts.map { if (it === string) it.withPayload(new.toByteArray(Charsets.UTF_8)) else it })
            val next = values.size + added.size
            added += value.withPayload(changed)
            replacements[index] = next
            return next
        }
        val changed = original.map { field ->
            if (field.number != 2 || field.wire != 2) field else {
                val feature = fields(field.payload)
                field.withPayload(encode(feature.map { part ->
                    if (part.number != 2 || part.wire != 2) part else {
                        val tags = ints(part.payload).toMutableList()
                        require(tags.size % 2 == 0)
                        for (i in tags.indices step 2) if (tags[i] in names) tags[i + 1] = replacement(tags[i + 1])
                        part.withPayload(varints(tags))
                    }
                }))
            }
        }
        return if (added.isEmpty()) bytes else encode(changed + added)
    }

    private data class Field(val number: Int, val wire: Int, val raw: ByteArray, val payload: ByteArray) {
        fun withPayload(bytes: ByteArray): Field {
            if (bytes.contentEquals(payload)) return this
            require(wire == 2)
            return Field(number, wire, varints(listOf((number shl 3) or 2, bytes.size)) + bytes, bytes)
        }
    }

    private class Reader(private val bytes: ByteArray) {
        var offset = 0
        fun integer(): Int {
            var value = 0L
            for (shift in 0..63 step 7) {
                require(offset < bytes.size)
                val byte = bytes[offset++].toInt() and 255
                value = value or ((byte and 127).toLong() shl shift)
                if (byte and 128 == 0) { require(value in 0..Int.MAX_VALUE.toLong()); return value.toInt() }
            }
            throw IllegalArgumentException("Invalid tile integer")
        }
        fun skip(length: Int): ByteArray {
            require(length >= 0 && length <= bytes.size - offset)
            return bytes.copyOfRange(offset, offset + length).also { offset += length }
        }
        fun fields(): List<Field> = buildList {
            while (offset < bytes.size) {
                val start = offset
                val tag = integer()
                require(tag ushr 3 > 0)
                val wire = tag and 7
                val payload = when (wire) {
                    0 -> { // Unknown varints may hold a full uint64, including feature IDs.
                        val from = offset
                        var ended = false
                        repeat(10) {
                            if (!ended) { require(offset < bytes.size); ended = bytes[offset++].toInt() and 128 == 0 }
                        }
                        require(ended)
                        bytes.copyOfRange(from, offset)
                    }
                    1 -> skip(8)
                    2 -> skip(integer())
                    5 -> skip(4)
                    else -> throw IllegalArgumentException("Unsupported tile field")
                }
                add(Field(tag ushr 3, wire, bytes.copyOfRange(start, offset), payload))
            }
        }
        fun ints(): List<Int> = buildList { while (offset < bytes.size) add(integer()) }
    }

    companion object {
        const val CACHE_PARAMETER = "tingzong_labels"
        private fun cacheUrl(value: String): String {
            val url = value.toHttpUrl()
            return if (url.host == "api.maptiler.com") url.newBuilder().setQueryParameter(CACHE_PARAMETER, "zh-Hans-3")
                .build().toString().replace("%7B", "{").replace("%7D", "}") else value
        }
        fun load(context: Context): ChineseMapLabels {
            val root = Node()
            for (file in listOf("TSCharacters.txt", "TSPhrases.txt")) {
                context.assets.open("design/map-labels/$file").bufferedReader().useLines { lines ->
                    lines.filter { it.isNotBlank() && !it.startsWith('#') }.forEach { line ->
                        val parts = line.split('\t', limit = 2)
                        require(parts.size == 2)
                        var node = root
                        for (character in parts[0]) node = node.next.getOrPut(character) { Node() }
                        node.value = parts[1].substringBefore(' ')
                    }
                }
            }
            return ChineseMapLabels(root)
        }
        private fun fields(bytes: ByteArray) = Reader(bytes).fields()
        private fun ints(bytes: ByteArray) = Reader(bytes).ints()
        private fun encode(fields: List<Field>) = ByteArrayOutputStream().apply { fields.forEach { write(it.raw) } }.toByteArray()
        private fun varints(values: List<Int>) = ByteArrayOutputStream().apply {
            for (input in values) {
                require(input >= 0)
                var value = input
                while (value >= 128) { write((value and 127) or 128); value = value ushr 7 }
                write(value)
            }
        }.toByteArray()
    }
}
