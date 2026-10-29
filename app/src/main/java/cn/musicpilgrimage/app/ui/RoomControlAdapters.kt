package cn.musicpilgrimage.app.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Screen-local adapters preserve existing callbacks and accessibility descriptions. */
@Composable internal fun Icon(imageVector:ImageVector,contentDescription:String?,modifier:Modifier=Modifier,tint:Color=LocalContentColor.current) {
    androidx.compose.material3.Icon(imageVector,contentDescription,modifier,tint)
}
@Composable internal fun IconButton(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,content:@Composable ()->Unit) {
    androidx.compose.material3.IconButton(onClick,modifier,enabled=enabled,content=content)
}
@Composable internal fun Switch(checked:Boolean,onCheckedChange:(Boolean)->Unit,modifier:Modifier=Modifier,enabled:Boolean=true) {
    androidx.compose.material3.Switch(checked,onCheckedChange,modifier,enabled=enabled)
}
@Composable internal fun CircularProgressIndicator(modifier:Modifier=Modifier,color:Color=Acid,strokeWidth:Dp=2.dp) {
    androidx.compose.material3.CircularProgressIndicator(modifier,color=color,strokeWidth=strokeWidth)
}
