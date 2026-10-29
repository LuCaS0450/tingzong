package cn.musicpilgrimage.app.ui.room

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable internal fun RoomSpaceMenu(open:Boolean,origin:Offset,current:String,motion:Boolean,onDismiss:()->Unit,onChoose:(String)->Unit) {
    BackHandler(open,onDismiss)
    val duration=if(motion)160 else 0
    AnimatedVisibility(open,enter=fadeIn(tween(duration))+slideInVertically(tween(duration)){it/28},
        exit=fadeOut(tween(duration))+slideOutVertically(tween(duration)){it/32}) {
        Column(Modifier.fillMaxSize().background(RoomColors.Wall).padding(horizontal=26.dp).testTag("room-space-menu")) {
            Row(Modifier.fillMaxWidth().height(66.dp),verticalAlignment=Alignment.CenterVertically) {
                SwissEyebrow("DIRECTORY / 听踪")
                Spacer(Modifier.weight(1f))
                IconButton(onDismiss,Modifier.testTag("room-close-space")){Icon(Icons.Outlined.Close,"关闭空间导航",tint=RoomColors.Ink)}
            }
            Text("更多功能\n与记录。",fontSize=32.sp,lineHeight=41.sp,fontWeight=FontWeight.SemiBold,color=RoomColors.Ink,modifier=Modifier.padding(top=16.dp,bottom=28.dp))
            val entries=listOf(Triple("room","音乐与唱片",Icons.Outlined.Album),Triple("explore","世界与旅行",Icons.Outlined.Map),
                Triple("performance","演奏",Icons.Outlined.Piano),Triple("me","我的空间",Icons.Outlined.PersonOutline),
                Triple("library","资料册与记录",Icons.Outlined.Inventory2),Triple("settings","设置",Icons.Outlined.Settings))
            entries.forEachIndexed { index,(route,label,icon)->
                HorizontalDivider(color=RoomColors.Line)
                Row(Modifier.fillMaxWidth().heightIn(min=68.dp).semantics{selected=current==route}
                    .testTag("room-module-$route").clickable(enabled=open,role=Role.Button){onChoose(route)},verticalAlignment=Alignment.CenterVertically) {
                    Text("%02d".format(index+1),fontSize=11.sp,color=RoomColors.Red,modifier=Modifier.width(32.dp))
                    Icon(icon,null,Modifier.size(22.dp),tint=RoomColors.Ink)
                    Text(label,Modifier.weight(1f).padding(start=14.dp),fontSize=16.sp,color=RoomColors.Ink)
                    Icon(Icons.Outlined.ChevronRight,null,Modifier.size(20.dp),tint=RoomColors.Muted)
                }
            }
            HorizontalDivider(color=RoomColors.Line)
        }
    }
}
