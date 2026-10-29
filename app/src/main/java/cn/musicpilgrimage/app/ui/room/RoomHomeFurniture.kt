package cn.musicpilgrimage.app.ui.room

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.caverock.androidsvg.SVG

private object HomeFurnitureImages {
    private var layers:HomeFurnitureLayers?=null
    @Synchronized fun load(context:Context):HomeFurnitureLayers = layers ?: HomeFurnitureLayers(context.applicationContext).also {layers=it}
}

/** Photo stand, geographic print, diffuse sphere lighting and piano stay separate. */
private class HomeFurnitureLayers(private val context:Context) {
    private fun bitmap(name:String):Bitmap = requireNotNull(context.assets.open("design/music-space/$name").use {BitmapFactory.decodeStream(it)})
    val globe=bitmap("home-globe-resin.png")
    val piano=bitmap("home-piano-charcoal.png")
    val geography=context.assets.open("design/music-space/home-globe-map.svg").use {SVG.getFromInputStream(it).renderToPicture(1600,1600)}
    val imagePaint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clearPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply {xfermode=PorterDuffXfermode(PorterDuff.Mode.CLEAR)}
    // Same stand-only cutout as the reviewed SVG mask, including its lower fringe.
    private val maquetteCutout=Path().apply {
        moveTo(1048f,545f)
        cubicTo(1048f,300.89f,846.53f,103f,598f,103f)
        cubicTo(349.47f,103f,148f,300.89f,148f,545f)
        cubicTo(148f,797.95f,349.47f,1003f,598f,1003f)
        cubicTo(846.53f,1003f,1048f,797.95f,1048f,545f)
        close()
    }
    private val sphere=RectF(1227f*.133f,1282f*.0855f,1227f*(.133f+.709f),1282f*.0855f+1227f*.709f)
    private val sphereClip=Path().apply {addOval(sphere,Path.Direction.CW)}
    private val oceanPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=AndroidColor.rgb(176,203,215)}
    private val diffusePaint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader=RadialGradient(sphere.left+sphere.width()*.30f,sphere.top+sphere.height()*.24f,sphere.width()*.95f,
            intArrayOf(AndroidColor.TRANSPARENT,AndroidColor.argb(20,37,71,79),AndroidColor.argb(87,20,47,61)),
            floatArrayOf(.28f,.58f,1f),Shader.TileMode.CLAMP)
    }
    private val sidePaint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader=LinearGradient(sphere.left,sphere.top,sphere.right,sphere.bottom,
            intArrayOf(AndroidColor.argb(8,255,255,255),AndroidColor.TRANSPARENT,AndroidColor.argb(26,19,44,52)),
            floatArrayOf(0f,.4f,1f),Shader.TileMode.CLAMP)
    }

    fun drawGlobe(canvas:AndroidCanvas) {
        val stand=canvas.saveLayer(0f,0f,1227f,1282f,null)
        canvas.drawBitmap(globe,0f,0f,imagePaint)
        canvas.drawPath(maquetteCutout,clearPaint)
        canvas.restoreToCount(stand)
        val surface=canvas.save()
        canvas.clipPath(sphereClip)
        canvas.drawOval(sphere,oceanPaint)
        val print=canvas.save()
        canvas.rotate(16f,sphere.centerX(),sphere.centerY())
        canvas.drawPicture(geography,sphere)
        canvas.restoreToCount(print)
        canvas.drawOval(sphere,diffusePaint)
        canvas.drawOval(sphere,sidePaint)
        canvas.restoreToCount(surface)
    }
}

/** Two edge crops from the approved 360px wall; the globe keeps part of its base. */
@Composable internal fun RoomHomeFurniture(modifier:Modifier,onMap:()->Unit,onPerformance:()->Unit) {
    val context=LocalContext.current
    val layers=remember(context){HomeFurnitureImages.load(context)}
    BoxWithConstraints(modifier) {
        val unit=maxWidth/360f
        Canvas(Modifier.fillMaxSize().clipToBounds()) {
            drawIntoCanvas { composeCanvas ->
                val canvas=composeCanvas.nativeCanvas
                val scene=canvas.save()
                val scale=size.width/360f
                canvas.scale(scale,scale)
                val globe=canvas.save()
                canvas.clipRect(0f,0f,166f,224f)
                canvas.translate(-70f,0f)
                canvas.scale(235f/1227f,235f/1227f)
                layers.drawGlobe(canvas)
                canvas.restoreToCount(globe)
                val piano=canvas.save()
                canvas.clipRect(204f,2f,360f,224f)
                // Align the solid piano edge (excluding transparent margins) with 演.
                // A 312px-wide body naturally exposes its left 156px at the screen edge.
                canvas.drawBitmap(layers.piano,Rect(122,0,1416,1024),RectF(204f,2f,516f,2f+312f*1024f/1294f),layers.imagePaint)
                canvas.restoreToCount(piano)
                canvas.restoreToCount(scene)
            }
        }
        Box(Modifier.width(unit*166f).height(unit*224f).testTag("room-open-map")
            .semantics {contentDescription="地图与旅行，地球仪"}
            .clickable(role=Role.Button,onClickLabel="打开地图与旅行",onClick=onMap))
        Box(Modifier.offset(x=unit*204f,y=unit*2f).width(unit*156f).height(unit*222f).testTag("room-open-performance")
            .semantics {contentDescription="演奏，钢琴"}
            .clickable(role=Role.Button,onClickLabel="打开演奏",onClick=onPerformance))
    }
}
