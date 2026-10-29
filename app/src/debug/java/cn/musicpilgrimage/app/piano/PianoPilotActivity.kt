package cn.musicpilgrimage.app.piano

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.SoundPool
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import cn.musicpilgrimage.app.design.BauhausButton
import cn.musicpilgrimage.app.design.BauhausPanel
import cn.musicpilgrimage.app.design.AppArt
import cn.musicpilgrimage.app.ui.PilgrimageTheme
import kotlinx.coroutines.delay
import org.json.JSONObject
import kotlin.math.abs

/** Independent debug-only material/interaction pilot; no account or GPS required. */
class PianoPilotActivity : ComponentActivity() {
    private lateinit var session: PianoSession
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        session = PianoSession(this)
        setContent { PilgrimageTheme { PianoPilot(session) } }
    }
    override fun onPause() {
        if (::session.isInitialized) session.pause()
        super.onPause()
    }
    override fun onDestroy() {
        if (::session.isInitialized) session.release()
        super.onDestroy()
    }
}

private data class PianoNote(val index: Int, val bar: Int, val lane: Int, val pitch: Int, val timeMs: Int)
private enum class PlayMode(val label: String) { STEP("慢慢弹"), RHYTHM("跟着弹"), LISTEN("先听一遍") }
private enum class PlayState { READY, PLAYING, PAUSED, FINISHED }

