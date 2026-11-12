package cn.musicpilgrimage.app.performance

import android.content.Context
import org.json.JSONObject

/** An unverified score never becomes a playable entry merely because its assets exist. */
internal object PerformanceCatalog {
    const val SHARED_BACKGROUND_FOLDER="greatest-works"
    val ORDERED_SONG_IDS=listOf(
        "greatest-works",
        "love-confession",
        "common-jasmine-orange",
        "secret",
        "wont-cry",
        "she-says",
        "river-south",
        "no-turning-back",
        "under-mount-fuji",
        "long-time-no-see",
        "you-complete-me",
        "he-r",
        "love-me-or-him",
        "wedding-card-street",
        "hotel-california"
    )
    fun backgroundAssetPath(context:Context,song:SongPackage):String {
        val ownFiles=context.assets.list("performance/${song.id}").orEmpty()
        return if(song.background in ownFiles) "performance/${song.id}/${song.background}"
        else "performance/$SHARED_BACKGROUND_FOLDER/${song.background}"
    }
    fun load(context:Context):List<SongPackage> {
        val folders=context.assets.list("performance").orEmpty().filter{it!="piano"}
        val opusManifest = if ("opus.json" in folders) {
            context.assets.open("performance/opus.json").bufferedReader().use { JSONObject(it.readText()) }
        } else JSONObject()
        val loaded=folders.mapNotNull {folder->
            if(!Regex("[a-z0-9-]+").matches(folder))return@mapNotNull null
            val files=context.assets.list("performance/$folder").orEmpty()
            if("song.json" !in files)return@mapNotNull null
            runCatching {
                val song=SongPackage.from(JSONObject(context.assets.open("performance/$folder/song.json").bufferedReader().use{it.readText()}))
                require(song.id==folder)
                song.recording?.takeUnless{it.local}?.variants?.values?.forEach{name->
                    context.assets.open("performance/$folder/audio/$name").use{check(it.read()>=0)}
                }
                require(song.sections.all{Regex("[a-z0-9-]+").matches(it.scene)})
                context.assets.open(backgroundAssetPath(context,song)).use{check(it.read()>=0)}
                val recording = song.recording
                val opus = opusManifest.optJSONObject(song.id)?.takeIf {
                    recording?.local == true && it.optInt("version") == song.version &&
                        it.optString("sourceSha256") == recording.files.values.singleOrNull()?.sha256
                }
                if (recording != null && opus != null) {
                    val file = RecordingFile(opus.getLong("bytes"), opus.getString("sha256"))
                    require(file.bytes in 1..RecordedMusic.MAX_PCM_BYTES && Regex("[a-f0-9]{64}").matches(file.sha256))
                    song.copy(recording = recording.copy(opus = file))
                } else song
            }.getOrNull()
        }
        return loaded.sortedWith(compareBy({val idx=ORDERED_SONG_IDS.indexOf(it.id);if(idx>=0)idx else Int.MAX_VALUE},{it.id}))
    }
}
