package cn.musicpilgrimage.app.ui.room

import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import cn.musicpilgrimage.app.ui.LocalPhysicalMotion
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable internal fun SwissPersonalWall(
    profile:JSONObject?,signedIn:Boolean,avatar:String?,onLogin:()->Unit,onTrips:()->Unit,onRoute:(String)->Unit
) {
    LazyColumn(Modifier.fillMaxSize().testTag("swiss-personal-wall"),contentPadding=PaddingValues(bottom=16.dp)) {
        item("identity") {
            Row(Modifier.fillMaxWidth().padding(horizontal=26.dp).clickable(role=Role.Button,
                onClickLabel=if(signedIn)"编辑头像和昵称" else "登录") {
                if(signedIn)onRoute("edit-profile") else onLogin()
            }.padding(bottom=12.dp).testTag("personal-identity"),
                verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                RoomAccountAvatar(avatar,Modifier.size(49.dp))
                val name=if(signedIn)profile?.optString("nickname")?.takeIf(String::isNotBlank)?:"音乐旅人" else "欢迎来到听踪"
                Text(name,Modifier.weight(1f),fontSize=19.sp,fontWeight=FontWeight.Medium,color=RoomColors.Ink,
                    maxLines=1,overflow=TextOverflow.Ellipsis)
                Icon(Icons.Outlined.ChevronRight,null,tint=RoomColors.Ink)
            }
            HorizontalDivider(Modifier.padding(horizontal=26.dp),color=RoomColors.Line)
        }
        item("cabinet") {PersonalCabinet(onRoute)}
        item("actions") {
            Column(Modifier.padding(horizontal=26.dp)) {
                AccountLink("我的行程",Icons.Outlined.Route,onClick=onTrips)
                if(signedIn)AccountLink("投稿",Icons.Outlined.EditLocationAlt){onRoute("submissions")}
                AccountLink("设置",Icons.Outlined.Settings,last=true){onRoute("settings")}
            }
        }
    }
}

private data class PersonalDrawer(val id:String,val label:String,val route:String,val top:Int,val height:Int)

private val personalDrawers=listOf(
    PersonalDrawer("songs","歌曲","archive/songs",310,252),
    PersonalDrawer("releases","专辑","archive/releases",564,212),
    PersonalDrawer("places","地点","archive/places",778,210),
    PersonalDrawer("footprints","足迹","archive/footprints",990,211)
)

@Composable private fun PersonalCabinet(onRoute:(String)->Unit) {
    val context=LocalContext.current
    val bitmap=remember(context) {
        runCatching {
            context.assets.open("design/music-space/cabinet-empty.webp").use {BitmapFactory.decodeStream(it)?.asImageBitmap()}
        }.getOrNull()
    }
    if(bitmap==null) {
        Column(Modifier.padding(horizontal=26.dp,vertical=12.dp)) {
            personalDrawers.forEach { drawer->
                Box(Modifier.testTag("room-drawer-${drawer.id}")) {
                    AccountLink(drawer.label,Icons.Outlined.Inventory2){onRoute(drawer.route)}
                }
            }
        }
        return
    }

    val motion=LocalPhysicalMotion.current
    val scope=rememberCoroutineScope()
    var selected by rememberSaveable {mutableStateOf<String?>(null)}
    val pull=remember {Animatable(if(selected==null)0f else 1f)}
    LaunchedEffect(Unit) {
        if(selected!=null) {
            pull.animateTo(0f,tween(if(motion)120 else 0))
            selected=null
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal=12.dp)) {
        val scale=maxWidth/1155f
        Box(Modifier.fillMaxWidth().height(scale*1362f)) {
            Image(bitmap,null,Modifier.matchParentSize(),contentScale=ContentScale.FillBounds)
            personalDrawers.forEach { drawer->
                val amount=if(selected==drawer.id)pull.value else 0f
                Box(Modifier.offset(x=scale*68f,y=scale*drawer.top).width(scale*1020f).height(scale*drawer.height)
                    .graphicsLayer {translationY=8.dp.toPx()*amount;scaleX=1f+.025f*amount;scaleY=1f+.025f*amount}
                    .testTag("room-drawer-${drawer.id}").clickable(role=Role.Button,onClickLabel="打开${drawer.label}抽屉") {
                        if(!pull.isRunning)scope.launch {
                            selected=drawer.id
                            pull.animateTo(1f,tween(if(motion)160 else 0))
                            onRoute(drawer.route)
                        }
                    }) {
                    if(amount>0f)Canvas(Modifier.matchParentSize()) {
                        drawImage(bitmap,srcOffset=IntOffset(68,drawer.top),srcSize=IntSize(1020,drawer.height),
                            dstSize=IntSize(size.width.toInt(),size.height.toInt()))
                    }
                    Text(drawer.label,Modifier.align(Alignment.CenterStart).padding(start=30.dp),color=RoomColors.Ink,
                        fontSize=20.sp,fontWeight=FontWeight.SemiBold)
                }
            }
        }
    }
}
