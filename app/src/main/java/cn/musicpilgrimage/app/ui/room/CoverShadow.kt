package cn.musicpilgrimage.app.ui.room

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/** One upper-left light source, with a broad cast shadow and a tighter contact shadow. */
internal fun Modifier.coverShadow(): Modifier = drawWithCache {
    val small = size.minDimension < 96.dp.toPx()
    val blur = (if (small) 3.1.dp else 5.0.dp).toPx()
    val dx = (if (small) 1.5.dp else 2.8.dp).toPx()
    val dy = (if (small) 2.1.dp else 3.8.dp).toPx()
    val pad = ceil(blur * 2 + dy + 2.dp.toPx()).toInt()
    val bitmap = Bitmap.createBitmap(
        ceil(size.width).toInt().coerceAtLeast(1) + pad * 2,
        ceil(size.height).toInt().coerceAtLeast(1) + pad * 2,
        Bitmap.Config.ARGB_8888
    )
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    fun layer(x:Float,y:Float,radius:Float,alpha:Int) {
        paint.color = android.graphics.Color.argb(alpha, 30, 39, 30)
        paint.maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
        canvas.drawRect(pad+x,pad+y,pad+x+size.width,pad+y+size.height,paint)
    }
    layer(dx,dy,blur,if(small)65 else 76)
    layer(.8.dp.toPx(),1.1.dp.toPx(),1.2.dp.toPx(),if(small)69 else 92)
    val image = bitmap.asImageBitmap()
    onDrawBehind { drawImage(image, topLeft = Offset(-pad.toFloat(), -pad.toFloat())) }
}

/** A physical paper sleeve belongs to the rack; other covers retain a plain edge. */
internal fun Modifier.coverSleeve(): Modifier = drawWithCache {
    val u=size.width/141f
    val edge=2.6f*u
    val rim=.65f*u
    onDrawWithContent {
        drawRect(Color(0xFFDDD5C6),topLeft=Offset(edge,edge*.55f),size=size)
        drawRect(Brush.horizontalGradient(listOf(Color(0xFFF1EBDD),Color(0xFFD0C6B4),Color(0xFFA89D89)),startX=size.width,endX=size.width+edge),
            topLeft=Offset(size.width,edge*.55f),size=Size(edge,size.height))
        drawLine(Color(0xFFF8F2E4),Offset(size.width+edge*.42f,edge*.55f),Offset(size.width+edge*.42f,size.height+edge*.55f),.45f*u)
        drawLine(Color(0xFF756B59),Offset(size.width+edge,edge*.55f),Offset(size.width+edge,size.height+edge*.55f),.45f*u)
        drawContent()
        drawRect(Color(0xFFF0EADC),size=Size(size.width,rim))
        drawRect(Color(0xFFECE5D7),size=Size(rim,size.height))
        drawLine(Color(0xFF756B59).copy(alpha=.65f),Offset(size.width-rim*.5f,rim),Offset(size.width-rim*.5f,size.height),.45f*u)
        drawRect(Color(0xFFDAD0BE),topLeft=Offset(0f,size.height-rim),size=Size(size.width,rim))
        drawLine(Color(0xFF29261F).copy(alpha=.45f),Offset(0f,size.height),Offset(size.width,size.height),.5f*u)
    }
}
