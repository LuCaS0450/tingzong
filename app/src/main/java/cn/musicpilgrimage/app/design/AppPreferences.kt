package cn.musicpilgrimage.app.design

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

internal val Context.appPreferenceStore by preferencesDataStore(name = "tingzong-preferences")
internal data class AppPreferencesState(val muted: Boolean = true, val reducedMotion: Boolean = false)
internal class AppPreferences(context: Context) {
    companion object {
        /** Called before either DataStore is opened; the old file is kept for scoped retirement. */
        @Synchronized fun migrateLegacyFile(context: Context) {
            val folder = java.io.File(context.filesDir, "datastore")
            val old = java.io.File(folder, "detective-v4-preferences.preferences_pb")
            val target = java.io.File(folder, "tingzong-preferences.preferences_pb")
            if (old.exists() && !target.exists()) {
                val pending = java.io.File(folder, "tingzong-preferences.migrating")
                pending.outputStream().use { output -> old.inputStream().use { it.copyTo(output) } }
                check(pending.renameTo(target)) { "无法保存通用偏好" }
            }
        }
    }
    private val store = context.applicationContext.let { app ->
        migrateLegacyFile(app)
        app.appPreferenceStore
    }
    private val mutedKey = booleanPreferencesKey("muted")
    private val motionKey = booleanPreferencesKey("reduced_motion")
    val changes = store.data.map { AppPreferencesState(it[mutedKey] ?: true, it[motionKey] ?: false) }
    suspend fun read(): AppPreferencesState = changes.first()
    suspend fun set(value: AppPreferencesState) { store.edit { it[mutedKey] = value.muted; it[motionKey] = value.reducedMotion } }
    suspend fun setMuted(value:Boolean){store.edit{it[mutedKey]=value}}
    suspend fun setReducedMotion(value:Boolean){store.edit{it[motionKey]=value}}
    suspend fun importOnce(value: AppPreferencesState) { store.edit {
        if (mutedKey !in it) it[mutedKey] = value.muted
        if (motionKey !in it) it[motionKey] = value.reducedMotion
    } }
}
