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
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/** A softly uneven paper edge. Cache the normalized mask so resizing never blurs a new bitmap. */
internal fun Modifier.sleeveShadow(): Modifier = drawWithCache {
    val mask = RoomShadowCache.image("record-sleeve-shadow") {
        val bitmap = Bitmap.createBitmap(272, 272, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(35, 30, 39, 30)
            maskFilter = BlurMaskFilter(4f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawRect(16f, 16f, 256f, 256f, paint)
        val edge = android.graphics.Path().apply {
            moveTo(17f, 16f)
            lineTo(255f, 17f)
            lineTo(259f, 250f)
            quadTo(233f, 258f, 198f, 255f)
            quadTo(128f, 252f, 18f, 257f)
            close()
        }
        paint.color = android.graphics.Color.argb(49, 30, 39, 30)
        paint.maskFilter = BlurMaskFilter(5f, BlurMaskFilter.Blur.NORMAL)
        canvas.drawPath(edge, paint)
        paint.color = android.graphics.Color.argb(50, 30, 39, 30)
        paint.maskFilter = BlurMaskFilter(1.7f, BlurMaskFilter.Blur.NORMAL)
        canvas.drawRect(17f, 17f, 253f, 253f, paint)
        bitmap.asImageBitmap()
    }
    onDrawBehind {
        scale(size.width / 240f, size.height / 240f, pivot = Offset.Zero) {
            drawImage(mask, topLeft = Offset(-16f, -16f))
        }
    }
}

/** Share immutable blurs across the covers and adjacent pages of the record rack. */
internal object RoomShadowCache {
    private val images = object : android.util.LruCache<String, ImageBitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    @Synchronized fun image(key: String, create: () -> ImageBitmap): ImageBitmap =
        images.get(key) ?: create().also { images.put(key, it) }
}

/** One upper-left light source, with a broad cast shadow and a tighter contact shadow. */
internal fun Modifier.coverShadow(): Modifier = drawWithCache {
    val small = size.minDimension < 96.dp.toPx()
    val blur = (if (small) 3.1.dp else 5.0.dp).toPx()
    val dx = (if (small) 1.5.dp else 2.8.dp).toPx()
    val dy = (if (small) 2.1.dp else 3.8.dp).toPx()
    val pad = ceil(blur * 2 + dy + 2.dp.toPx()).toInt()
    val image = RoomShadowCache.image("cover:$size:$density") {
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
    bitmap.asImageBitmap()
    }
    onDrawBehind { drawImage(image, topLeft = Offset(-pad.toFloat(), -pad.toFloat())) }
}

/** Paper edge shared by the rack, the moving cover and the release archive. */
internal fun Modifier.coverSleeve(strength: () -> Float = { 1f }): Modifier = drawWithCache {
    val u=size.width/141f
    val edge=2.6f*u
    val rim=.65f*u
    val paperEdge=Brush.horizontalGradient(listOf(Color(0xFFF1EBDD),Color(0xFFD0C6B4),Color(0xFFA89D89)),startX=size.width,endX=size.width+edge)
    onDrawWithContent {
        val alpha=strength().coerceIn(0f,1f)
        drawRect(Color(0xFFDDD5C6).copy(alpha=alpha),topLeft=Offset(edge,edge*.55f),size=size)
        drawRect(paperEdge,
            topLeft=Offset(size.width,edge*.55f),size=Size(edge,size.height),alpha=alpha)
        drawLine(Color(0xFFF8F2E4).copy(alpha=alpha),Offset(size.width+edge*.42f,edge*.55f),Offset(size.width+edge*.42f,size.height+edge*.55f),.45f*u)
        drawLine(Color(0xFF756B59).copy(alpha=alpha),Offset(size.width+edge,edge*.55f),Offset(size.width+edge,size.height+edge*.55f),.45f*u)
        drawContent()
        drawRect(Color(0xFFF0EADC).copy(alpha=alpha),size=Size(size.width,rim))
        drawRect(Color(0xFFECE5D7).copy(alpha=alpha),size=Size(rim,size.height))
        drawLine(Color(0xFF756B59).copy(alpha=.65f*alpha),Offset(size.width-rim*.5f,rim),Offset(size.width-rim*.5f,size.height),.45f*u)
        drawRect(Color(0xFFDAD0BE).copy(alpha=alpha),topLeft=Offset(0f,size.height-rim),size=Size(size.width,rim))
        drawLine(Color(0xFF29261F).copy(alpha=.45f*alpha),Offset(0f,size.height),Offset(size.width,size.height),.5f*u)
    }
}
