package cn.musicpilgrimage.app.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.*
import kotlin.math.*
import kotlinx.coroutines.delay
import cn.musicpilgrimage.app.ui.LocalPhysicalMotion

/** Square steps, not diagonal cuts or rounded corners. */
internal class PixelShape(
    topStart:CornerSize,topEnd:CornerSize,bottomEnd:CornerSize,bottomStart:CornerSize
):CornerBasedShape(topStart,topEnd,bottomEnd,bottomStart) {
    constructor(corner:Dp=4.dp):this(CornerSize(corner),CornerSize(corner),CornerSize(corner),CornerSize(corner))
    override fun createOutline(size:Size,topStart:Float,topEnd:Float,bottomEnd:Float,bottomStart:Float,layoutDirection:LayoutDirection):Outline {
        val w=size.width;val h=size.height
        val cap=min(w,h)/4f
        val a=topStart.coerceAtMost(cap);val b=topEnd.coerceAtMost(cap)
        val c=bottomEnd.coerceAtMost(cap);val d=bottomStart.coerceAtMost(cap)
        return Outline.Generic(Path().apply {
            moveTo(a,0f);lineTo(w-b,0f)
            lineTo(w-b,b/2);lineTo(w-b/2,b/2);lineTo(w-b/2,b);lineTo(w,b)
            lineTo(w,h-c);lineTo(w-c/2,h-c);lineTo(w-c/2,h-c/2);lineTo(w-c,h-c/2);lineTo(w-c,h)
            lineTo(d,h);lineTo(d,h-d/2);lineTo(d/2,h-d/2);lineTo(d/2,h-d);lineTo(0f,h-d)
            lineTo(0f,a);lineTo(a/2,a);lineTo(a/2,a/2);lineTo(a,a/2);close()
        })
    }
    override fun copy(topStart:CornerSize,topEnd:CornerSize,bottomEnd:CornerSize,bottomStart:CornerSize)=
        PixelShape(topStart,topEnd,bottomEnd,bottomStart)
}

internal fun Modifier.pixelShadow(shape:Shape=PixelShape(),pressed:Boolean=false):Modifier=drawBehind {
    val outline=shape.createOutline(size,layoutDirection,this)
    translate(if(pressed)1.dp.toPx() else 3.dp.toPx(),if(pressed)1.dp.toPx() else 4.dp.toPx()) {
        drawOutline(outline,AppArt.Ink.copy(alpha=.80f))
    }
}

private val sprites=mapOf(
    "Explore" to listOf("0000011111100000","0001100000011000","0010000000000100","0100000000000010","0100000001110010","1000000111100001","1000001111000001","1000011110000001","1000111100000001","1001111000000001","0101110000000010","0100000000000010","0010000000000100","0001100000011000","0000011111100000","0000000000000000"),
    "Piano" to listOf("0000000000000000","0111111111111110","0100000000000010","0100000000000010","0111111111111110","0101010101010110","0101010101010110","0101010101010110","0101010101010110","0100010001000110","0100010001000110","0100010001000110","0111111111111110","0000000000000000","0000000000000000","0000000000000000"),
    "Search" to listOf("0000111100000000","0011000011000000","0100000000100000","0100000000100000","1000000000010000","1000000000010000","1000000000010000","1000000000010000","0100000000100000","0100000000100000","0011000011100000","0000111100110000","0000000000011000","0000000000001100","0000000000000110","0000000000000000"),
    "Close" to listOf("0000000000000000","0011000000001100","0011100000011100","0001110000111000","0000111001110000","0000011111100000","0000001111000000","0000001111000000","0000011111100000","0000111001110000","0001110000111000","0011100000011100","0011000000001100","0000000000000000","0000000000000000","0000000000000000"),
    "Pause" to List(16){if(it in 2..13)"0001110000111000" else "0000000000000000"},
    "PlayArrow" to listOf("0000000000000000","0001100000000000","0001111000000000","0001111110000000","0001111111100000","0001111111111000","0001111111111100","0001111111111100","0001111111111000","0001111111100000","0001111110000000","0001111000000000","0001100000000000","0000000000000000","0000000000000000","0000000000000000"),
    "PersonOutline" to listOf("0000011110000000","0001100001100000","0001000000100000","0001000000100000","0001000000100000","0001100001100000","0000011110000000","0000000000000000","0000111111000000","0011000000110000","0100000000001000","0100000000001000","0100000000001000","0111111111111000","0000000000000000","0000000000000000"),
    "Bookmarks" to listOf("0001111111111000","0001000000001000","0111111111001000","0100000001001000","0100000001001000","0100000001001000","0100000001001000","0100000001001000","0100000001001000","0100000001111000","0100110001000000","0101001001000000","0110000101000000","0100000011000000","0000000000000000","0000000000000000")
)

