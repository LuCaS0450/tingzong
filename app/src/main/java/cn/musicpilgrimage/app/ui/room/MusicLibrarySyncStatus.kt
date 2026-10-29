package cn.musicpilgrimage.app.ui.room

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.musicpilgrimage.app.data.*

@Composable internal fun RoomLibrarySyncStatus(state:PersonalLibrarySyncState,onRetry:()->Unit,modifier:Modifier=Modifier) {
    val local=!state.owner.startsWith("user:")||state.status==LibrarySyncStatus.LOCAL
    Row(modifier.fillMaxWidth().testTag("room-library-sync"),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f).semantics{liveRegion=LiveRegionMode.Polite},verticalArrangement=Arrangement.spacedBy(5.dp)) {
            Text(if(local)state.error?:"行程和音乐收藏保存在这台设备。"else when(state.status) {
                LibrarySyncStatus.WAITING->"已保存到本机，等待同步"
                LibrarySyncStatus.SYNCING->"正在同步，本机更改会保留"
                LibrarySyncStatus.SYNCED->"行程和音乐收藏已同步"
                LibrarySyncStatus.ERROR->state.error?:"暂时无法同步，本机更改已保留。"
                else->"已保存到本机"
            },fontSize=12.sp,lineHeight=20.sp,color=RoomColors.Muted)
            state.notice?.takeUnless{local}?.let{Text(it,fontSize=12.sp,lineHeight=20.sp,color=RoomColors.Navy)}
        }
        if(!local)SwissTextButton(onRetry,enabled=state.status!=LibrarySyncStatus.SYNCING,modifier=Modifier.heightIn(min=48.dp).testTag("room-library-sync-retry")) {
            Text(if(state.status==LibrarySyncStatus.ERROR)"重试"else "同步",fontSize=12.sp,color=RoomColors.Navy)
        }
    }
}