private class PianoSession(private val context: Context) {
    private val json = JSONObject(context.assets.open("piano-pilot/chart.json").bufferedReader().use { it.readText() })
    val notes = json.getJSONArray("notes").let { array -> List(array.length()) { index ->
        array.getJSONObject(index).let { PianoNote(it.getInt("index"), it.getInt("bar"), it.getInt("lane"), it.getInt("pitch"), it.getInt("timeMs")) }
    } }
    val duration = json.getInt("durationMs")
    val photo = context.assets.open("piano-pilot/samaritaine.jpg").use { BitmapFactory.decodeStream(it) }.asImageBitmap()
    var mode by mutableStateOf(PlayMode.STEP); private set
    var state by mutableStateOf(PlayState.READY); private set
    var position by mutableIntStateOf(0); private set
    var hits by mutableIntStateOf(0); private set
    var combo by mutableIntStateOf(0); private set
    var bestCombo by mutableIntStateOf(0); private set
    var next by mutableIntStateOf(0); private set
    var feedback by mutableStateOf("不必识谱，点亮起的琴键就能弹。")
    var ready by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var lastLane by mutableIntStateOf(-1); private set
    var lastTapMs by mutableLongStateOf(0); private set
    // 0 pending, 1 hit, -1 missed. Snapshot state makes the Canvas react to taps.
    val results = mutableStateListOf<Int>().apply { repeat(notes.size) { add(0) } }
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val audio = context.getSystemService(AudioManager::class.java)
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener { if (it != AudioManager.AUDIOFOCUS_GAIN) pause() }.build()
    private val pool = SoundPool.Builder().setMaxStreams(10).setAudioAttributes(attributes).build()
    private val sounds = mutableMapOf<Int, Int>()
    private val streams = ArrayDeque<Int>()
    private var player: MediaPlayer? = null
    private var closed = false
    private var loaded = 0
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { pause() }
    }
    init {
        val pitches = notes.map { it.pitch }.distinct()
        pool.setOnLoadCompleteListener { _, _, status ->
            if (!closed) {
                if (status == 0) loaded++ else error = "琴键音色加载失败，请退出后重试。"
                ready = loaded == pitches.size && error == null
            }
        }
        pitches.forEach { pitch ->
            context.assets.openFd("piano-pilot/keys/$pitch.wav").use { sounds[pitch] = pool.load(it, 1) }
        }
        ContextCompat.registerReceiver(context, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    fun select(value: PlayMode) {
        stopAudio()
        mode = value
        reset()
        feedback = when (value) {
            PlayMode.STEP -> "不必识谱，点亮起的琴键就能弹。"
            PlayMode.RHYTHM -> "音符落到横线时，点下对应琴键。"
            PlayMode.LISTEN -> "先听这段简化钢琴编配。"
        }
    }
    private fun reset() {
        state = PlayState.READY; position = 0; hits = 0; combo = 0; bestCombo = 0; next = 0; lastLane = -1
        results.indices.forEach { results[it] = 0 }
    }
    fun start() {
        if (!ready || closed) return
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            feedback = "暂时无法播放，请稍后再试。"; return
        }
        if (state == PlayState.PAUSED) {
            state = PlayState.PLAYING
            player?.start()
            feedback = "继续这一段。"
            return
        }
        stopPlayer()
        reset()
        state = PlayState.PLAYING
        feedback = if (mode == PlayMode.STEP) "点亮起的琴键，按你的速度来。" else "准备开始……"
        if (mode != PlayMode.STEP) {
            try {
                player = MediaPlayer().apply {
                    setAudioAttributes(attributes)
                    context.assets.openFd("piano-pilot/${if (mode == PlayMode.LISTEN) "preview" else "backing"}.wav").use {
                        setDataSource(it.fileDescriptor, it.startOffset, it.length)
                    }
                    setOnCompletionListener { finish() }
                    setOnErrorListener { _, _, _ -> error = "音频播放失败，请重新打开试奏。"; pause(); true }
                    prepare()
                    start()
                }
            } catch (_: Exception) {
                error = "音频播放失败，请重新打开试奏。"; pause()
            }
        }
    }
    fun tick() {
        if (state != PlayState.PLAYING) return
        if (lastLane >= 0 && android.os.SystemClock.elapsedRealtime() - lastTapMs > 180) lastLane = -1
        position = if (mode == PlayMode.STEP) notes.getOrNull(next)?.timeMs ?: duration else player?.currentPosition ?: position
        if (mode == PlayMode.RHYTHM) {
            notes.filter { results[it.index] == 0 && position - it.timeMs > 240 }.forEach {
                results[it.index] = -1; combo = 0; feedback = "漏掉一音也没关系，跟上下一个。"
            }
        }
        if (mode == PlayMode.LISTEN && position >= 2000) feedback = "第 ${currentBar()} / 8 小节"
    }
    fun tap(lane: Int) {
        if (!ready || state != PlayState.PLAYING || mode == PlayMode.LISTEN) return
        val target = if (mode == PlayMode.STEP) notes.getOrNull(next) else notes.filter {
            it.lane == lane && results[it.index] == 0 && abs(it.timeMs - position) <= 240
        }.minByOrNull { abs(it.timeMs - position) }
        val pitch = notes.firstOrNull { it.lane == lane && it.timeMs >= position - 240 }?.pitch
            ?: notes.last { it.lane == lane }.pitch
        playPitch(if (target?.lane == lane) target.pitch else pitch)
        lastLane = lane; lastTapMs = android.os.SystemClock.elapsedRealtime()
        if (target == null || target.lane != lane) {
            if (mode == PlayMode.RHYTHM) combo = 0
            feedback = if (mode == PlayMode.STEP) "试试亮起的那一键。" else "等音符靠近横线，再点一下。"
            return
        }
        results[target.index] = 1; hits++; combo++; bestCombo = maxOf(combo, bestCombo)
        feedback = if (mode == PlayMode.STEP) "${hits} / ${notes.size} 音 · 按你的速度来" else if (abs(target.timeMs - position) <= 110) "正好 · 连续 $combo 音" else "接住了 · 连续 $combo 音"
        if (mode == PlayMode.STEP) {
            next++
            if (next == notes.size) finish() else position = notes[next].timeMs
        }
    }
    private fun playPitch(pitch: Int) {
        sounds[pitch]?.let { id ->
            streams.addLast(pool.play(id, 1f, 1f, 1, 0, 1f))
            if (streams.size > 16) pool.stop(streams.removeFirst())
        }
    }
    fun currentBar(): Int = if (mode == PlayMode.STEP) notes.getOrNull(next)?.bar ?: 8 else
        (1 + ((position - 2000).coerceAtLeast(0) / (60_000f / 88 * 4)).toInt()).coerceAtMost(8)
    fun progress(): Float = if (mode == PlayMode.STEP) next.toFloat() / notes.size else position.toFloat() / duration
    fun pause() {
        if (closed) return
        if (state == PlayState.PLAYING) {
            runCatching { player?.pause() }; state = PlayState.PAUSED; feedback = "已暂停，准备好再继续。"
        }
        streams.forEach { pool.stop(it) }; streams.clear()
        audio.abandonAudioFocusRequest(focus)
    }
    private fun finish() {
        if (mode == PlayMode.RHYTHM) results.indices.filter { results[it] == 0 }.forEach { results[it] = -1 }
        state = PlayState.FINISHED; position = duration
        feedback = if (mode == PlayMode.LISTEN) "试听结束，换成「慢慢弹」亲手试试。" else "这一段弹完了。"
        stopPlayer()
        audio.abandonAudioFocusRequest(focus)
    }
    private fun stopPlayer() { player?.release(); player = null }
    private fun stopAudio() { stopPlayer(); streams.forEach { pool.stop(it) }; streams.clear(); audio.abandonAudioFocusRequest(focus) }
    fun release() {
        if (closed) return
        stopAudio(); pool.release(); context.unregisterReceiver(noisy); closed = true
    }
}

