package cn.musicpilgrimage.app.ui.room

import android.graphics.BitmapFactory
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.*
import cn.musicpilgrimage.app.ui.LocalPhysicalMotion
import kotlin.math.roundToInt

/** Nine-sliced moulding from the approved artwork: the map itself always remains native and live. */
@Composable internal fun RoomMapFrame(full:Boolean,modifier:Modifier=Modifier,content:@Composable BoxScope.()->Unit) {
    val context=LocalContext.current
    val frame=remember(context){context.assets.open("design/music-space/map-frame-reference.png").use{BitmapFactory.decodeStream(it)}.asImageBitmap()}
    val duration=if(LocalPhysicalMotion.current)260 else 0
    val inset by animateDpAsState(if(full)0.dp else 12.dp,tween(duration),label="map-moulding-width")
    val radius by animateDpAsState(if(full)0.dp else 4.dp,tween(duration),label="map-corners")
    val elevation by animateDpAsState(if(full)0.dp else 7.dp,tween(duration),label="map-frame-depth")
    Box(modifier.shadow(elevation,RoundedCornerShape(radius),clip=false).clip(RoundedCornerShape(radius))) {
        Box(Modifier.fillMaxSize().padding(inset).clip(RoundedCornerShape(radius)),content=content)
        Canvas(Modifier.matchParentSize()) {
            if(inset.value<=.05f)return@Canvas
            val b=inset.toPx().roundToInt().coerceAtLeast(1)
            val w=size.width.roundToInt();val h=size.height.roundToInt()
            // These bounds contain only the wood rails, corner joints and inner bevel.
            val sx=intArrayOf(24,48,789,813);val sy=intArrayOf(351,375,1256,1280)
            val dx=intArrayOf(0,b,w-b,w);val dy=intArrayOf(0,b,h-b,h)
            for(row in 0..2)for(col in 0..2)if(row!=1||col!=1) {
                drawImage(frame,srcOffset=IntOffset(sx[col],sy[row]),srcSize=IntSize(sx[col+1]-sx[col],sy[row+1]-sy[row]),
                    dstOffset=IntOffset(dx[col],dy[row]),dstSize=IntSize((dx[col+1]-dx[col]).coerceAtLeast(1),(dy[row+1]-dy[row]).coerceAtLeast(1)),alpha=(inset.value/12).coerceIn(0f,1f))
            }
        }
    }
}
