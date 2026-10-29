package cn.musicpilgrimage.app.performance

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.channels.Channel
import java.util.UUID
import java.io.File
import cn.musicpilgrimage.app.design.AppPreferences

internal enum class PlayMode { PLAY, LISTEN, PRACTICE, COMPARE }
internal enum class PlayState { LOADING, READY, STARTING, PLAYING, PAUSED, RECOVERING, AUDIO_FAILED, FINISHED, FAILED }
internal data class PlayOptions(val difficulty:Int=2,val mode:PlayMode=PlayMode.PLAY,
    val section:Int?=null,val loop:Boolean=false) {
    // Playback is always the release speed, including listening and section practice.
    val speed:Double get()=1.0
    init {require(difficulty in 1..2);require(mode==PlayMode.PRACTICE || (section==null && !loop))}
    val storageKey get()=if(mode==PlayMode.PRACTICE) "practice:$section:1.0:$loop"
        else if(mode==PlayMode.COMPARE) "compare:1.0" else mode.name.lowercase()
}
internal enum class FeedbackKind(val label:String,val success:Boolean) {
    TAP("正好",true), HOLD_START("按住",true), HOLD_COMPLETE("长按完成",true),
    HOLD_BREAK("松早了",false), MISS("错过",false), EMPTY("未接住",false)
}
internal data class MusicFeedback(val lane:Int,val atUs:Long,val kind:FeedbackKind,val grade:Judgment=Judgment.WAITING) {
    val label get()=if(kind==FeedbackKind.TAP && grade==Judgment.GOOD)"接住" else kind.label
}
internal data class PerformanceUi(val state:PlayState=PlayState.LOADING,val positionUs:Long=0,
    val countIn:Int=0,val score:Int=0,val combo:Int=0,val bestCombo:Int=0,
    val judgments:List<Judgment> = emptyList(),val holdFractions:List<Float> = emptyList(),
    val feedbacks:List<MusicFeedback> = emptyList(),val message:String?=null,val saved:Boolean=false,
    val highestScore:Int?=null,val newRecord:Boolean=false)

