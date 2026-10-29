package cn.musicpilgrimage.app.data

import android.content.Context
import cn.musicpilgrimage.app.design.AppPreferences
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** One-time, scoped upgrade. No retired model or gameplay is instantiated. */
internal object LegacyChallengeCleanup {
    @Synchronized fun run(context: Context) {
        val prefs=context.getSharedPreferences("tingzong-upgrades",Context.MODE_PRIVATE)
        if(prefs.getBoolean("detective-removed-2.3",false))return
        try {
            AppPreferences.migrateLegacyFile(context)
            val root=context.dataDir.canonicalFile
            val stores=File(context.filesDir,"datastore")
            val db=context.getDatabasePath("detective-v4.db")
            val candidates=listOf(
                File(stores,"v2-detective-sessions.preferences_pb"),File(stores,"v2-detective-sessions.preferences_pb.tmp"),
                File(stores,"detective-v4-preferences.preferences_pb"),File(stores,"detective-v4-preferences.preferences_pb.tmp"),
                db,File(db.path+"-wal"),File(db.path+"-shm"),File(db.path+"-journal"),File(context.filesDir,"detective-v4")
            ).map{it.canonicalFile}
            check(candidates.all {it.path.startsWith(root.path+File.separator)})
            val files=candidates.flatMap {if(it.isDirectory)it.walkTopDown().filter(File::isFile).toList()else listOf(it)}.filter(File::exists)
            val recovery=File(context.noBackupFilesDir,"retired-detective-recovery.zip")
            if(files.isNotEmpty()) {
                if(!recovery.exists()) {
                    val pending=File(recovery.path+".pending")
                    ZipOutputStream(pending.outputStream()).use {zip->
                        files.forEach {file->
                            zip.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath))
                            file.inputStream().use{it.copyTo(zip)};zip.closeEntry()
                        }
                    }
                    check(pending.renameTo(recovery))
                }
                ZipFile(recovery).use {zip->files.forEach {file->
                    val entry=requireNotNull(zip.getEntry(file.relativeTo(root).invariantSeparatorsPath))
                    check(zip.getInputStream(entry).use{it.readBytes().contentEquals(file.readBytes())})
                }}
                check(candidates.all{!it.exists() || if(it.isDirectory)it.deleteRecursively()else it.delete()})
            }
            prefs.edit().putBoolean("detective-removed-2.3",true).putBoolean("legacy-v3-removed",true).commit()
        } catch(_:Exception) {
            android.util.Log.w("LegacyCleanup","Retirement deferred; original data retained until a valid recovery point exists")
        }
    }
}
