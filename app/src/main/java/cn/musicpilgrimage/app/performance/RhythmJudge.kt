package cn.musicpilgrimage.app.performance

import kotlin.math.abs

internal enum class Judgment { WAITING, HOLDING, PERFECT, GOOD, MISSED, PARTIAL }
internal data class TargetProgress(var judgment:Judgment=Judgment.WAITING,var startedUs:Long=0,var heldUs:Long=0,var holdingFromUs:Long?=null,var attack:Judgment=Judgment.WAITING)
internal data class Hit(val target:Target,val judgment:Judgment)

/** Music time is supplied by Oboe. Wall time never advances notes or hold progress. */
internal class RhythmJudge(val targets:List<Target>,val progress:List<TargetProgress> = List(targets.size){TargetProgress()},val speed:Double=1.0) {
    var combo=0
    var bestCombo=0
    var wrongInputs=0
    private var paused=false
    init {require(progress.size==targets.size && speed>0 && speed.isFinite())}
    private val goodWindow=(220_000*speed).toLong()
    private val perfectWindow=(90_000*speed).toLong()
    private fun success(){combo++;bestCombo=maxOf(bestCombo,combo)}
    fun down(lane:Int,musicUs:Long):Hit? {
        if(paused)return null
        tick(musicUs)
        val target=targets.filter{it.lane==lane && progress[it.id].judgment==Judgment.WAITING && abs(it.onsetUs-musicUs)<=goodWindow}
            .minByOrNull{abs(it.onsetUs-musicUs)}
        if(target==null){wrongInputs++;combo=0;return null}
        val grade=if(abs(target.onsetUs-musicUs)<=perfectWindow)Judgment.PERFECT else Judgment.GOOD
        val row=progress[target.id];row.startedUs=musicUs;row.attack=grade
        if(target.hold){row.judgment=Judgment.HOLDING;row.holdingFromUs=maxOf(musicUs,target.onsetUs)}
        else {row.judgment=grade;success()}
        return Hit(target,grade)
    }
    fun up(lane:Int,musicUs:Long):Target? {
        if(paused)return null
        val target=targets.firstOrNull{it.lane==lane && progress[it.id].judgment==Judgment.HOLDING} ?: return null
        finishHold(target,musicUs);return target
    }
    private fun finishHold(target:Target,musicUs:Long) {
        val row=progress[target.id]
        row.holdingFromUs?.let{row.heldUs+=(minOf(musicUs,target.endUs)-it).coerceAtLeast(0)}
        row.holdingFromUs=null
        row.judgment=if(row.heldUs.toDouble()/target.durationUs>=.8)row.attack else Judgment.PARTIAL
        if(row.judgment in listOf(Judgment.PERFECT,Judgment.GOOD))success()else combo=0
    }
    fun heldFraction(target:Target,musicUs:Long):Float {
        val row=progress[target.id]
        val active=row.holdingFromUs?.let{(minOf(musicUs,target.endUs)-it).coerceAtLeast(0)} ?: 0L
        return ((row.heldUs+active).toDouble()/target.durationUs).toFloat().coerceIn(0f,1f)
    }
    fun tick(musicUs:Long) {
        if(paused)return
        targets.forEach {target->
            val row=progress[target.id]
            if(row.judgment==Judgment.WAITING && musicUs>target.onsetUs+goodWindow){row.judgment=Judgment.MISSED;combo=0}
            if(row.judgment==Judgment.HOLDING && musicUs>=target.endUs)finishHold(target,musicUs)
        }
    }
    /** Interrupted holds replay with a short musical lead-in; resolved notes never change. */
    fun pause(musicUs:Long):Long {
        if(!paused)tick(musicUs)
        val interrupted=targets.filter{progress[it.id].judgment==Judgment.HOLDING}
        interrupted.forEach {target->progress[target.id].apply {
            judgment=Judgment.WAITING;startedUs=0;heldUs=0;holdingFromUs=null;attack=Judgment.WAITING
        }}
        paused=true
        return interrupted.minOfOrNull{(it.onsetUs-2_000_000).coerceAtLeast(0)} ?: musicUs
    }
    fun resume(musicUs:Long) {
        paused=false
    }
    fun score():Int {
        val points=progress.sumOf {when(it.judgment){Judgment.PERFECT->1000;Judgment.GOOD->700;Judgment.PARTIAL->350;else->0}}
        return if(targets.isEmpty())0 else ((points-wrongInputs*100).coerceAtLeast(0).toLong()*100000/(targets.size*1000L)).toInt()
    }
}
