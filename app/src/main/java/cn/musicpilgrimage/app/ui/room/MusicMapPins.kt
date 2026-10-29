package cn.musicpilgrimage.app.ui.room

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import cn.musicpilgrimage.app.data.Place
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import kotlin.math.roundToInt

/** Compose hit targets stay attached to real geographic coordinates during native camera/size updates. */
@Composable internal fun MusicMapPins(view:MapView,map:MapLibreMap,places:List<Place>,selected:String?,onPlace:(Place)->Unit) {
    val density=LocalDensity.current.density
    var points by remember(map){mutableStateOf<Map<String,Offset>>(emptyMap())}
    DisposableEffect(view,map,places) {
        var disposed=false
        fun update() {
            if(disposed||view.width==0||view.height==0)return
            val positions=places.associate{p->map.projection.toScreenLocation(LatLng(p.latitude,p.longitude)).let{p.id to Offset(it.x,it.y)}}
            if(positions!=points)points=positions
        }
        val move=MapLibreMap.OnCameraMoveListener{update()}
        val frame=MapView.OnDidFinishRenderingFrameListener{_,_,_->update()}
        val layout=android.view.View.OnLayoutChangeListener{_,_,_,_,_,_,_,_,_->update()}
        map.addOnCameraMoveListener(move);view.addOnDidFinishRenderingFrameListener(frame);view.addOnLayoutChangeListener(layout)
        view.post{update()}
        onDispose{disposed=true;map.removeOnCameraMoveListener(move);view.removeOnDidFinishRenderingFrameListener(frame);view.removeOnLayoutChangeListener(layout)}
    }
    // Draw the active pin last so it remains selectable in a dense group.
    places.sortedBy{it.id==selected}.forEach{place->
        val point=points[place.id] ?: return@forEach
        if(point.x !in -40f..view.width+40f||point.y !in -40f..view.height+40f)return@forEach
        // Keep the pin tip on its coordinate; the larger hit area stays invisible.
        Box(Modifier.absoluteOffset{IntOffset((point.x-24*density).roundToInt(),(point.y-41*density).roundToInt())}
            .size(48.dp).testTag("room-map-pin-${place.id}").semantics{contentDescription=place.name;this.selected=place.id==selected}
            .clickable(role=Role.Button,onClickLabel="查看${place.name}"){onPlace(place)},contentAlignment=Alignment.Center) {
            Canvas(Modifier.size(44.dp)) {
                val unit=size.width/44
                val path=Path().apply {
                    moveTo(22*unit,39*unit)
                    cubicTo(19*unit,34*unit,9*unit,24*unit,9*unit,17*unit)
                    cubicTo(9*unit,0f,35*unit,0f,35*unit,17*unit)
                    cubicTo(35*unit,24*unit,25*unit,34*unit,22*unit,39*unit);close()
                }
                drawPath(path,if(place.id==selected)Color(0xFFC85438)else RoomColors.Navy)
                drawCircle(RoomColors.Paper,4*unit,Offset(22*unit,17*unit))
            }
        }
    }
}
