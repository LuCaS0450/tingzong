package cn.musicpilgrimage.app.performance

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.musicpilgrimage.app.ui.LocalPhysicalMotion
import kotlin.math.*

private val noteColor=Color(0xFFFFF0D5)
private val holdColor=Color(0xFFC64A37)
private val laneColors=List(4){noteColor}
private val night=Color(0xFF273025)
private val missColor=Color(0xFFFFA69B)

/** A single translucent backdrop spans the scene, independent of the touch/judgment area. */
@Composable internal fun MusicLaneBackdrop(modifier:Modifier) {
    Canvas(modifier) {
        val w=size.width/4
        for(lane in 0..3) {
            drawRect(Brush.horizontalGradient(listOf(Color.White.copy(alpha=.025f),Color.Transparent)),Offset(lane*w,0f),Size(w,size.height))
            drawLine(Color.White.copy(alpha=.13f),Offset(lane*w,0f),Offset(lane*w,size.height),.6.dp.toPx())
        }
        drawLine(Color.White.copy(alpha=.13f),Offset(size.width,0f),Offset(size.width,size.height),.6.dp.toPx())
    }
}

/** Bright input cues stay on the lower playfield; the upper artwork remains unobstructed. */
@Composable internal fun MusicLanes(controller:PerformanceController,ui:PerformanceUi,motion:Boolean,modifier:Modifier) {
    val listen=controller.options.mode==PlayMode.LISTEN
    val view=LocalView.current
    val navigationBottom=WindowInsets.navigationBars.getBottom(LocalDensity.current).toFloat()
    var pressed by remember(controller){mutableStateOf(emptySet<Int>())}
    val held=controller.targets.filter{ui.judgments.getOrNull(it.id)==Judgment.HOLDING}
    val acceptsInput=!listen&&ui.state==PlayState.PLAYING&&ui.countIn==0
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val w=size.width/4
            // Paint through the system bar, keeping the approved judgment line in the safe area.
            val line=(size.height-navigationBottom).coerceAtLeast(0f)*.82f
            val travel=line/1_800_000f
            for(lane in 0..3) {
                val x=(lane+.5f)*w
                performanceReceptor(x,line,w,laneColors[lane],lane in pressed)
            }
            drawLine(noteColor.copy(alpha=.09f),Offset(0f,line),Offset(size.width,line),6.dp.toPx())
            drawLine(Color(0xFFF7E8C5).copy(alpha=.80f),Offset(0f,line),Offset(size.width,line),.9.dp.toPx())

            clipRect {
                if(!listen)controller.targets.forEach {t->
                    val judgment=ui.judgments.getOrNull(t.id) ?: Judgment.WAITING
                    if(judgment!=Judgment.WAITING && judgment!=Judgment.HOLDING)return@forEach
                    val holding=judgment==Judgment.HOLDING
                    val y=if(holding)line else line-(t.onsetUs-ui.positionUs)*travel
                    val tailY=line-(t.endUs-ui.positionUs)*travel
                    val h=15.dp.toPx()
                    if(y< -h || tailY>size.height)return@forEach
                    val x=(t.lane+.5f)*w
                    val color=if(t.hold)holdColor else noteColor
                    if(t.hold && y>tailY) {
                        val top=tailY.coerceAtLeast(-h)
                        performanceRibbon(x,top,y,w,color,holding)
                    }
                    if(holding) {
                        val fraction=ui.holdFractions.getOrElse(t.id){0f}
                        performanceSustain(x,line,w,color,fraction,ui.positionUs,motion)
                    }
                    noteHead(x,y,w,h,color,t.hold,holding)
                }
                ui.feedbacks.forEach {event->
                    val x=(event.lane+.5f)*w
                    val color=if(event.kind.success)laneColors[event.lane] else missColor
                    performanceImpact(event,ui.positionUs,x,line,w,color,motion)
                }
            }
        }
        Row(Modifier.fillMaxSize().navigationBarsPadding()) {
            for(lane in 0..3)Box(Modifier.weight(1f).fillMaxHeight().testTag("performance-lane-$lane")
                .semantics {
                    contentDescription="轨道 ${lane+1}，轻点短音，按住长音"
                    stateDescription=if(held.any{it.lane==lane})"长按中" else "等待音符"
                }
                .pointerInput(controller,lane,view,acceptsInput) {if(acceptsInput)awaitEachGesture {
                    val down=awaitFirstDown(requireUnconsumed=false)
                    down.consume();pressed=pressed+lane
                    if(controller.down(lane))view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    try {
                        // Keep ownership until this finger lifts; drifting does not cancel a hold.
                        do {
                            val change=awaitPointerEvent().changes.firstOrNull{it.id==down.id} ?: break
                            change.consume()
                        } while(change.pressed)
                    } finally {pressed=pressed-lane;controller.up(lane)}
                }},contentAlignment=Alignment.BottomCenter) {
                val hold=held.firstOrNull{it.lane==lane}
                if(hold!=null)Text("按住 ${(ui.holdFractions.getOrElse(hold.id){0f}*100).toInt()}%",
                    Modifier.padding(bottom=20.dp).testTag("performance-hold-$lane"),color=laneColors[lane],fontSize=14.sp,fontWeight=FontWeight.Bold)
                else Text((lane+1).toString(),Modifier.padding(bottom=20.dp),color=Color.White.copy(alpha=.55f),fontSize=14.sp)
            }
        }
    }
}

/** Positioned above the playfield so judgments cannot cover approaching notes. */
@Composable internal fun PerformanceFeedback(ui:PerformanceUi) {
    val held=ui.judgments.any{it==Judgment.HOLDING}
    val feedback=ui.feedbacks.lastOrNull()
    val label=when {held->"持续按住";feedback!=null->feedback.label;else->null}
    val motion=LocalPhysicalMotion.current
    val pulse=remember{Animatable(1f)}
    LaunchedEffect(ui.combo,motion){if(motion&&ui.combo>0){pulse.snapTo(1.1f);pulse.animateTo(1f,tween(160))}else pulse.snapTo(1f)}
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(ui.combo.toString(),fontSize=37.sp,fontWeight=FontWeight.Normal,color=Color(0xFFF8F3E5),modifier=Modifier.graphicsLayer{scaleX=pulse.value;scaleY=pulse.value})
        Text("COMBO",fontSize=11.sp,color=Color(0xFFF8F3E5))
        Spacer(Modifier.weight(1f))
        Text(label.orEmpty(),color=if(held || feedback?.kind?.success==true)Color(0xFFFFEED0) else missColor,fontSize=14.sp,fontWeight=FontWeight.Medium,modifier=Modifier.testTag("performance-feedback"))
    }
}

private fun DrawScope.noteHead(x:Float,y:Float,w:Float,h:Float,color:Color,hold:Boolean,holding:Boolean) {
    val width=w*.72f;val left=x-width/2;val top=y-h/2
    drawRect(Color.Black.copy(alpha=.25f),Offset(left,top+3.dp.toPx()),Size(width,h))
    drawRect(Brush.verticalGradient(if(hold)listOf(Color(0xFFD96C4D),color)else listOf(Color(0xFFFFF9EC),Color(0xFFD8D0B9))),Offset(left,top),Size(width,h))
    drawLine(Color.White.copy(alpha=.75f),Offset(left,top),Offset(left+width,top),.8.dp.toPx())
    if(holding)drawLine(Color.White.copy(alpha=.8f),Offset(left,top+h),Offset(left+width,top+h),1.5.dp.toPx())
}
