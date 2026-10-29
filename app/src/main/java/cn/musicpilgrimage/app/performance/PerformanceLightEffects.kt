package cn.musicpilgrimage.app.performance

import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlin.math.*

private val ivory=Color(0xFFFFEED0)
internal fun DrawScope.performanceReceptor(x:Float,line:Float,w:Float,color:Color,pressed:Boolean) {
    if(pressed)drawRect(Brush.verticalGradient(listOf(Color.Transparent,ivory.copy(alpha=.22f)),startY=line-100.dp.toPx(),endY=line+30.dp.toPx()),Offset(x-w/2,line-100.dp.toPx()),Size(w,130.dp.toPx()))
    drawLine(ivory.copy(alpha=if(pressed)1f else .3f),Offset(x-w*.34f,line),Offset(x+w*.34f,line),if(pressed)3.dp.toPx()else .7.dp.toPx())
}
internal fun DrawScope.performanceRibbon(x:Float,top:Float,bottom:Float,w:Float,color:Color,holding:Boolean) {
    val width=w*.72f
    drawRect(Brush.horizontalGradient(listOf(Color(0xFF862D23),Color(0xFFC84A37),Color(0xFF9F362A))),Offset(x-width/2,top),Size(width,(bottom-top).coerceAtLeast(0f)),alpha=if(holding)1f else .88f)
    drawLine(ivory.copy(alpha=.4f),Offset(x,top+5.dp.toPx()),Offset(x,bottom),.7.dp.toPx())
    drawLine(Color(0xFFFFBE95).copy(alpha=.35f),Offset(x-width/2,top),Offset(x-width/2,bottom),.7.dp.toPx())
}
internal fun DrawScope.performanceSustain(x:Float,line:Float,w:Float,color:Color,fraction:Float,timeUs:Long,motion:Boolean) {
    val height=138.dp.toPx()
    drawRect(Brush.verticalGradient(listOf(Color.Transparent,ivory.copy(alpha=.23f)),line-height,line),Offset(x-w/2,line-height),Size(w,height))
    drawLine(ivory.copy(alpha=.8f),Offset(x,line-height*.78f),Offset(x,line),.85.dp.toPx())
    if(motion)repeat(5){i->
        val t=((timeUs/1000f+i*179f)%1200)/1200f
        val point=Offset(x+sin(i*2.1f)*w*.22f,line-t*height)
        drawRect(ivory.copy(alpha=(1-t)*.8f),point,Size(2.dp.toPx(),5.dp.toPx()))
    }
    drawLine(ivory.copy(alpha=.3f),Offset(x-w*.32f,line+22.dp.toPx()),Offset(x+w*.32f,line+22.dp.toPx()),2.dp.toPx())
    drawLine(ivory,Offset(x-w*.32f,line+22.dp.toPx()),Offset(x-w*.32f+w*.64f*fraction.coerceIn(0f,1f),line+22.dp.toPx()),2.dp.toPx())
}
/** Geometric rings and fragments follow actual engine judgments. */
internal fun DrawScope.performanceImpact(event:MusicFeedback,timeUs:Long,x:Float,line:Float,w:Float,color:Color,motion:Boolean) {
    val age=(timeUs-event.atUs)/1_000_000f
    if(age !in 0f..0.65f)return
    val p=(age/.65f).coerceIn(0f,1f)
    val fade=(1-p)*(1-p)
    if(!event.kind.success){drawLine(color.copy(alpha=fade*.8f),Offset(x-w*.25f,line+5.dp.toPx()),Offset(x+w*.25f,line+5.dp.toPx()),1.dp.toPx());return}
    val complete=event.kind==FeedbackKind.HOLD_COMPLETE
    val flash=(1-age/.15f).coerceIn(0f,1f)
    drawRect(Brush.verticalGradient(listOf(Color.Transparent,ivory.copy(alpha=flash*.5f)),line-55.dp.toPx(),line),Offset(x-w*.4f,line-55.dp.toPx()),Size(w*.8f,55.dp.toPx()))
    drawLine(ivory.copy(alpha=flash),Offset(x-w*.42f,line),Offset(x+w*.42f,line),3.dp.toPx())
    if(!motion)return
    repeat(if(complete)3 else 2){i->
        val t=((p-i*.08f)/.8f).coerceIn(0f,1f)
        val width=w*(.7f+t*.95f);val height=(9+26*t).dp.toPx()
        drawRect(ivory.copy(alpha=fade*(1-i*.2f)),Offset(x-width/2,line-height/2),Size(width,height),style=Stroke((if(i==0)1.2f else .7f).dp.toPx()))
    }
    repeat(if(complete)12 else 9){i->
        val angle=(-160+i*17)*PI/180
        val distance=(8f+(1-(1-p).pow(3))*(31f+(i%3)*12)).dp.toPx()
        val point=Offset(x+(cos(angle)*distance).toFloat(),line+(sin(angle)*distance).toFloat()+p*p*11.dp.toPx())
        val width=(if(i%3==0)1.5f else 3f).dp.toPx();val height=(if(i%3==0)8f else 3f).dp.toPx()
        rotate((i*23f+p*55),point){drawRect((if(i%3==0)Color(0xFFE18A68)else ivory).copy(alpha=fade),point,Size(width,height))}
    }
}
