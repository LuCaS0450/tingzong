package cn.musicpilgrimage.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import cn.musicpilgrimage.app.design.*

@Composable internal fun PixelNavigation(tabs:List<Triple<String,String,ImageVector>>,route:String?,onSelect:(String)->Unit) {
    Column(Modifier.fillMaxWidth().background(AppArt.Base)) {
        HorizontalDivider(thickness=2.dp,color=AppArt.Ink)
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal=16.dp,vertical=8.dp)) {
            tabs.forEach{(path,label,icon)->
                val active=route==path
                Column(Modifier.weight(1f).heightIn(min=64.dp)
                    .semantics{selected=active;role=Role.Tab}
                    .clickable(role=Role.Tab,onClick={onSelect(path)}),horizontalAlignment=Alignment.CenterHorizontally,
                    verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Surface(color=if(active)AppArt.Yellow else AppArt.Base,shape=RoundedCornerShape(3.dp),
                        border=if(active)BorderStroke(2.dp,AppArt.Ink) else null) {
                        Box(Modifier.size(52.dp,34.dp),contentAlignment=Alignment.Center){Icon(icon,null,tint=AppArt.Ink)}
                    }
                    Text(label,color=if(active)AppArt.Ink else AppArt.Muted,style=MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
