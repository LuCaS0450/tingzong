package cn.musicpilgrimage.app.performance

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import kotlin.math.*

/** Approved shared stage construction, with an individual material arrangement per song. */
private data class StageMaterial(val kind:Int,val objectColor:Color,val light:Color)
private fun stageMaterial(id:String):StageMaterial = when(id) {
    "greatest-works"->StageMaterial(0,Color(0xFFBD3C2C),Color(0xFFF9F3E6))
    "love-confession"->StageMaterial(1,Color(0xFF597483),Color(0xFFF0F6F2))
    "common-jasmine-orange"->StageMaterial(2,Color(0xFF657450),Color(0xFFF8F6E7))
    "secret"->StageMaterial(3,Color(0xFF4A4A43),Color(0xFFF4F0E7))
    "wont-cry"->StageMaterial(4,Color(0xFF778988),Color(0xFFEEF3EE))
    "she-says"->StageMaterial(5,Color(0xFFAC6754),Color(0xFFF7EEE4))
    "river-south"->StageMaterial(6,Color(0xFF5E8277),Color(0xFFF0F4E9))
    "no-turning-back"->StageMaterial(7,Color(0xFFB14F38),Color(0xFFF7EEE1))
    "under-mount-fuji"->StageMaterial(8,Color(0xFF838A86),Color(0xFFF1F5F1))
    "long-time-no-see"->StageMaterial(9,Color(0xFF846A53),Color(0xFFF5F0E7))
    "you-complete-me"->StageMaterial(10,Color(0xFF6D8994),Color(0xFFF0F5F3))
    "he-r"->StageMaterial(11,Color(0xFFAC8D4D),Color(0xFFF7F2E3))
    "love-me-or-him"->StageMaterial(12,Color(0xFF925C53),Color(0xFFF4EEE8))
    "wedding-card-street"->StageMaterial(13,Color(0xFFB55442),Color(0xFFF8F3E8))
    "hotel-california"->StageMaterial(14,Color(0xFFB58A4A),Color(0xFFF9F0DC))
    else->StageMaterial(Math.floorMod(id.hashCode(),15),Color(0xFF7D8370),Color(0xFFF6F3E9))
}

@Composable internal fun SwissPerformanceStage(song:SongPackage,ui:PerformanceUi,motion:Boolean,modifier:Modifier=Modifier) {
    val material=remember(song.id){stageMaterial(song.id)}
    Canvas(modifier) {
        val u=size.width/360f
        drawRect(Brush.radialGradient(listOf(material.light,Color(0xFFE5E6DB),Color(0xFFC9CDBF)),center=Offset(size.width*.16f,size.height*.15f),radius=size.height*.9f))
        val feedback=ui.feedbacks.lastOrNull{it.kind.success}
        val age=feedback?.let{(ui.positionUs-it.atUs)/1_000_000f}?:1f
        if(motion&&age in 0f..0.5f)drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha=(1-age*2)*.1f),Color.Transparent),center=Offset(size.width*.58f,size.height*.3f),radius=size.width*.6f),radius=size.width*.6f,center=Offset(size.width*.58f,size.height*.3f))
        val artBottom=size.height*.43f-6*u
        val artScale=min(u,size.height*.19f/155f)
        drawOval(Brush.radialGradient(listOf(Color(0xFF4E5443).copy(alpha=.15f),Color.Transparent),center=Offset(size.width*.54f,artBottom-2*u),radius=110*u),Offset(size.width*.23f,artBottom-8*u),Size(size.width*.64f,18*u))
        withTransform({translate(size.width/2-130*artScale,artBottom-153*artScale);scale(artScale,artScale,Offset.Zero)}) {
            stageObject(material)
            drawRect(Brush.verticalGradient(listOf(Color(0xFFDDC9AA),Color(0xFFB69C79),Color(0xFF9C8568))),Offset(15f,146f),Size(230f,8f))
            drawLine(Color.White.copy(alpha=.45f),Offset(15f,146f),Offset(245f,146f),1f)
        }
        val left=17*u;val top=size.height*.43f;val width=size.width-34*u;val height=size.height*.515f
        repeat(4){i->drawRect(Color(0xFF2A3322).copy(alpha=.018f*(4-i)),Offset(left+(i+2)*u,top+(i+4)*u),Size(width,height))}
        drawRect(Brush.linearGradient(listOf(Color(0xFF3D4539),Color(0xFF252E26),Color(0xFF333D2E)),Offset(left,top),Offset(size.width,size.height)),Offset(left,top),Size(width,height))
        drawLine(Color(0xFFEAEDCF).copy(alpha=.4f),Offset(left,top),Offset(left+width,top),.75f*u)
        drawLine(Color(0xFFEAEDCF).copy(alpha=.15f),Offset(left,top),Offset(left,top+height),.75f*u)
    }
}

