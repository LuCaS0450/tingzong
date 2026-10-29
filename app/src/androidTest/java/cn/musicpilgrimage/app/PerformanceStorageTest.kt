package cn.musicpilgrimage.app

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import cn.musicpilgrimage.app.performance.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*

class PerformanceStorageTest {
    @Test fun onlyBestSongDifficultySurvivesLosingAttemptsAndVersions()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,LibraryDatabase::class.java).build()
        try {
            val dao=db.performance()
            val first=PerformanceResult("test:a","first","song",1,1,80000,10,8,0,2,100)
            val one=dao.finish(first)
            assertTrue(one.newRecord);assertNull(one.previousBest);assertEquals(80000,one.highestScore)
            val lower=first.copy(attemptId="lower",score=60000,finishedAt=200)
            dao.activate(PerformanceRun(lower.owner,lower.songId,1,1,"play",lower.attemptId))
            val stale=PerformanceCheckpoint(lower.owner,lower.songId,1,1,"play",lower.attemptId,100,"{}",200,1)
            dao.save(stale)
            assertFalse(dao.finish(lower).newRecord)
            dao.save(stale.copy(revision=2))
            assertNull(dao.checkpoint(lower.owner,lower.songId,1,1,"play"))
            dao.finish(first.copy(owner="test:b",attemptId="other-owner",score=90000))
            dao.finish(first.copy(attemptId="other-difficulty",difficulty=2,score=70000))
            val next=dao.finish(first.copy(attemptId="new-version",version=2,score=85000,finishedAt=300))
            assertTrue(next.newRecord);assertEquals(80000,next.previousBest)
            val equal=dao.finish(first.copy(attemptId="tie",score=85000,bestCombo=12,finishedAt=400))
            assertFalse(equal.newRecord)
            val rows=dao.results("test:a").first()
            assertEquals(2,rows.size)
            assertEquals("tie",rows.single{it.difficulty==1}.attemptId)
            assertEquals(1,dao.results("test:b").first().size)
        } finally {db.close()}
    }
    @Test fun completedAttemptCannotBeDuplicatedOrResurrectedByPendingSave()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,LibraryDatabase::class.java).build()
        try {
            val dao=db.performance();val run=PerformanceRun("guest:a","song",1,1,"performance","one")
            dao.activate(run)
            val checkpoint=PerformanceCheckpoint(run.owner,run.songId,1,1,run.mode,run.attemptId,500,"{}",10,1)
            dao.save(checkpoint)
            val result=PerformanceResult(run.owner,run.attemptId,run.songId,1,1,90000,12,10,2,0,100)
            dao.finish(result);dao.finish(result);dao.save(checkpoint.copy(revision=3,positionUs=800))
            assertEquals(1,dao.results(run.owner).first().size)
            assertNull(dao.checkpoint(run.owner,run.songId,1,1,run.mode))
            assertTrue(dao.results("user:b").first().isEmpty())
        } finally {db.close()}
    }
    @Test fun previousAttemptAndOlderRevisionCannotOverwriteRestart()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,LibraryDatabase::class.java).build()
        try {
            val dao=db.performance();val run=PerformanceRun("user:a","song",1,2,"performance","one")
            dao.activate(run)
            val old=PerformanceCheckpoint(run.owner,run.songId,1,2,run.mode,run.attemptId,800,"old",100,4)
            dao.save(old)
            dao.activate(run.copy(attemptId="two"))
            val fresh=old.copy(attemptId="two",positionUs=100,snapshot="new",revision=1)
            dao.save(fresh);dao.save(old.copy(revision=10))
            dao.save(fresh.copy(positionUs=500,revision=2))
            dao.save(fresh.copy(positionUs=10,revision=1))
            val stored=dao.checkpoint(run.owner,run.songId,1,2,run.mode)!!
            assertEquals("two",stored.attemptId);assertEquals(500,stored.positionUs)
        } finally {db.close()}
    }
}