@Composable
private fun PianoPilot(session: PianoSession) {
    var place by remember { mutableStateOf(false) }
    LaunchedEffect(session) { while (true) { session.tick(); delay(16) } }
    val playing = session.state == PlayState.PLAYING
    Column(Modifier.fillMaxSize().background(AppArt.Base).safeDrawingPadding().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("演奏巡礼", style = MaterialTheme.typography.titleLarge, color = AppArt.Ink)
            Text("PARIS / 01", color = AppArt.Muted, fontSize = 12.sp, letterSpacing = 1.sp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("最伟大的作品", fontSize = 27.sp, lineHeight = 35.sp, color = AppArt.Ink, fontWeight = FontWeight.SemiBold)
            Text("周杰伦  ·  前奏八小节  ·  四键试奏", fontSize = 13.sp, color = AppArt.Muted)
        }
        BauhausPanel(Modifier.fillMaxWidth(), material = "wood") {
            Column(Modifier.padding(6.dp)) {
                Image(session.photo, "《最伟大的作品》MV 中的莎玛丽丹百货，原作完整画面", Modifier.fillMaxWidth().height(136.dp), contentScale = ContentScale.Fit)
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("莎玛丽丹百货", color = Color.White, fontSize = 13.sp)
                    Text("巴黎 · 法国", color = Color.White.copy(alpha = .75f), fontSize = 12.sp)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlayMode.entries.forEach { mode ->
                BauhausPanel(Modifier.weight(1f).heightIn(min = 48.dp).clickable(role = Role.Button) { session.select(mode) },
                    material = if (session.mode == mode) "paint" else "paper", face = if (session.mode == mode) AppArt.Ink else AppArt.Paper) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                        Text(mode.label, color = if (session.mode == mode) Color.White else AppArt.Ink, fontSize = 14.sp)
                    }
                }
            }
        }
        if (session.state == PlayState.FINISHED) {
            BauhausPanel(Modifier.fillMaxWidth().weight(1f)) {
                Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (session.mode == PlayMode.LISTEN) "这段旋律，轮到你了" else "把旋律带到巴黎", fontSize = 22.sp, color = AppArt.Ink)
                    Spacer(Modifier.height(12.dp))
                    Text(if (session.mode == PlayMode.LISTEN) "选择「慢慢弹」，没有计时压力。" else "接住 ${session.hits} / 64 音 · 最长连续 ${session.bestCombo} 音", color = AppArt.Muted, fontSize = 14.sp)
                    Spacer(Modifier.height(20.dp))
                    BauhausButton("看看 MV 的取景地", { place = true })
                }
            }
        } else {
            PianoRoll(session, Modifier.fillMaxWidth().weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(session.error ?: if (session.ready) session.feedback else "正在准备钢琴音色……", color = if (session.error == null) AppArt.Muted else AppArt.RedInk, fontSize = 13.sp, modifier = Modifier.weight(1f))
            if (playing || session.state == PlayState.PAUSED) Text("${session.currentBar()}/8", fontSize = 13.sp, color = AppArt.Muted)
        }
        LinearProgressIndicator(progress = { session.progress().coerceIn(0f, 1f) }, Modifier.fillMaxWidth().height(3.dp), color = AppArt.Blue, trackColor = AppArt.Edge.copy(alpha = .35f))
        if (session.mode != PlayMode.LISTEN && session.state != PlayState.FINISHED) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(4) { lane ->
                    val highlighted = playing && session.mode == PlayMode.STEP && session.notes.getOrNull(session.next)?.lane == lane
                    val pressed = playing && session.lastLane == lane && android.os.SystemClock.elapsedRealtime() - session.lastTapMs < 180
                    BauhausPanel(Modifier.weight(1f).height(82.dp).semantics { contentDescription = "琴键 ${lane + 1}${if (highlighted) "，下一音" else ""}" }
                        .clickable(enabled = playing, role = Role.Button) { session.tap(lane) }, material = "paint",
                        face = if (pressed) AppArt.Yellow else if (highlighted) AppArt.Blue else AppArt.Paper, pressed = pressed) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("${lane + 1}", fontSize = 24.sp, color = if (highlighted && !pressed) Color.White else AppArt.Ink)
                        }
                    }
                }
            }
        }
        BauhausButton(when (session.state) {
            PlayState.READY -> if (session.mode == PlayMode.LISTEN) "听这段钢琴" else "开始试奏"
            PlayState.PLAYING -> "暂停"
            PlayState.PAUSED -> "继续"
            PlayState.FINISHED -> if (session.mode == PlayMode.LISTEN) "再听一遍" else "再弹一次"
        }, { if (playing) session.pause() else session.start() }, Modifier.fillMaxWidth(), enabled = session.ready && session.error == null, primary = !playing)
        Text("钢琴采样：Alexander Holm · CC BY 3.0", fontSize = 10.sp, color = AppArt.Muted, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
    if (place) {
        val context = androidx.compose.ui.platform.LocalContext.current
        AlertDialog(onDismissRequest = { place = false }, title = { Text("莎玛丽丹百货 · 巴黎") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("你刚弹的旋律，来自这支在巴黎拍摄的 MV。Sony Music 官方报道确认莎玛丽丹是取景地。")
                Text("9 rue de la Monnaie, 75001 Paris")
                Text("具体楼层、钢琴位置和开放情况需向商场核实。", fontSize = 13.sp, color = AppArt.Muted)
                Text("曲谱：Jeanie / Everyone Piano，公开谱第 1 页，1–8 小节。短样简化节奏与伴奏，供本机个人试奏。", fontSize = 12.sp)
                Text("音色：Salamander Grand Piano v3 / Alexander Holm；取样、重采样与截短，保留 CC BY 3.0 署名。", fontSize = 12.sp)
            }
        }, confirmButton = { TextButton(onClick = {
            session.pause()
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=1emA1EFsPMM")))
        }) { Text("看官方 MV") } }, dismissButton = { TextButton(onClick = { place = false }) { Text("回到试奏") } })
    }
}

