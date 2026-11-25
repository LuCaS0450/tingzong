package cn.musicpilgrimage.app.performance

import android.content.Context

/** Loads verified cloud-synced performance charts and manifest metadata from local atomic cache. */
internal object PerformanceCatalog {
    fun load(context: Context): List<SongPackage> = PerformanceCatalogStore(context).loadCachedSongs()

    fun loadManifestTracks(context: Context): List<CloudCatalogTrack> =
        PerformanceCatalogStore(context).loadManifestTracks()

    fun loadCachedSong(context: Context, id: String, version: Int? = null): SongPackage? =
        PerformanceCatalogStore(context).loadCachedSongById(id, version)
}