/** Sixteen-pixel sprites; other existing vectors are rasterized onto the same tiny grid. */
@Composable internal fun PixelIcon(imageVector:ImageVector,description:String?,modifier:Modifier=Modifier,tint:Color=LocalContentColor.current) {
    val key=imageVector.name.substringAfterLast('.')
    val sprite=sprites[key] ?: if(key=="Person")sprites["PersonOutline"] else null
    val painter=rememberVectorPainter(imageVector)
    val raster=remember(imageVector){ImageBitmap(16,16)}
    val rasterCanvas=remember(raster){androidx.compose.ui.graphics.Canvas(raster)}
    val scope=remember{CanvasDrawScope()}
    Canvas(modifier.size(24.dp).then(if(description==null)Modifier else Modifier.semantics{contentDescription=description})) {
        val u=floor(size.minDimension/16f).coerceAtLeast(1f)
        val origin=Offset(floor((size.width-u*16)/2),floor((size.height-u*16)/2))
        if(sprite!=null) sprite.forEachIndexed {y,row->row.forEachIndexed {x,v->
            if(v=='1')drawRect(tint,origin+Offset(x*u,y*u),Size(u,u))
        }} else {
            rasterCanvas.drawRect(androidx.compose.ui.geometry.Rect(0f,0f,16f,16f),Paint().apply{blendMode=BlendMode.Clear})
            scope.draw(Density(1f),LayoutDirection.Ltr,rasterCanvas,Size(16f,16f)) {with(painter){draw(Size(16f,16f))}}
            drawImage(raster,dstOffset=IntOffset(origin.x.toInt(),origin.y.toInt()),
                dstSize=IntSize((u*16).toInt(),(u*16).toInt()),colorFilter=ColorFilter.tint(tint),filterQuality=FilterQuality.None)
        }
    }
}

/** Small coordinate motif. Its sparse blocks are interface decoration, not a real location. */
@Composable internal fun PixelSignal(modifier:Modifier=Modifier,color:Color=AppArt.Blue) {
    Canvas(modifier.size(28.dp,20.dp)) {
        val u=floor(size.width/14f).coerceAtLeast(1f)
        listOf(2 to 3,5 to 6,8 to 9,11 to 5).forEach{(x,h)->
            drawRect(color,Offset(x*u,(10-h)*u),Size(2*u,h*u))
        }
    }
}

@Composable internal fun PixelLoader(modifier:Modifier=Modifier,color:Color=AppArt.Blue,strokeWidth:Dp=2.dp) {
    val motion=LocalPhysicalMotion.current
    var phase by remember{mutableIntStateOf(0)}
    LaunchedEffect(motion){if(motion)while(true){delay(120);phase=(phase+1)%8}}
    Canvas(modifier.size(24.dp)) {
        val u=floor(size.minDimension/12f).coerceAtLeast(1f)
        listOf(5 to 0,9 to 2,10 to 5,9 to 9,5 to 10,1 to 9,0 to 5,1 to 2).forEachIndexed{i,(x,y)->
            drawRect(color.copy(alpha=if(motion).25f+((i+phase)%8)*.1f else .8f),Offset(x*u,y*u),Size(2*u,2*u))
        }
    }
}

