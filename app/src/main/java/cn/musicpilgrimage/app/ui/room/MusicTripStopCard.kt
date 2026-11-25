package cn.musicpilgrimage.app.ui.room

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.musicpilgrimage.app.data.MusicTripStop
import cn.musicpilgrimage.app.data.geographicParts

@Composable internal fun RoomTripStopCard(
    stop:MusicTripStop,modifier:Modifier,enabled:Boolean,canUp:Boolean,canDown:Boolean,
    onPlace:()->Unit,onUp:()->Unit,onDown:()->Unit,onMoveDay:()->Unit,onRemove:()->Unit,
    handleModifier:Modifier,placeEnabled:Boolean=enabled
) {
    Box(
        modifier
            .fillMaxWidth()
            .shadow(
                elevation = 3.5.dp,
                shape = TripStopCardShape,
                ambientColor = RoomColors.Ink.copy(alpha = 0.08f),
                spotColor = RoomColors.Ink.copy(alpha = 0.13f),
                clip = false,
            )
            .clip(TripStopCardShape)
            .background(tripBoardFace)
            .border(0.8.dp, tripCardBorder, TripStopCardShape),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(start = 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier.weight(1f)
                    .then(if (placeEnabled && stop.placeId != null) Modifier.clickable(role = Role.Button, onClick = onPlace) else Modifier)
                    .padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    stop.name,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = RoomColors.Ink,
                )
                val location = geographicParts(stop.city, stop.country).joinToString(" · ")
                val subtitle = listOf(location, stop.note).filter(String::isNotBlank).joinToString(" · ")
                if (subtitle.isNotBlank()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (stop.placeId != null) {
                            Icon(
                                Icons.Outlined.Place,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = RoomColors.Red.copy(alpha = 0.74f),
                            )
                        }
                        Text(subtitle, fontSize = 12.sp, lineHeight = 18.sp, color = RoomColors.Muted)
                    }
                }
            }
            IconButton(
                onMoveDay,
                handleModifier.size(48.dp).testTag("trip-drag-handle-${stop.id}"),
                enabled = enabled,
            ) {
                Icon(Icons.Outlined.DragIndicator, "拖动地点", tint = RoomColors.Muted.copy(alpha = 0.78f))
            }
            Box {
                var menu by remember { mutableStateOf(false) }
                IconButton({ menu = true }, enabled = enabled) {
                    Icon(Icons.Outlined.MoreHoriz, "地点安排操作", tint = RoomColors.Muted)
                }
                DropdownMenu(
                    expanded = menu,
                    onDismissRequest = { menu = false },
                    shape = TripControlShape,
                    containerColor = lerp(RoomColors.Paper, Color.White, 0.78f),
                ) {
                    DropdownMenuItem({ Text("上移地点") }, { menu = false; onUp() }, enabled = enabled && canUp)
                    DropdownMenuItem({ Text("下移地点") }, { menu = false; onDown() }, enabled = enabled && canDown)
                    DropdownMenuItem({ Text("移到另一天") }, { menu = false; onMoveDay() }, enabled = enabled)
                    DropdownMenuItem(
                        { Text("从行程移出", color = RoomColors.Red) },
                        { menu = false; onRemove() },
                        enabled = enabled,
                    )
                }
            }
        }
    }
}
