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

@Composable internal fun RoomTripStopCard(
    stop:MusicTripStop,modifier:Modifier,enabled:Boolean,canUp:Boolean,canDown:Boolean,
    onPlace:()->Unit,onUp:()->Unit,onDown:()->Unit,onMoveDay:()->Unit,onUnscheduled:()->Unit,onRemove:()->Unit,
    handleModifier:Modifier
) {
    RoomPaper(modifier.fillMaxWidth(),woodEdge=true) {
        Column(Modifier.padding(13.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f).then(if(enabled&&stop.placeId!=null)Modifier.clickable(role=Role.Button,onClick=onPlace)else Modifier)) {
                    Text(stop.name,fontSize=17.sp,color=RoomColors.Ink)
                    Text("${stop.city} · ${stop.country}",Modifier.padding(top=5.dp),fontSize=12.sp,color=RoomColors.Muted)
                }
                if(stop.placeId!=null)Icon(Icons.Outlined.ChevronRight,null,tint=RoomColors.Muted)
            }
            if(stop.note.isNotBlank())Text(stop.note,fontSize=13.sp,color=RoomColors.Muted)
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onMoveDay,handleModifier.size(48.dp).testTag("trip-drag-handle-${stop.id}"),enabled=enabled) {
                    Icon(Icons.Outlined.DragIndicator,"拖动地点",tint=RoomColors.Navy)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onUp,enabled=enabled&&canUp){Icon(Icons.Outlined.ArrowUpward,"上移地点",tint=if(enabled&&canUp)RoomColors.Navy else RoomColors.Muted.copy(alpha=.4f))}
                IconButton(onDown,enabled=enabled&&canDown){Icon(Icons.Outlined.ArrowDownward,"下移地点",tint=if(enabled&&canDown)RoomColors.Navy else RoomColors.Muted.copy(alpha=.4f))}
                Box {
                    var menu by remember{mutableStateOf(false)}
                    IconButton({menu=true},enabled=enabled){Icon(Icons.Outlined.MoreHoriz,"地点安排操作",tint=RoomColors.Navy)}
                    DropdownMenu(menu,{menu=false}) {
                        DropdownMenuItem({Text("移到另一天")},{menu=false;onMoveDay()})
                        if(stop.day!=null)DropdownMenuItem({Text("移到待安排")},{menu=false;onUnscheduled()})
                        DropdownMenuItem({Text("从行程移出")},{menu=false;onRemove()})
                    }
                }
            }
        }
    }
}
