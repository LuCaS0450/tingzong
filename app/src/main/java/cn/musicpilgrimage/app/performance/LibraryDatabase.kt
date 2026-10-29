package cn.musicpilgrimage.app.performance

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName="performance_checkpoints",primaryKeys=["owner","songId","version","difficulty","mode"])
internal data class PerformanceCheckpoint(val owner:String,val songId:String,val version:Int,val difficulty:Int,
    val mode:String,val attemptId:String,val positionUs:Long,val snapshot:String,val updatedAt:Long,val revision:Long=0)

@Entity(tableName="performance_runs",primaryKeys=["owner","songId","version","difficulty","mode"])
internal data class PerformanceRun(val owner:String,val songId:String,val version:Int,val difficulty:Int,val mode:String,val attemptId:String)

@Entity(tableName="performance_results",primaryKeys=["owner","attemptId"])
internal data class PerformanceResult(val owner:String,val attemptId:String,val songId:String,val version:Int,
    val difficulty:Int,val score:Int,val bestCombo:Int,val perfect:Int,val good:Int,val missed:Int,val finishedAt:Long)

internal data class PerformanceSaveOutcome(val previousBest:Int?,val highestScore:Int,val newRecord:Boolean)

@Dao internal interface PerformanceDao {
    @Query("select * from performance_checkpoints where owner=:owner and songId=:song and version=:version and difficulty=:difficulty and mode=:mode")
    suspend fun checkpoint(owner:String,song:String,version:Int,difficulty:Int,mode:String):PerformanceCheckpoint?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsertCheckpoint(row:PerformanceCheckpoint)
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun activate(row:PerformanceRun)
    @Query("select attemptId from performance_runs where owner=:owner and songId=:song and version=:version and difficulty=:difficulty and mode=:mode")
    suspend fun activeAttempt(owner:String,song:String,version:Int,difficulty:Int,mode:String):String?
    @Query("select count(*) from performance_results where owner=:owner and attemptId=:attempt") suspend fun completed(owner:String,attempt:String):Int
    @Query("delete from performance_runs where owner=:owner and attemptId=:attempt") suspend fun closeRun(owner:String,attempt:String)
    @Query("delete from performance_checkpoints where owner=:owner and attemptId=:attempt") suspend fun removeCheckpoint(owner:String,attempt:String)
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun insertResult(row:PerformanceResult):Long
    @Query("select * from performance_results where owner=:owner order by finishedAt desc") fun results(owner:String):Flow<List<PerformanceResult>>
    @Query("select * from performance_results where owner=:owner and songId=:song and difficulty=:difficulty order by score desc, bestCombo desc, finishedAt asc limit 1")
    suspend fun bestResult(owner:String,song:String,difficulty:Int):PerformanceResult?
    @Query("delete from performance_results where owner=:owner and songId=:song and difficulty=:difficulty and attemptId!=:keep")
    suspend fun retainBest(owner:String,song:String,difficulty:Int,keep:String)
    @Transaction suspend fun finish(row:PerformanceResult):PerformanceSaveOutcome {
        val previous=bestResult(row.owner,row.songId,row.difficulty)
        val better=previous==null || row.score>previous.score || row.score==previous.score&&row.bestCombo>previous.bestCombo
        val winner=if(better){insertResult(row);row}else requireNotNull(previous)
        // Keep one highest result per song/difficulty. Closing a losing attempt still prevents
        // its queued checkpoint from resurrecting it; other owners and difficulties are untouched.
        retainBest(row.owner,row.songId,row.difficulty,winner.attemptId)
        removeCheckpoint(row.owner,row.attemptId)
        closeRun(row.owner,row.attemptId)
        return PerformanceSaveOutcome(previous?.score,winner.score,previous==null||row.score>previous.score)
    }
    @Transaction suspend fun save(row:PerformanceCheckpoint) {
        if(activeAttempt(row.owner,row.songId,row.version,row.difficulty,row.mode)!=row.attemptId || completed(row.owner,row.attemptId)>0)return
        val old=checkpoint(row.owner,row.songId,row.version,row.difficulty,row.mode)
        if(old==null || old.attemptId!=row.attemptId || row.revision>old.revision)upsertCheckpoint(row)
    }
}

@Database(entities=[PerformanceCheckpoint::class,PerformanceResult::class,PerformanceRun::class],version=1,exportSchema=true)
internal abstract class LibraryDatabase:RoomDatabase() {
    abstract fun performance():PerformanceDao
    companion object {
        @Volatile private var instance:LibraryDatabase?=null
        fun open(context:Context):LibraryDatabase=instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext,LibraryDatabase::class.java,"tingzong-library.db")
                .build().also{instance=it}
        }
        fun guestOwner(context:Context):String=synchronized(this) {
            val prefs=context.getSharedPreferences("tingzong-library-identity",Context.MODE_PRIVATE)
            val id=prefs.getString("guest",null) ?: java.util.UUID.randomUUID().toString().also{prefs.edit().putString("guest",it).commit()}
            "guest:$id"
        }
    }
}
