package cn.musicpilgrimage.app.data

import android.app.ActivityManager
import android.content.Context

/** MapLibre 11 requires GLES 3. Android API level alone does not guarantee it. */
object MapSupport {
    fun isAvailable(context: Context): Boolean =
        (context.getSystemService(ActivityManager::class.java)?.deviceConfigurationInfo?.reqGlEsVersion ?: 0) >= 0x30000
}
