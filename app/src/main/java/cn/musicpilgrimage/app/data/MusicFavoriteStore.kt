package cn.musicpilgrimage.app.data

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class MusicFavorites(
    val songs: List<String> = emptyList(),
    val releases: List<String> = emptyList(),
    val addedAt: Map<String, Long> = emptyMap(),
    val owner: String = ""
) {
    fun time(id: String, release: Boolean) = addedAt["${if (release) "releases" else "songs"}:$id"] ?: 0L
}

internal data class RemovedMusicFavorite(
    val owner: String, val id: String, val release: Boolean, val index: Int,
    val previous: String?, val next: String?, val addedAt: Long
)

/** Ordered account cache. A confirmed cloud base is committed atomically with reconciled rows. */
internal class MusicFavoriteStore(private val prefs: SharedPreferences,private val onChanged:(String,Boolean)->Unit={_,_->}, private val now: () -> Long = System::currentTimeMillis):LibraryDocumentStore {
    override val kind=PersonalLibraryKind.FAVORITES
    @Synchronized fun read(owner: String): MusicFavorites {
        fun ids(kind: String): List<String> = when (val value = prefs.all["$owner:$kind"]) {
            is String -> runCatching { JSONArray(value).let { a -> List(a.length()) { a.getString(it) }.distinct() } }.getOrDefault(emptyList())
            is Set<*> -> value.filterIsInstance<String>()
            else -> emptyList()
        }
        val dates = runCatching { JSONObject(prefs.getString("$owner:added-at", "{}")!!) }.getOrDefault(JSONObject())
        return MusicFavorites(ids("songs"), ids("releases"), dates.keys().asSequence().associateWith { dates.optLong(it) }, owner)
    }

    private fun write(owner: String, value: MusicFavorites,remote:LibraryCloudDocument?=null,dirty:Map<String,Long> = emptyMap()) {
        val editor=prefs.edit().putString("$owner:songs", JSONArray(value.songs).toString())
            .putString("$owner:releases", JSONArray(value.releases).toString())
            .putString("$owner:added-at", JSONObject(value.addedAt).toString())
        if(remote!=null)editor.libraryRemote(owner,remote,dirty)
        else editor.libraryLocalEdit(prefs,owner,kind,MusicFavoriteCodec.encode(read(owner)),MusicFavoriteCodec.encode(value))
        check(editor.commit()){"收藏尚未保存，请重试"}
        onChanged(owner,remote==null)
    }

    @Synchronized fun clear(owner:String) {
        check(prefs.edit().remove("$owner:songs").remove("$owner:releases").remove("$owner:added-at").remove("$owner:sync-base").remove("$owner:sync-version").remove("$owner:sync-dirty").remove("$owner:sync-generation").remove("$owner:sync-client").commit())
        onChanged(owner,false)
    }

    @Synchronized fun add(owner: String, id: String, release: Boolean) {
        val current = read(owner)
        val ids = if (release) current.releases else current.songs
        if (id in ids) return
        val key = "${if (release) "releases" else "songs"}:$id"
        val next = listOf(id) + ids
        write(owner, (if (release) current.copy(releases = next) else current.copy(songs = next))
            .copy(addedAt = current.addedAt + (key to now())))
    }

    @Synchronized fun remove(owner: String, id: String, release: Boolean): RemovedMusicFavorite? {
        val current = read(owner)
        val ids = if (release) current.releases else current.songs
        val index = ids.indexOf(id)
        if (index < 0) return null
        val removed = RemovedMusicFavorite(owner, id, release, index, ids.getOrNull(index - 1), ids.getOrNull(index + 1), current.time(id, release))
        val key = "${if (release) "releases" else "songs"}:$id"
        write(owner, (if (release) current.copy(releases = ids - id) else current.copy(songs = ids - id))
            .copy(addedAt = current.addedAt - key))
        return removed
    }

    @Synchronized fun undo(owner: String, removed: RemovedMusicFavorite): Boolean {
        if (owner != removed.owner) return false
        val current = read(owner)
        val ids = (if (removed.release) current.releases else current.songs).toMutableList()
        if (removed.id in ids) return false
        // Neighbor anchors preserve the original place even if a new favorite was added meanwhile.
        val index = removed.next?.let(ids::indexOf)?.takeIf { it >= 0 }
            ?: removed.previous?.let(ids::indexOf)?.takeIf { it >= 0 }?.plus(1)
            ?: removed.index.coerceAtMost(ids.size)
        ids.add(index, removed.id)
        val key = "${if (removed.release) "releases" else "songs"}:${removed.id}"
        write(owner, (if (removed.release) current.copy(releases = ids) else current.copy(songs = ids))
            .copy(addedAt = current.addedAt + (key to removed.addedAt)))
        return true
    }

    @Synchronized override fun snapshot(owner:String)=librarySnapshot(prefs,owner,kind,MusicFavoriteCodec.encode(read(owner)))
    @Synchronized override fun reconcile(owner:String,remote:LibraryCloudDocument,acknowledged:LibraryLocalDocument?):LibraryMerge {
        MusicFavoriteCodec.decode(remote.data,owner)
        val local=snapshot(owner)
        if(remote.revision<local.revision)return LibraryMerge(local.data)
        val merged=reconcileLibrary(kind,local,remote,acknowledged)
        write(owner,MusicFavoriteCodec.decode(merged.data,owner),remote,merged.dirty)
        return merged
    }
}
