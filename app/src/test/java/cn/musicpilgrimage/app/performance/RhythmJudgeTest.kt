package cn.musicpilgrimage.app.performance

import org.junit.Test
import org.junit.Assert.*

class RhythmJudgeTest {
    private fun target(id:Int,lane:Int,time:Long,duration:Long=100_000,hold:Boolean=false)=Target(id,listOf(id),lane,time,duration,hold)
    @Test fun slowerSourceClockKeepsTheSamePhysicalJudgmentWindowsAfterRestore() {
        val targets=listOf(target(0,0,1_000_000),target(1,1,2_000_000),target(2,2,3_000_000))
        val judge=RhythmJudge(targets,speed=.9)
        assertEquals(Judgment.PERFECT,judge.down(0,1_081_000)?.judgment)
        assertEquals(Judgment.GOOD,judge.down(1,2_198_000)?.judgment)
        val restored=CheckpointCodec.decode(targets,CheckpointCodec.encode(judge,2_198_000),.9)
        restored.tick(3_198_001)
        assertEquals(Judgment.MISSED,restored.progress[2].judgment)
        assertEquals(56666,restored.score())
    }
    @Test fun windowsDoNotChangeSourceTimingAndMissesContinue() {
        val judge=RhythmJudge(listOf(target(0,0,1_000_000),target(1,1,2_000_000),target(2,2,3_000_000)))
        assertEquals(Judgment.PERFECT,judge.down(0,1_090_000)?.judgment)
        assertEquals(Judgment.GOOD,judge.down(1,2_220_000)?.judgment)
        judge.tick(3_220_001);assertEquals(Judgment.MISSED,judge.progress[2].judgment)
        assertEquals(56666,judge.score());assertEquals(2,judge.bestCombo)
    }
    @Test fun interruptedHoldReplaysAfterLeadInWithoutDuplicatingResolvedNotes() {
        val judge=RhythmJudge(listOf(target(0,0,1_000_000),target(1,2,4_000_000,1_000_000,true)))
        judge.down(0,1_000_000);judge.down(2,4_000_000)
        val rewind=judge.pause(4_100_000)
        assertEquals(2_000_000L,rewind)
        assertEquals(Judgment.PERFECT,judge.progress[0].judgment)
        assertEquals(Judgment.WAITING,judge.progress[1].judgment)
        assertEquals(0L,judge.progress[1].heldUs)
        assertEquals(50000,judge.score());assertEquals(1,judge.combo)
        judge.tick(90_000_000);assertNull(judge.up(2,90_000_000))
        assertEquals(rewind,judge.pause(rewind))
        judge.resume(rewind)
        judge.down(2,4_000_000);judge.up(2,4_800_000)
        assertEquals(Judgment.PERFECT,judge.progress[1].judgment)
        assertEquals(100000,judge.score());assertEquals(2,judge.combo)
        judge.up(2,5_000_000);judge.tick(5_000_000);assertEquals(2,judge.combo)
    }
    @Test fun earlyReleaseAndWrongLaneAreDistinctFromCompletedHold() {
        val judge=RhythmJudge(listOf(target(0,0,1_000_000,1_000_000,true)))
        assertNull(judge.down(3,1_000_000));assertEquals(1,judge.wrongInputs)
        judge.down(0,1_000_000);judge.up(0,1_500_000)
        assertEquals(Judgment.PARTIAL,judge.progress[0].judgment);assertEquals(25000,judge.score())
    }
    @Test fun earlyAttackDoesNotCountTimeBeforeTheLongNoteStarts() {
        val t=target(0,0,1_000_000,1_000_000,true)
        val judge=RhythmJudge(listOf(t))
        judge.down(0,800_000)
        assertEquals(0f,judge.heldFraction(t,900_000),0f)
        assertEquals(.6f,judge.heldFraction(t,1_600_000),.001f)
        judge.up(0,1_600_000)
        assertEquals(Judgment.PARTIAL,judge.progress[0].judgment)
        val completed=RhythmJudge(listOf(t))
        completed.down(0,800_000);completed.tick(2_000_000)
        assertEquals(Judgment.GOOD,completed.progress[0].judgment)
        assertEquals(1,completed.combo)
    }
    @Test fun releasingDuringPauseCannotCompleteAnUntouchedHold() {
        val judge=RhythmJudge(listOf(target(0,1,4_000_000,1_000_000,true)))
        judge.down(1,4_000_000)
        val rewind=judge.pause(4_100_000)
        judge.up(1,4_100_000);judge.resume(rewind);judge.tick(5_000_000)
        assertEquals(Judgment.MISSED,judge.progress[0].judgment)
        assertEquals(0,judge.score());assertEquals(0,judge.bestCombo)
    }
    @Test fun periodicCheckpointRecoveryReplaysOnlyTheInterruptedHold() {
        val targets=listOf(target(0,0,1_000_000),target(1,1,4_000_000,1_000_000,true))
        val first=RhythmJudge(targets);first.down(0,1_000_000);first.down(1,4_000_000)
        val snapshot=CheckpointCodec.encode(first,4_400_000)
        val untouched=CheckpointCodec.decode(targets,snapshot)
        val rewind=untouched.pause(4_400_000)
        assertEquals(2_000_000L,rewind)
        assertEquals(0L,untouched.progress[1].heldUs)
        untouched.resume(rewind);untouched.tick(5_000_000)
        assertEquals(Judgment.MISSED,untouched.progress[1].judgment)
        assertEquals(50000,untouched.score())
        val replayed=CheckpointCodec.decode(targets,snapshot)
        replayed.resume(replayed.pause(4_400_000));replayed.down(1,4_000_000);replayed.tick(5_000_000)
        assertEquals(100000,replayed.score());assertEquals(2,replayed.bestCombo)
    }
    @Test fun resolvedFailuresAndSuccessfulHoldsAreNotUndoneByPause() {
        val targets=listOf(target(0,0,4_000_000,1_000_000,true))
        val partial=RhythmJudge(targets);partial.down(0,4_000_000);partial.up(0,4_100_000)
        assertEquals(4_200_000L,partial.pause(4_200_000))
        assertEquals(Judgment.PARTIAL,partial.progress[0].judgment)
        val missed=RhythmJudge(targets);missed.tick(4_300_000)
        assertEquals(4_400_000L,missed.pause(4_400_000))
        assertEquals(Judgment.MISSED,missed.progress[0].judgment)
        val completed=RhythmJudge(targets);completed.down(0,4_000_000);completed.tick(5_000_000)
        assertEquals(5_100_000L,completed.pause(5_100_000))
        assertEquals(100000,completed.score())
    }
    @Test fun leadInNeverSeeksBeforeBeginningAndSurvivesAnotherRestore() {
        val targets=listOf(target(0,0,1_000_000,1_000_000,true))
        val first=RhythmJudge(targets);first.down(0,1_000_000)
        assertEquals(0L,first.pause(1_100_000))
        val restored=CheckpointCodec.decode(targets,CheckpointCodec.encode(first,0))
        assertEquals(0L,restored.pause(0));restored.resume(0)
        assertEquals(Judgment.PERFECT,restored.down(0,1_000_000)?.judgment)
        restored.tick(2_000_000);assertEquals(100000,restored.score())
    }
}