@Composable
private fun PianoRoll(session: PianoSession, modifier: Modifier) {
    val results = session.results.toList()
    val position = if (session.state == PlayState.READY) session.notes.first().timeMs - 1000 else session.position
    val active = session.state == PlayState.PLAYING || session.state == PlayState.PAUSED
    val description = when (session.mode) {
        PlayMode.STEP -> "点亮起的琴键，逐音前进"
        PlayMode.RHYTHM -> "音符落到横线时，点击对应琴键"
        PlayMode.LISTEN -> "自动试听，同步显示旋律"
    }
    Box(modifier.clip(RoundedCornerShape(5.dp)).background(Color(0xFFE7EBE6)).semantics { contentDescription = description }) {
        Canvas(Modifier.fillMaxSize()) {
            val width = size.width / 4
            val line = size.height * .8f
            repeat(4) { lane ->
                drawRect(if (lane % 2 == 0) Color.White.copy(alpha = .23f) else Color.Transparent, Offset(lane * width, 0f), Size(width, size.height))
                if (lane > 0) drawLine(AppArt.Edge.copy(alpha = .5f), Offset(lane * width, 0f), Offset(lane * width, size.height), 1.dp.toPx())
            }
            drawLine(AppArt.Ink, Offset(0f, line), Offset(size.width, line), 2.dp.toPx())
            val window = if (session.mode == PlayMode.STEP) 1600f else 1800f
            session.notes.forEach { note ->
                val y = line - (note.timeMs - position) / window * line
                if (y > -24.dp.toPx() && y < size.height + 24.dp.toPx()) {
                    val played = if (session.mode == PlayMode.LISTEN) note.timeMs < position && active else results[note.index] == 1
                    val missed = results[note.index] == -1
                    val color = if (played) AppArt.Green.copy(alpha = .4f) else if (missed) AppArt.Edge else AppArt.Blue
                    drawRoundRect(color, Offset(note.lane * width + 12.dp.toPx(), y - 10.dp.toPx()), Size(width - 24.dp.toPx(), 20.dp.toPx()), CornerRadius(3.dp.toPx()))
                    if (session.mode == PlayMode.STEP && active && note.index == session.next) {
                        drawRoundRect(AppArt.Yellow, Offset(note.lane * width + 8.dp.toPx(), y - 14.dp.toPx()), Size(width - 16.dp.toPx(), 28.dp.toPx()), CornerRadius(4.dp.toPx()), style = Stroke(2.dp.toPx()))
                    }
                }
            }
            drawRect(Brush.verticalGradient(listOf(AppArt.Base.copy(alpha = .9f), Color.Transparent), endY = size.height * .22f))
        }
    }
}
