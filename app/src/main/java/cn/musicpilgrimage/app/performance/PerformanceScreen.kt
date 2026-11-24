package cn.musicpilgrimage.app.performance

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.musicpilgrimage.app.data.ReleaseArtwork
import cn.musicpilgrimage.app.ui.AppNavEasing
import cn.musicpilgrimage.app.ui.LocalPhysicalMotion
import cn.musicpilgrimage.app.ui.room.BauhausBottomFooter
import cn.musicpilgrimage.app.ui.room.BauhausCrimsonSeal
import cn.musicpilgrimage.app.ui.room.KeybedCavityShadows
import cn.musicpilgrimage.app.ui.room.LidAcousticVent
import cn.musicpilgrimage.app.ui.room.MicroScrew
import cn.musicpilgrimage.app.ui.room.PianoWallTextures
import cn.musicpilgrimage.app.ui.room.RoomHeader
import cn.musicpilgrimage.app.ui.room.RoomPerformanceResult
import cn.musicpilgrimage.app.ui.room.RoomWall
import cn.musicpilgrimage.app.ui.room.SimpleCharcoalCheek
import cn.musicpilgrimage.app.ui.room.tiledMaterial
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val ConsoleBaseDark = Color(0xFF141715)
private val ConsoleSurfaceEbony = Color(0xFF1D211F)
private val ConsoleIvory = Color(0xFFF8F6F0)
private val ConsoleSilverMuted = Color(0xFF9DA49E)
private val ConsoleCrimson = Color(0xFFBD382B)
private val ConsoleCrimsonDarkText = Color(0xFFD64B3D)
private val ConsoleTrackDark = Color(0xFF2D3330)
private val ModalCardInk = Color(0xFF242B27)
private val ModalCardMuted = Color(0xFF5F6560)
private val ModalCardDivider = Color(0x28242B27)
private val ModalScrim = Color(0xFF0C0F0D)

