package cn.musicpilgrimage.app.ui.room

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import kotlin.math.roundToInt

private object RackImages {
    private val images=mutableMapOf<String,ImageBitmap>()
    @Synchronized fun load(context:Context,name:String):ImageBitmap = images.getOrPut(name) {
        requireNotNull(context.assets.open("design/music-space/$name").use{BitmapFactory.decodeStream(it)}).asImageBitmap()
    }
}

/** Only the wall retains acrylic brushwork; soften the heading's right side locally. */
@Composable internal fun RoomShelfWall(modifier:Modifier,content:@Composable BoxScope.()->Unit={}) {
    val context=LocalContext.current
    val wall=remember(context){ShaderBrush(ImageShader(RackImages.load(context,"wall.webp"),TileMode.Repeated,TileMode.Repeated))}
    Box(modifier.drawBehind {
        val u=size.width/360f
        drawRect(RoomColors.Wall)
        drawRect(wall,alpha=.68f)
        drawRect(Brush.verticalGradient(listOf(RoomColors.Wall.copy(alpha=.53f),Color.Transparent),endY=54f*u))
        for(x in listOf(.54f,.87f))drawRect(Brush.radialGradient(
            listOf(RoomColors.Wall.copy(alpha=.72f),Color.Transparent),
            center=Offset(size.width*x,47f*u),radius=130f*u
        ),topLeft=Offset(size.width*.34f,0f),size=Size(size.width*.66f,115f*u))
    },content=content)
}

/** Geometry and satin-metal shading follow the approved 338px-wide front-end rack. */
@Composable internal fun RoomRecordRack(modifier:Modifier,bearings:List<Float>,backPlate:Boolean=false) {
    val context=LocalContext.current
    val wood=remember(context){RackImages.load(context,"shelf-natural.png")}
    val shadows=Modifier.drawWithCache {
        val u=size.width/338f
        val pad=ceil(24f*u).toInt()
        val bitmap=Bitmap.createBitmap(ceil(size.width).toInt()+pad*2,ceil(size.height).toInt()+pad*2,Bitmap.Config.ARGB_8888)
        val canvas=AndroidCanvas(bitmap)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.translate(pad.toFloat(),pad.toFloat())
        paint.color=android.graphics.Color.argb(44,35,43,36)
        paint.maskFilter=BlurMaskFilter(1.5f*u,BlurMaskFilter.Blur.NORMAL)
        for(x in listOf(7f*u,size.width-12f*u))canvas.drawRoundRect(x+u,2f*u,x+6f*u,size.height,2.5f*u,2.5f*u,paint)
        for(bearing in bearings) {
            paint.color=android.graphics.Color.argb(64,51,41,30)
            paint.maskFilter=BlurMaskFilter(6f*u,BlurMaskFilter.Blur.NORMAL)
            canvas.drawRect(2f*u,(bearing+5f)*u,size.width+2f*u,(bearing+19f)*u,paint)
        }
        val image=bitmap.asImageBitmap()
        onDrawBehind {drawImage(image,topLeft=Offset(-pad.toFloat(),-pad.toFloat()))}
    }
    Canvas(modifier.then(shadows)) {
        val u=size.width/338f
        val postHeight=size.height-3.5f*u
        for(x in listOf(7f*u,size.width-12f*u)) {
            drawRoundRect(Brush.horizontalGradient(
                0f to Color(0xFF6A7476),.19f to Color(0xFFAFB8BA),.35f to Color(0xFFDCE1E1),
                .58f to Color(0xFFBCC3C4),.81f to Color(0xFF919B9E),1f to Color(0xFF6D777A),
                startX=x,endX=x+5f*u
            ),topLeft=Offset(x,0f),size=Size(5f*u,postHeight),cornerRadius=CornerRadius(2.5f*u))
            drawCircle(Brush.radialGradient(
                listOf(Color(0xFFE4E8E3),Color(0xFFABB5B0),Color(0xFF7C8783)),
                center=Offset(x+1.5f*u,postHeight-u),radius=5f*u
            ),radius=3.5f*u,center=Offset(x+2.5f*u,postHeight))
        }
        if(backPlate) {
            val y=(bearings.last()+17f)*u
            drawRect(Brush.verticalGradient(listOf(Color(0xFF244E64),Color(0xFF24465A),Color(0xFF183949)),startY=y,endY=y+20f*u),Offset(9f*u,y),Size(size.width-18f*u,20f*u))
            drawLine(Color(0xFF849C9D),Offset(9f*u,y),Offset(size.width-9f*u,y),.7f*u)
        }
        for(bearing in bearings) {
            val y=bearing*u
            // Use opaque inner grain so the plank has exact straight, square ends, with no baked bevel.
            drawImage(wood,srcOffset=IntOffset(48,634),srcSize=IntSize(1678,60),
                dstOffset=IntOffset(0,y.roundToInt()),dstSize=IntSize(size.width.roundToInt(),(14f*u).roundToInt().coerceAtLeast(1)))
            drawLine(Color(0xFFE9BD83).copy(alpha=.4f),Offset(0f,y),Offset(size.width,y),.6f*u)
            drawLine(Color(0xFF4C2E1E).copy(alpha=.5f),Offset(0f,y+14f*u),Offset(size.width,y+14f*u),.6f*u)
            for(x in listOf(5f*u,size.width-5f*u))drawCircle(Brush.radialGradient(
                listOf(Color(0xFFE5E8E4),Color(0xFFADB6B3),Color(0xFF727D7B)),center=Offset(x-.5f*u,y+10.5f*u),radius=2.5f*u
            ),radius=1.65f*u,center=Offset(x,y+11f*u))
        }
    }
}
