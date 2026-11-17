package cn.musicpilgrimage.app.ui.room

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.musicpilgrimage.app.data.MusicTripStop
import cn.musicpilgrimage.app.data.geographicParts

@Composable internal fun RoomTripStopCard(
    stop:MusicTripStop,modifier:Modifier,enabled:Boolean,canUp:Boolean,canDown:Boolean,
    onPlace:()->Unit,onUp:()->Unit,onDown:()->Unit,onMoveDay:()->Unit,onRemove:()->Unit,
    handleModifier:Modifier,placeEnabled:Boolean=enabled
) {
    RoomPaper(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min=72.dp).padding(start=13.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f).then(if(placeEnabled&&stop.placeId!=null)Modifier.clickable(role=Role.Button,onClick=onPlace)else Modifier)
                .padding(vertical=10.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                Text(stop.name,fontSize=16.sp,color=RoomColors.Ink)
                val location=geographicParts(stop.city,stop.country).joinToString(" · ")
                val subtitle=listOf(location,stop.note).filter(String::isNotBlank).joinToString(" · ")
                if(subtitle.isNotBlank())Text(subtitle,fontSize=12.sp,color=RoomColors.Muted)
            }
            IconButton(onMoveDay,handleModifier.size(48.dp).testTag("trip-drag-handle-${stop.id}"),enabled=enabled) {
                Icon(Icons.Outlined.DragIndicator,"拖动地点",tint=RoomColors.Navy)
            }
            Box {
                var menu by remember{mutableStateOf(false)}
                IconButton({menu=true},enabled=enabled){Icon(Icons.Outlined.MoreHoriz,"地点安排操作",tint=RoomColors.Navy)}
                DropdownMenu(menu,{menu=false}) {
                    DropdownMenuItem({Text("上移地点")},{menu=false;onUp()},enabled=enabled&&canUp)
                    DropdownMenuItem({Text("下移地点")},{menu=false;onDown()},enabled=enabled&&canDown)
                    DropdownMenuItem({Text("移到另一天")},{menu=false;onMoveDay()},enabled=enabled)
                    DropdownMenuItem({Text("从行程移出")},{menu=false;onRemove()},enabled=enabled)
                }
            }
        }
    }
}
