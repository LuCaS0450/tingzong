package cn.musicpilgrimage.app.performance

import android.content.Context

/** Loads verified cloud-synced performance charts from local atomic cache. */
internal object PerformanceCatalog {
    fun load(context: Context): List<SongPackage> = PerformanceCatalogStore(context).loadCachedSongs()
}
