package cn.musicpilgrimage.app.ui.room

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

@Composable
internal fun RowScope.ArchiveIconAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: @Composable (Modifier) -> Unit,
) {
    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
        IconButton(
            onClick = onClick,
            modifier = modifier.size(48.dp).semantics { contentDescription = label },
            enabled = enabled,
            colors = IconButtonDefaults.iconButtonColors(contentColor = RoomColors.Ink),
        ) {
            icon(Modifier.size(24.dp))
        }
    }
}

@Composable
internal fun RowScope.ArchiveFavoriteAction(
    favorite: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    ArchiveIconAction(
        label = if (favorite) "取消收藏" else "收藏",
        onClick = onClick,
        modifier = modifier.semantics { stateDescription = if (favorite) "已收藏" else "未收藏" },
        enabled = enabled,
    ) {
        ArchiveFavoriteIcon(favorite, null, it)
    }
}

@Composable
internal fun RowScope.ArchiveSearchAction(onClick: () -> Unit, modifier: Modifier = Modifier) {
    ArchiveIconAction("搜索", onClick, modifier) {
        Icon(Icons.Outlined.Search, null, it)
    }
}