@Composable
internal fun PerformanceScreen(
    controller: PerformanceController,
    onPrepare: () -> Unit,
    artwork: ReleaseArtwork? = null,
    onExplore: (() -> Unit)? = null,
    onSpace: () -> Unit = onPrepare,
    onSpaceOrigin: (Offset) -> Unit = {},
    revealStage: Boolean = true,
) {
    val ui by controller.ui.collectAsStateWithLifecycle()
    val song = controller.song
    val lifecycle = LocalLifecycleOwner.current
    val motion = LocalPhysicalMotion.current
    val density = LocalDensity.current
    val view = LocalView.current
    val context = LocalContext.current
    val ebony = remember(context) { PianoWallTextures.load(context, "design/music-space/piano-ebony.jpg") }
    val ivory = remember(context) { PianoWallTextures.load(context, "design/music-space/piano-ivory.jpg") }
    val metal = remember(context) { PianoWallTextures.load(context, "design/materials/aluminum.webp") }

    var exitConfirm by rememberSaveable(controller.song.id) { mutableStateOf(false) }
    var attemptGeneration by rememberSaveable(controller.song.id) { mutableIntStateOf(0) }
    val live = ui.state != PlayState.FINISHED
    val reveal = remember(controller, revealStage) { Animatable(if (revealStage && motion) 0f else 1f) }
    LaunchedEffect(controller, motion, revealStage) {
        if (revealStage && motion) reveal.animateTo(1f, tween(110, easing = AppNavEasing))
        else reveal.snapTo(1f)
    }
    DisposableEffect(view, live) {
        val bars = (view.context as? Activity)?.window?.let { WindowCompat.getInsetsController(it, view) }
        val oldStatus = bars?.isAppearanceLightStatusBars
        val oldNav = bars?.isAppearanceLightNavigationBars
        if (bars != null) {
            bars.isAppearanceLightStatusBars = !live
            bars.isAppearanceLightNavigationBars = !live
        }
        onDispose {
            if (bars != null) {
                if (oldStatus != null) bars.isAppearanceLightStatusBars = oldStatus
                if (oldNav != null) bars.isAppearanceLightNavigationBars = oldNav
            }
        }
    }
    val requestBack: () -> Unit = {
        if (ui.state in listOf(PlayState.PLAYING, PlayState.STARTING, PlayState.PAUSED, PlayState.RECOVERING, PlayState.AUDIO_FAILED)) {
            controller.pause()
            exitConfirm = true
        } else {
            onPrepare()
        }
    }
    BackHandler(onBack = requestBack)
    DisposableEffect(controller, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) controller.pause()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose {
            controller.pause()
            lifecycle.lifecycle.removeObserver(observer)
        }
    }
    LaunchedEffect(controller) {
        while (isActive) {
            withFrameNanos { controller.frame() }
        }
    }

    val stageReady = ui.state !in listOf(PlayState.LOADING, PlayState.FAILED)
    val lightReveal = remember(controller, attemptGeneration) { Animatable(if (motion) 0.28f else 1f) }
    val laneClarity = remember(controller, attemptGeneration) { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(controller, attemptGeneration, stageReady, motion) {
        if (!stageReady) return@LaunchedEffect
        if (!motion) {
            lightReveal.snapTo(1f)
            laneClarity.snapTo(1f)
            return@LaunchedEffect
        }
        launch { lightReveal.animateTo(1f, tween(210, easing = AppNavEasing)) }
        laneClarity.animateTo(1f, tween(170, easing = AppNavEasing))
    }

    val pausedOrExiting = exitConfirm || ui.state in listOf(PlayState.PAUSED, PlayState.READY, PlayState.RECOVERING, PlayState.AUDIO_FAILED)
    val pauseLight by animateFloatAsState(
        targetValue = if (pausedOrExiting) 0.44f else 1f,
        animationSpec = if (motion) tween(160, easing = AppNavEasing) else tween(0),
        label = "pause-light",
    )
    val ambientCues = rememberSectionAmbientCues(song, controller.targets, controller.startUs, controller.endUs)
    val ambientGlow = if (motion && !pausedOrExiting && ui.state == PlayState.PLAYING && ui.countIn == 0) {
        ambientIntensityAt(ambientCues, ui.positionUs)
    } else 0f

    if (ui.state == PlayState.FINISHED) {
        val resultReveal = remember(attemptGeneration, song.id) { Animatable(if (motion) 0f else 1f) }
        val residualGlow = remember(attemptGeneration, song.id) { Animatable(if (motion) 1f else 0f) }
        LaunchedEffect(attemptGeneration, song.id, motion) {
            if (!motion) {
                residualGlow.snapTo(0f)
                resultReveal.snapTo(1f)
                return@LaunchedEffect
            }
            residualGlow.snapTo(1f)
            resultReveal.snapTo(0f)
            launch { residualGlow.animateTo(0.14f, tween(160, easing = AppNavEasing)) }
            delay(85)
            resultReveal.animateTo(1f, tween(200, easing = AppNavEasing))
        }
        Box(Modifier.fillMaxSize().background(ConsoleBaseDark)) {
            SwissPerformanceStage(
                song = song,
                ui = ui,
                motion = motion,
                modifier = Modifier.matchParentSize(),
                stageLightIntensity = residualGlow.value,
                residualGlow = residualGlow.value,
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = resultReveal.value
                        translationY = with(density) { 10.dp.toPx() } * (1f - resultReveal.value)
                    },
            ) {
                RoomWall(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    Column {
                        RoomHeader("本次演奏", onBack = onPrepare, onSpace = onSpace, onSpaceOrigin = onSpaceOrigin)
                        RoomPerformanceResult(
                            song = song,
                            ui = ui,
                            artwork = artwork,
                            preview = controller.options.mode != PlayMode.PLAY,
                            onAgain = {
                                attemptGeneration++
                                controller.restart()
                                controller.start()
                            },
                            onExplore = onExplore,
                            onRetrySave = controller::retrySave,
                            attemptGeneration = attemptGeneration,
                        )
                    }
                }
            }
        }
        return
    }

    val drawLanes = ui.state in listOf(PlayState.PLAYING, PlayState.PAUSED, PlayState.STARTING, PlayState.RECOVERING, PlayState.AUDIO_FAILED)
    val modeLabel = if (controller.options.mode == PlayMode.COMPARE) "体验演奏"
    else if (controller.options.difficulty == 1) "轻松演奏"
    else "标准演奏"
    val progressFraction = (ui.positionUs.toDouble() / song.durationUs.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ConsoleBaseDark)
            .testTag("performance-screen")
            .graphicsLayer { alpha = reveal.value },
    ) {
        SwissPerformanceStage(
            song = song,
            ui = ui,
            motion = motion,
            modifier = Modifier.matchParentSize(),
            stageLightIntensity = if (stageReady) lightReveal.value * pauseLight else 0.38f,
            ambientGlow = ambientGlow,
        )

        Column(Modifier.fillMaxSize()) {
            // 1. Bauhaus Ebony Top Console
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ConsoleSurfaceEbony)
                    .tiledMaterial(ebony, 240.dp, ConsoleSurfaceEbony) {
                        drawRect(Color(0x52101312))
                    }
                    .statusBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MicroScrew()
                    LidAcousticVent(Modifier.weight(1f))
                    MicroScrew()
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "03 / PLAY",
                            color = ConsoleCrimsonDarkText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.6.sp,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { controller.pause() },
                            modifier = Modifier.size(44.dp).testTag("performance-pause"),
                            enabled = ui.state in listOf(PlayState.PLAYING, PlayState.STARTING),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Pause,
                                contentDescription = "暂停",
                                modifier = Modifier.size(20.dp),
                                tint = ConsoleIvory,
                            )
                        }
                    }
                    Text(
                        text = song.title,
                        color = ConsoleIvory,
                        fontSize = 24.sp,
                        lineHeight = 30.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(song.artist, fontSize = 12.sp, color = ConsoleSilverMuted)
                        Text("原速 · $modeLabel", fontSize = 12.sp, color = ConsoleSilverMuted)
                    }
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { progressFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 8.dp)
                            .height(2.dp),
                        color = ConsoleCrimson,
                        trackColor = ConsoleTrackDark,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "${musicTime(ui.positionUs)} / ${musicTime(song.durationUs)}",
                            fontSize = 12.sp,
                            color = ConsoleSilverMuted,
                            style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
                        )
                        Text(
                            text = "%,d".format(ui.score),
                            color = ConsoleIvory,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = (-0.5).sp,
                            style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
                            softWrap = false,
                            modifier = Modifier.testTag("performance-score"),
                        )
                    }
                }
                BauhausCrimsonSeal(horizontalPadding = 11.dp)
            }

            // 2. Central 4-Lane Piano Cavity bounded by 11.dp Ebony Cheeks
            Row(Modifier.fillMaxWidth().weight(1f)) {
                SimpleCharcoalCheek(
                    ebony = ebony,
                    isLeft = true,
                    modifier = Modifier.width(11.dp).fillMaxHeight(),
                )
                Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                    if (drawLanes) {
                        MusicLaneBackdrop(
                            modifier = Modifier.matchParentSize(),
                            stageClarity = laneClarity.value,
                        )
                        MusicLanes(
                            controller = controller,
                            ui = ui,
                            motion = motion,
                            modifier = Modifier.matchParentSize(),
                            stageClarity = laneClarity.value,
                        )
                    }
                    KeybedCavityShadows(Modifier.matchParentSize().zIndex(6f))
                    if (ui.state == PlayState.PLAYING && ui.countIn == 0 && controller.options.mode != PlayMode.LISTEN) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 12.dp, start = 14.dp, end = 14.dp)
                                .zIndex(7f),
                        ) {
                            PerformanceFeedback(ui)
                        }
                    }
                }
                SimpleCharcoalCheek(
                    ebony = ebony,
                    isLeft = false,
                    modifier = Modifier.width(11.dp).fillMaxHeight(),
                )
            }

            // 3. Bauhaus Pedal Console + Navigation Bar Frame
            BauhausBottomFooter(ebony, metal)
            Box(Modifier.fillMaxWidth().background(ConsoleBaseDark).navigationBarsPadding())
        }

        // 4. Stage Overlays (Ivory Key Surface + 1.5.dp Matte Dark Edge + 2.dp Crimson Top Seal)
        if (exitConfirm) {
            PerformancePanel(ivory, motion, Modifier.align(Alignment.Center)) {
                BauhausModalEyebrow("03 / PAUSED")
                Spacer(Modifier.height(6.dp))
                Text("确认退出？", fontSize = 30.sp, fontWeight = FontWeight.Medium, color = ModalCardInk)
                Spacer(Modifier.height(6.dp))
                Text("音乐已暂停。", fontSize = 14.sp, color = ModalCardMuted)
                Spacer(Modifier.height(18.dp))
                PerformancePanelButton("继续演奏", "performance-stay") {
                    exitConfirm = false
                    controller.start()
                }
                TextButton(
                    onClick = {
                        exitConfirm = false
                        onPrepare()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("performance-confirm-exit"),
                ) {
                    Text("返回曲目", color = ModalCardMuted, fontSize = 14.sp)
                }
            }
        } else if (ui.state == PlayState.STARTING || (ui.state == PlayState.PLAYING && ui.countIn > 0)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ModalScrim.copy(alpha = 0.42f)),
                contentAlignment = Alignment.Center,
            ) {
                val cardShape = RoundedCornerShape(2.dp)
                Column(
                    modifier = Modifier
                        .size(124.dp, 132.dp)
                        .shadow(12.dp, cardShape, clip = false)
                        .clip(cardShape)
                        .background(ConsoleIvory)
                        .tiledMaterial(ivory, 260.dp, ConsoleIvory)
                        .border(1.5.dp, ModalCardInk, cardShape)
                        .testTag("performance-countdown"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.fillMaxWidth().height(2.dp).background(ConsoleCrimson))
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text("准备", fontSize = 14.sp, color = ModalCardMuted, fontWeight = FontWeight.Medium)
                        val displayCount = if (ui.state == PlayState.STARTING) 3 else ui.countIn
                        Box(Modifier.size(64.dp, 65.dp), contentAlignment = Alignment.Center) {
                            RollingTabularDigits(
                                valueText = displayCount.toString(),
                                fontSize = 46.sp,
                                color = ModalCardInk,
                                motion = motion,
                                fontWeight = FontWeight.SemiBold,
                                durationMillis = 120,
                                horizontalArrangement = Arrangement.Center,
                            )
                        }
                    }
                }
            }
        } else if (ui.state in listOf(PlayState.READY, PlayState.PAUSED, PlayState.RECOVERING, PlayState.AUDIO_FAILED, PlayState.FAILED)) {
            PerformancePanel(ivory, motion, Modifier.align(Alignment.Center).testTag("performance-pause-panel")) {
                BauhausModalEyebrow(if (ui.state == PlayState.PAUSED) "03 / PAUSED" else "03 / PLAY")
                Spacer(Modifier.height(6.dp))
                Text(
                    text = when (ui.state) {
                        PlayState.PAUSED -> "稍作停留。"
                        PlayState.READY -> "准备演奏"
                        PlayState.RECOVERING -> "正在恢复声音"
                        else -> "暂时无法开始"
                    },
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Medium,
                    color = ModalCardInk,
                )
                Spacer(Modifier.height(16.dp))
                if (ui.message != null) {
                    Text(requireNotNull(ui.message), fontSize = 14.sp, color = ModalCardMuted)
                    Spacer(Modifier.height(12.dp))
                }
                if (ui.state == PlayState.RECOVERING) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = ConsoleCrimson, strokeWidth = 2.dp)
                    Spacer(Modifier.height(12.dp))
                }
                if (ui.state !in listOf(PlayState.FAILED, PlayState.RECOVERING)) {
                    PerformancePanelButton(
                        label = when (ui.state) {
                            PlayState.PAUSED -> "继续演奏"
                            PlayState.AUDIO_FAILED -> "重试恢复声音"
                            else -> "开始演奏"
                        },
                        tag = "performance-start",
                        onClick = controller::start,
                    )
                    if (ui.state == PlayState.PAUSED) {
                        TextButton(
                            onClick = {
                                attemptGeneration++
                                controller.restart()
                                controller.start()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 44.dp)
                                .testTag("performance-restart"),
                        ) {
                            Text("从头开始", color = ModalCardInk, fontSize = 14.sp)
                        }
                    }
                }
                TextButton(
                    onClick = onPrepare,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("performance-back"),
                ) {
                    Text("返回曲目", color = ModalCardMuted, fontSize = 14.sp)
                }
            }
        } else if (ui.state == PlayState.LOADING) {
            PerformancePanel(ivory, motion, Modifier.align(Alignment.Center)) {
                BauhausModalEyebrow("03 / LOADING")
                Spacer(Modifier.height(8.dp))
                Text("准备音乐与场景…", color = ModalCardInk, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun BauhausModalEyebrow(text: String) {
    Text(
        text = text,
        color = ConsoleCrimson,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.6.sp,
    )
}

@Composable
private fun PerformancePanel(
    ivory: androidx.compose.ui.graphics.ImageBitmap?,
    motion: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    var visible by remember { mutableStateOf(!motion) }
    LaunchedEffect(Unit) { visible = true }
    val reveal by animateFloatAsState(
        targetValue = if (visible || !motion) 1f else 0f,
        animationSpec = tween(if (motion) 145 else 0, easing = AppNavEasing),
        label = "pause-panel",
    )
    val slidePx = with(density) { 8.dp.toPx() }
    val cardShape = RoundedCornerShape(2.dp)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ModalScrim.copy(alpha = 0.56f * reveal)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = modifier
                .padding(24.dp)
                .widthIn(max = 360.dp)
                .fillMaxWidth()
                .graphicsLayer {
                    alpha = reveal
                    translationY = slidePx * (1f - reveal)
                }
                .shadow(12.dp, cardShape, clip = false)
                .clip(cardShape)
                .background(ConsoleIvory)
                .tiledMaterial(ivory, 400.dp, ConsoleIvory)
                .border(1.5.dp, ModalCardInk, cardShape),
        ) {
            Box(Modifier.fillMaxWidth().height(2.dp).background(ConsoleCrimson))
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun PerformancePanelButton(label: String, tag: String, onClick: () -> Unit) {
    HorizontalDivider(color = ModalCardDivider)
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .testTag(tag),
        shape = RectangleShape,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.width(3.dp).height(14.dp).background(ConsoleCrimson))
            Text(
                text = label,
                color = ModalCardInk,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

internal fun musicTime(us: Long): String {
    val seconds = us.coerceAtLeast(0) / 1_000_000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
