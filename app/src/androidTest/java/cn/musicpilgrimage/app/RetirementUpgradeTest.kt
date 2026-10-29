package cn.musicpilgrimage.app

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import cn.musicpilgrimage.app.data.LegacyChallengeCleanup
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class RetirementUpgradeTest {
    private fun isolated(block:(Context)->Unit) {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val id=UUID.randomUUID().toString()
        val root=File(base.cacheDir,"retirement-$id").apply{mkdirs()}
        val app=object:ContextWrapper(base) {
            override fun getApplicationContext():Context=this
            override fun getDataDir()=root
            override fun getFilesDir()=File(root,"files").apply{mkdirs()}
            override fun getNoBackupFilesDir()=File(root,"no_backup").apply{mkdirs()}
            override fun getDatabasePath(name:String)=File(root,"databases/$name").also{it.parentFile!!.mkdirs()}
            override fun getSharedPreferences(name:String,mode:Int)=base.getSharedPreferences("$id-$name",mode)
        }
        try{block(app)}finally{root.deleteRecursively();base.getSharedPreferences("$id-tingzong-upgrades",0).edit().clear().commit()}
    }
    @Test fun migrationKeepsCommonPreferencesAndUnrelatedRecords()=isolated {app->
        val stores=File(app.filesDir,"datastore").apply{mkdirs()}
        val old=File(stores,"detective-v4-preferences.preferences_pb").apply{writeBytes(byteArrayOf(1,2,3,4))}
        val database=app.getDatabasePath("detective-v4.db").apply{writeText("retired database")}
        val unrelated=app.getDatabasePath("library.db").apply{writeText("real personal records")}
        val catalog=File(app.filesDir,"catalog-cache.json").apply{writeText("keep music catalog")}
        val packageFile=File(app.filesDir,"detective-v4/packages/case.json").apply{parentFile!!.mkdirs();writeText("retired case")}
        LegacyChallengeCleanup.run(app)
        assertFalse(old.exists());assertFalse(database.exists());assertFalse(packageFile.exists())
        assertArrayEquals(byteArrayOf(1,2,3,4),File(stores,"tingzong-preferences.preferences_pb").readBytes())
        assertEquals("real personal records",unrelated.readText());assertEquals("keep music catalog",catalog.readText())
        val recovery=File(app.noBackupFilesDir,"retired-detective-recovery.zip")
        java.util.zip.ZipFile(recovery).use{z->
            assertEquals("retired database",z.getInputStream(z.getEntry("databases/detective-v4.db")).bufferedReader().readText())
        }
        val size=recovery.length();LegacyChallengeCleanup.run(app);assertEquals(size,recovery.length())
    }
    @Test fun invalidRecoveryCannotDeleteOriginalDatabase()=isolated {app->
        val database=app.getDatabasePath("detective-v4.db").apply{writeText("only original")}
        File(app.noBackupFilesDir,"retired-detective-recovery.zip").writeText("invalid archive")
        LegacyChallengeCleanup.run(app)
        assertEquals("only original",database.readText())
        assertFalse(app.getSharedPreferences("tingzong-upgrades",0).getBoolean("detective-removed-2.3",false))
    }
}