/** Control and judgments run on Main. Only sample decoding and Room writes leave that thread. */
internal class PerformanceController(application:Application,val song:SongPackage,val owner:String,
    val options:PlayOptions,private val localAudioDirectory:File?=null,private val respectMute:Boolean=false):AndroidViewModel(application) {
    private val dao=LibraryDatabase.open(application).performance()
    private val writes=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val writeQueue=Channel<Pair<suspend ()->Unit,CompletableDeferred<Unit>>>(Channel.UNLIMITED)
    private val mutableUi=MutableStateFlow(PerformanceUi())
    val ui=mutableUi.asStateFlow()
    internal fun audioOutputPeak():Float?=piano?.let{it.outputPeak(it.handle)}
    val startUs=options.section?.let{song.sections[it].startUs} ?: 0L
    val endUs=options.section?.let{song.sections[it].endUs} ?: song.durationUs
    val targets=if(options.mode==PlayMode.LISTEN)emptyList() else song.targets(options.difficulty)
        .filter{it.onsetUs>=startUs && it.onsetUs<endUs}.mapIndexed{i,t->t.copy(id=i)}
    private var judge=RhythmJudge(targets,speed=options.speed)
    private var piano:NativePiano?=null
    private var attempt=UUID.randomUUID().toString()
    private var revision=0L
    private var persistedAtUs=startUs
    private var shouldRestoreHolds=false
    private var cleared=false
    private var initialized=false
    private var startGeneration=0
    private var muted=respectMute
    private var resultSaving=false
    private val focus=PlaybackAudioSession(application){pause("音乐已暂停")}

    init {
        if(respectMute)viewModelScope.launch {
            AppPreferences(application).changes.collect {value->muted=value.muted;piano?.let{it.setMuted(it.handle,muted)}}
        }
        writes.launch {
            for((block,done) in writeQueue) {
                try {block();done.complete(Unit)} catch(error:Exception) {
                    done.completeExceptionally(error)
                    withContext(Dispatchers.Main.immediate) {
                        if(!cleared)mutableUi.value=mutableUi.value.copy(message="本次记录尚未保存，请重试",saved=false)
                    }
                }
            }
            writes.cancel()
        }
        viewModelScope.launch {
            var loaded:NativePiano?=null
            try {
                // A single loader owns the stopped native engine until it is handed to Main.
                withContext(Dispatchers.IO) {
                    val engine=NativePiano();loaded=engine
                    loadAudio(engine)
                }
                ensureActive()
                piano=loaded;loaded=null
                piano?.let{it.setMuted(it.handle,muted)}
                val previousBest=dao.bestResult(owner,song.id,options.difficulty)?.score
                mutableUi.value=mutableUi.value.copy(highestScore=previousBest)
                val saved=dao.checkpoint(owner,song.id,song.version,options.difficulty,options.storageKey)
                if(saved!=null) {
                    require(saved.positionUs in startUs..endUs)
                    judge=CheckpointCodec.decode(targets,saved.snapshot,options.speed)
                    val restoredPosition=judge.pause(saved.positionUs).coerceIn(startUs,endUs)
                    attempt=saved.attemptId;revision=saved.revision;persistedAtUs=restoredPosition
                    publish(PlayState.PAUSED,restoredPosition,
                        if(restoredPosition!=saved.positionUs)"上次的长按已回退，继续后重新演奏" else "可以继续上次的旅程",true)
                } else publish(PlayState.READY,startUs)
                initialized=true
            } catch(cancel:CancellationException) {throw cancel}
            catch(error:Exception) {
                piano?.close();piano=null
                mutableUi.value=PerformanceUi(PlayState.FAILED,message=error.message ?: "音乐暂时无法载入")
            } finally {loaded?.close()}
        }
    }

    private fun loadAudio(engine:NativePiano) {
        val application=getApplication<Application>()
        if(song.recording!=null)song.recording.load(application,song,engine,options.speed,localAudioDirectory)
        else {
            PianoSamples.load(application,engine)
            val events=song.nativeEvents()
            song.events.forEach{event->events[event.id*5+4]=0.0}
            check(engine.setScore(engine.handle,events)){"音乐资源无法读取"}
        }
    }

    /** A failed Oboe stream cannot restart. Recreate it, then wait for an explicit Continue. */
    private fun recoverAudioOutput() {
        if(!initialized || cleared || mutableUi.value.state==PlayState.RECOVERING)return
        pause("声音输出中断，进度已保留")
        val position=mutableUi.value.positionUs
        startGeneration++
        focus.abandon();piano?.close();piano=null
        publish(PlayState.RECOVERING,position,"正在恢复声音，进度已保留")
        viewModelScope.launch {
            var loaded:NativePiano?=null
            try {
                withContext(Dispatchers.IO) {
                    val engine=NativePiano();loaded=engine
                    loadAudio(engine)
                }
                ensureActive()
                piano=loaded;loaded=null
                piano?.let{it.setMuted(it.handle,muted)}
                publish(PlayState.PAUSED,position,"声音已恢复，点击继续演奏")
            } catch(cancel:CancellationException){throw cancel}
            catch(error:Exception) {
                piano?.close();piano=null
                publish(PlayState.AUDIO_FAILED,position,"声音暂时无法恢复，进度已保留，请重试")
            } finally {loaded?.close()}
        }
    }

    private fun enqueue(block:suspend ()->Unit):CompletableDeferred<Unit> {
        val done=CompletableDeferred<Unit>()
        if(!writeQueue.trySend(block to done).isSuccess)done.completeExceptionally(IllegalStateException("存储队列已关闭"))
        return done
    }
    private fun publish(state:PlayState,position:Long,message:String?=null,saved:Boolean=false) {
        val previous=mutableUi.value
        val judgments=judge.progress.map{it.judgment}
        val transitions=if(state==PlayState.PLAYING && previous.state==PlayState.PLAYING)targets.mapNotNull{t->
            val before=previous.judgments.getOrNull(t.id)
            val after=judgments[t.id]
            val kind=when {
                before==Judgment.WAITING && after==Judgment.MISSED->FeedbackKind.MISS
                before==Judgment.HOLDING && after in listOf(Judgment.PERFECT,Judgment.GOOD)->FeedbackKind.HOLD_COMPLETE
                before==Judgment.HOLDING && after==Judgment.PARTIAL->FeedbackKind.HOLD_BREAK
                else->null
            }
            kind?.let{MusicFeedback(t.lane,position,it,after)}
        } else emptyList()
        val feedbacks=if(state==PlayState.PLAYING)(previous.feedbacks.filter{position-it.atUs in 0..700_000}+transitions).takeLast(8) else emptyList()
        mutableUi.value=mutableUi.value.copy(state=state,positionUs=position,
            score=judge.score(),combo=judge.combo,bestCombo=judge.bestCombo,
            judgments=judgments,holdFractions=targets.map{if(it.hold)judge.heldFraction(it,position) else 0f},
            feedbacks=feedbacks,message=message,saved=saved)
    }
    private fun snapshot(position:Long):PerformanceCheckpoint {
        revision++
        return PerformanceCheckpoint(owner,song.id,song.version,options.difficulty,options.storageKey,
            attempt,position,CheckpointCodec.encode(judge,position),System.currentTimeMillis(),revision)
    }
    private fun saveProgress(position:Long) {
        persistedAtUs=position
        val row=snapshot(position)
        enqueue{dao.save(row)}
    }
    private fun holdChanged(previous:List<Judgment>)=targets.any {
        it.hold && previous.getOrNull(it.id)!=judge.progress[it.id].judgment
    }
    fun start() {
        if(initialized && mutableUi.value.state==PlayState.AUDIO_FAILED){recoverAudioOutput();return}
        if(!initialized || mutableUi.value.state !in listOf(PlayState.READY,PlayState.PAUSED))return
        val engine=piano ?: return
        if(engine.failed(engine.handle)){recoverAudioOutput();return}
        if(!focus.acquire()){mutableUi.value=mutableUi.value.copy(message="另一段音频正在播放，请稍后再试");return}
        val position=judge.pause(mutableUi.value.positionUs).coerceIn(startUs,endUs)
        persistedAtUs=position
        shouldRestoreHolds=true
        val row=snapshot(position)
        val generation=++startGeneration
        publish(PlayState.STARTING,position)
        // The active attempt and first checkpoint are durable before audio is started.
        viewModelScope.launch {
          try {
            enqueue {
                dao.activate(PerformanceRun(owner,song.id,song.version,options.difficulty,options.storageKey,row.attemptId))
                dao.save(row)
            }.await()
            if(cleared || generation!=startGeneration || mutableUi.value.state!=PlayState.STARTING)return@launch
            if(engine.start(engine.handle,position/1e6,endUs/1e6,options.speed,
                    0,60.0,3)) {
                publish(PlayState.PLAYING,position)
                mutableUi.value=mutableUi.value.copy(countIn=3)
            } else if(engine.failed(engine.handle)){recoverAudioOutput()}
            else {focus.abandon();publish(PlayState.PAUSED,position,"音频输出暂时不可用，请稍后重试")}
          } catch(cancel:CancellationException){throw cancel}
          catch(error:Exception){focus.abandon();publish(PlayState.PAUSED,position,"本次记录尚未保存，请重试")}
        }
    }
    /** Called each visual frame; the visual frame timestamp never becomes music time. */
    fun frame() {
        if(mutableUi.value.state!=PlayState.PLAYING)return
        val engine=piano ?: return
        val position=engine.position(engine.handle).coerceAtMost(endUs)
        val count=engine.counting(engine.handle)
        if(engine.failed(engine.handle)) {recoverAudioOutput();return}
        if(count==0 && shouldRestoreHolds) {
            shouldRestoreHolds=false;judge.resume(position)
        }
        val previous=mutableUi.value.judgments
        if(count==0)judge.tick(position)
        publish(PlayState.PLAYING,position)
        mutableUi.value=mutableUi.value.copy(countIn=count)
        if(holdChanged(previous)||position-persistedAtUs>=2_000_000)saveProgress(position)
        if(count==0 && !engine.isPlaying(engine.handle)) {
            if(position>=endUs-20_000)finish() else pause("音乐已暂停")
        }
    }
    fun down(lane:Int):Boolean {
        if(mutableUi.value.state!=PlayState.PLAYING || mutableUi.value.countIn>0 || options.mode==PlayMode.LISTEN)return false
        val engine=piano ?: return false
        val position=engine.position(engine.handle)
        val previous=mutableUi.value.judgments
        val hit=judge.down(lane,position)
        publish(PlayState.PLAYING,position)
        val kind=when {hit==null->FeedbackKind.EMPTY;hit.target.hold->FeedbackKind.HOLD_START;else->FeedbackKind.TAP}
        mutableUi.value=mutableUi.value.copy(feedbacks=(mutableUi.value.feedbacks+
            MusicFeedback(lane,position,kind,hit?.judgment ?: Judgment.WAITING)).takeLast(8))
        if(holdChanged(previous))saveProgress(position)
        return hit!=null
    }
    fun up(lane:Int) {
        if(mutableUi.value.state!=PlayState.PLAYING || mutableUi.value.countIn>0)return
        val engine=piano ?: return
        val position=engine.position(engine.handle)
        val previous=mutableUi.value.judgments
        judge.up(lane,position)
        publish(PlayState.PLAYING,position)
        if(holdChanged(previous))saveProgress(position)
    }
    fun pause(reason:String?=null) {
        startGeneration++
        if(!initialized || mutableUi.value.state !in listOf(PlayState.PLAYING,PlayState.STARTING,PlayState.PAUSED,PlayState.READY))return
        val engine=piano ?: return
        if(mutableUi.value.state==PlayState.READY)return
        engine.pause(engine.handle)
        val sourcePosition=if(mutableUi.value.state in listOf(PlayState.STARTING,PlayState.PAUSED))mutableUi.value.positionUs
            else engine.position(engine.handle).coerceAtMost(endUs)
        val position=judge.pause(sourcePosition).coerceIn(startUs,endUs)
        persistedAtUs=position;focus.abandon()
        val message=reason ?: if(position!=sourcePosition)"长按已回退，继续后重新演奏"
            else if(mutableUi.value.state==PlayState.PAUSED)mutableUi.value.message else null
        publish(PlayState.PAUSED,position,message)
        mutableUi.value=mutableUi.value.copy(countIn=0)
        val row=snapshot(position);enqueue{dao.save(row);withContext(Dispatchers.Main.immediate){
            if(!cleared && attempt==row.attemptId && mutableUi.value.state==PlayState.PAUSED)mutableUi.value=mutableUi.value.copy(saved=true)
        }}
    }
    fun retrySave() {
        if(mutableUi.value.state==PlayState.FINISHED)persistResult() else pause()
    }
    fun restart() {
        if(!initialized)return
        startGeneration++
        piano?.let{it.pause(it.handle)};focus.abandon()
        attempt=UUID.randomUUID().toString();revision=0;persistedAtUs=startUs
        judge=RhythmJudge(targets,speed=options.speed);shouldRestoreHolds=false
        publish(PlayState.READY,startUs)
        mutableUi.value=mutableUi.value.copy(countIn=0,feedbacks=emptyList(),newRecord=false)
        val row=snapshot(startUs)
        enqueue{dao.activate(PerformanceRun(owner,song.id,song.version,options.difficulty,options.storageKey,row.attemptId));dao.save(row)}
    }
    private fun finish() {
        piano?.let{it.pause(it.handle)};focus.abandon();judge.tick(endUs)
        if(options.mode==PlayMode.PRACTICE && options.loop){restart();start();return}
        publish(PlayState.FINISHED,endUs)
        mutableUi.value=mutableUi.value.copy(countIn=0)
        persistResult()
    }
    private fun persistResult() {
        if(resultSaving || mutableUi.value.saved)return
        resultSaving=true
        val thisAttempt=attempt
        val row=PerformanceResult(owner,thisAttempt,song.id,song.version,options.difficulty,judge.score(),judge.bestCombo,
            judge.progress.count{it.judgment==Judgment.PERFECT},judge.progress.count{it.judgment==Judgment.GOOD},
            judge.progress.count{it.judgment in listOf(Judgment.MISSED,Judgment.PARTIAL)},System.currentTimeMillis())
        viewModelScope.launch {
          try {
            var outcome:PerformanceSaveOutcome?=null
            enqueue {
                if(options.mode==PlayMode.PLAY)outcome=dao.finish(row)
                else {dao.removeCheckpoint(owner,thisAttempt);dao.closeRun(owner,thisAttempt)}
            }.await()
            if(!cleared && attempt==thisAttempt)mutableUi.value=mutableUi.value.copy(saved=true,message=null,
                highestScore=outcome?.highestScore ?: mutableUi.value.highestScore,newRecord=outcome?.newRecord==true)
          } catch(cancel:CancellationException){throw cancel}
          catch(error:Exception){if(!cleared&&attempt==thisAttempt)mutableUi.value=mutableUi.value.copy(message="本次记录尚未保存，请重试",saved=false)}
          finally {resultSaving=false}
        }
    }
    override fun onCleared() {
        pause();cleared=true;focus.close();piano?.close();piano=null
        // Queued writes finish independently of the screen lifecycle; then release their scope.
        writeQueue.close()
    }
}
