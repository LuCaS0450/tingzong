package cn.musicpilgrimage.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Older record types retain their native screens and account-backed data. */
@Composable internal fun ArchiveOtherRecords(onRoute:(String)->Unit) {
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(24.dp,10.dp,24.dp,28.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item {ActionRow(Icons.Rounded.PhotoCamera,"场景复刻","我的复刻照片"){onRoute("mine/recreation")}}
        item {ActionRow(Icons.Rounded.ChatBubbleOutline,"地点留言","我的地点记录"){onRoute("mine/message")}}
    }
}