private fun DrawScope.stageObject(m:StageMaterial) {
    val c=m.objectColor;val dark=Color(0xFF273027);val gold=Color(0xFFB89958)
    fun block(x:Float,y:Float,w:Float,h:Float,color:Color=c) {
        drawRect(Brush.horizontalGradient(listOf(lerp(color,Color.White,.22f),color,lerp(color,Color.Black,.2f))),Offset(x,y),Size(w,h))
        drawPath(Path().apply{moveTo(x,y);lineTo(x+7,y-6);lineTo(x+w+7,y-6);lineTo(x+w,y);close()},lerp(color,Color.White,.35f))
        drawLine(Color.White.copy(alpha=.3f),Offset(x+.8f,y+1),Offset(x+.8f,y+h),.7f)
    }
    fun ring(x:Float,y:Float,r:Float,color:Color=c) {
        drawCircle(lerp(color,Color.Black,.25f),r,Offset(x+5,y+3))
        drawCircle(Brush.radialGradient(listOf(lerp(color,Color.White,.25f),color,lerp(color,Color.Black,.22f)),Offset(x-r*.35f,y-r*.4f),r*1.6f),r,Offset(x,y))
        drawCircle(lerp(color,Color.Black,.4f),r*.43f,Offset(x+2,y+2))
        drawCircle(m.light,r*.39f,Offset(x-1,y-1))
    }
    fun orb(x:Float,y:Float,r:Float,color:Color=c) {
        drawCircle(Brush.radialGradient(listOf(Color(0xFFEEF7F3),lerp(color,Color.White,.28f),color,lerp(color,Color.Black,.35f)),Offset(x-r*.35f,y-r*.4f),r*1.6f),r,Offset(x,y))
        drawOval(Color.White.copy(alpha=.35f),Offset(x-r*.35f,y-r*.62f),Size(r*.35f,r*.13f))
    }
    fun arch(x:Float,y:Float,w:Float,h:Float,color:Color=c) {
        val path=Path().apply{moveTo(x,y+h);lineTo(x,y+w/2);quadraticTo(x,y,x+w/2,y);quadraticTo(x+w,y,x+w,y+w/2);lineTo(x+w,y+h)}
        drawPath(path,lerp(color,Color.Black,.15f),style=Stroke(8f))
        drawPath(path,lerp(color,Color.White,.2f),style=Stroke(5f))
    }
    fun fold(x:Float,y:Float,w:Float,h:Float,color:Color=c) {
        repeat(3){i->val a=x+w*i/3;val b=a+w/3
            drawPath(Path().apply{moveTo(a,y+if(i%2==0)10 else 0);lineTo(b,y+if(i%2==0)0 else 10);lineTo(b,y+h-if(i%2==0)10 else 0);lineTo(a,y+h-if(i%2==0)0 else 10);close()},lerp(color,Color.White,if(i==1).05f else .3f))
            drawLine(Color.White.copy(alpha=.2f),Offset(a+8,y+h*.42f),Offset(b-3,y+h*.32f),.7f)
            drawLine(Color.White.copy(alpha=.2f),Offset(a+8,y+h*.68f),Offset(b-3,y+h*.58f),.7f)
        }
    }
    when(m.kind) {
        0->{block(35f,38f,43f,108f,dark);ring(172f,83f,61f)}
        1->{arch(45f,37f,81f,109f,Color(0xFF7C908E));orb(174f,85f,60f)}
        2->{fold(50f,30f,142f,116f);drawRoundRect(Brush.horizontalGradient(listOf(Color(0xFFD8B76B),gold,Color(0xFF876335))),Offset(197f,81f),Size(35f,65f),androidx.compose.ui.geometry.CornerRadius(15f,15f))}
        3->{fold(48f,43f,119f,103f);block(182f,57f,30f,89f,gold);drawRect(dark,Offset(42f,139f),Size(135f,7f))}
        4->{block(72f,28f,11f,118f);block(191f,28f,11f,118f);block(83f,28f,108f,10f);block(83f,136f,108f,10f);drawRect(Brush.linearGradient(listOf(Color.White.copy(alpha=.25f),c.copy(alpha=.08f))),Offset(83f,38f),Size(108f,98f))}
        5->{arch(126f,26f,82f,120f,gold);drawPath(Path().apply{moveTo(45f,146f);lineTo(87f,39f);lineTo(121f,146f);close()},Brush.linearGradient(listOf(lerp(c,Color.White,.2f),c,lerp(c,Color.Black,.18f))))}
        6->{arch(52f,72f,77f,74f);arch(131f,72f,77f,74f);block(43f,65f,175f,10f,gold);block(58f,53f,4f,21f,dark);block(200f,53f,4f,21f,dark)}
        7->{block(43f,98f,52f,48f);block(98f,68f,52f,78f);block(153f,29f,52f,117f);drawCircle(gold,16f,Offset(210f,124f))}
        8->{drawPath(Path().apply{moveTo(41f,146f);lineTo(129f,29f);lineTo(219f,146f);close()},Brush.horizontalGradient(listOf(lerp(c,Color.White,.25f),c,lerp(c,Color.Black,.18f))));drawPath(Path().apply{moveTo(104f,63f);lineTo(129f,29f);lineTo(157f,65f);lineTo(139f,58f);lineTo(124f,70f);close()},Color(0xFFF0F0E5));orb(212f,112f,27f,Color(0xFF9EB2B5))}
        9->{block(52f,27f,9f,119f,dark);block(178f,27f,9f,119f,dark);block(61f,27f,117f,9f,dark);block(61f,137f,117f,9f,dark);block(118f,36f,8f,101f,dark);block(61f,84f,117f,6f,dark);orb(201f,123f,22f,c)}
        10->{ring(111f,95f,47f,dark);ring(168f,79f,47f,c)}
        11->{block(51f,123f,68f,23f,dark);block(61f,101f,48f,22f,lerp(dark,Color.White,.1f));drawRoundRect(Brush.horizontalGradient(listOf(lerp(c,Color.White,.4f),c,lerp(c,Color.Black,.25f))),Offset(151f,49f),Size(51f,97f),androidx.compose.ui.geometry.CornerRadius(22f,18f));drawRect(gold,Offset(162f,31f),Size(29f,24f))}
        12->{block(65f,39f,49f,107f,c);block(136f,39f,49f,107f,gold);drawCircle(dark,16f,Offset(219f,129f))}
        13->{block(49f,59f,163f,87f,Color(0xFFD8CCB0));drawPath(Path().apply{moveTo(49f,59f);lineTo(132f,115f);lineTo(212f,59f)},Color(0xFFAA9B7E),style=Stroke(1f));drawCircle(c,14f,Offset(132f,109f));block(218f,112f,12f,34f,c)}
        14->{arch(55f,23f,95f,123f,c);block(47f,139f,111f,7f,gold);orb(196f,102f,43f,dark)}
    }
}
