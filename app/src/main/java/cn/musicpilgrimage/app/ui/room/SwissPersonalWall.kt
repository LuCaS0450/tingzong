package cn.musicpilgrimage.app.ui.room

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.musicpilgrimage.app.data.ReleaseArtwork
import coil.compose.AsyncImage
import com.caverock.androidsvg.SVG
import org.json.JSONObject

/** The approved material room is backed by the signed-in user's existing library. */
@Composable internal fun SwissPersonalWall(profile:JSONObject?,signedIn:Boolean,avatar:String?,artwork:ReleaseArtwork?,
    tripCount:Int,bestScore:Int?,onLogin:()->Unit,onRoute:(String)->Unit,onSpace:()->Unit) {
    LazyColumn(Modifier.fillMaxSize().testTag("swiss-personal-wall"),contentPadding=PaddingValues(26.dp,0.dp,26.dp,24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item("identity") {
            Row(Modifier.fillMaxWidth().clickable(role=Role.Button){if(signedIn)onRoute("account-profile")else onLogin()}.padding(bottom=14.dp),
                verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                RoomAccountAvatar(avatar,Modifier.size(49.dp))
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    Text(if(signedIn)profile?.optString("nickname")?.takeIf(String::isNotBlank)?:"音乐旅人" else "欢迎来到听踪",fontSize=19.sp,fontWeight=FontWeight.Medium,color=RoomColors.Ink,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text(if(signedIn)"收藏音乐，也收藏旅途。" else "登录，带上你的收藏与旅途。",fontSize=11.sp,color=RoomColors.Muted)
                }
                Icon(Icons.Outlined.ChevronRight,if(signedIn)"查看个人资料" else "登录",tint=RoomColors.Ink)
            }
            HorizontalDivider(color=RoomColors.Line)
        }
        item("collection-traces") {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(28.dp)) {
                PersonalObject("我的收藏","COLLECT",0,artwork,"",Modifier.weight(1f)){onRoute("music-favorites/releases")}
                PersonalObject("我的足迹","TRACES",1,null,"",Modifier.weight(1f)){onRoute("archive/footprints")}
            }
        }
        item("journey-records") {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(28.dp)) {
                PersonalObject("我的行程","JOURNEY",2,null,tripCount.toString().padStart(2,'0'),Modifier.weight(1f)){if(signedIn)onRoute("trips")else onLogin()}
                PersonalObject("演奏记录","RECORDS",3,null,bestScore?.let{"%,d".format(it)}?:"—",Modifier.weight(1f)){onRoute("account-scores")}
            }
        }
        item("links") {
            Column(Modifier.padding(top=10.dp)) {
                PersonalLink("个人资料"){if(signedIn)onRoute("account-profile")else onLogin()}
                PersonalLink("设置"){onRoute("settings")}
                PersonalLink("关于听踪"){onRoute("account-about")}
                PersonalLink("更多功能与记录",last=true,onClick=onSpace)
            }
        }
    }
}

@Composable private fun PersonalLink(label:String,last:Boolean=false,onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=51.dp).clickable(role=Role.Button,onClick=onClick),verticalAlignment=Alignment.CenterVertically) {
        Text(label,Modifier.weight(1f),fontSize=14.sp,color=RoomColors.Ink)
        Icon(Icons.Outlined.ChevronRight,null,Modifier.size(18.dp),tint=RoomColors.Muted)
    }
    if(!last)HorizontalDivider(color=RoomColors.Line,thickness=.5.dp)
}

@Composable private fun PersonalObject(label:String,english:String,kind:Int,artwork:ReleaseArtwork?,value:String,modifier:Modifier,onClick:()->Unit) {
    Column(modifier.height(176.dp).clickable(role=Role.Button,onClick=onClick).testTag("personal-object-$kind")) {
        Box(Modifier.fillMaxWidth().height(143.dp)) {
            when(kind) {
                0 -> {
                    Box(Modifier.align(Alignment.BottomCenter).offset(x=(-5).dp,y=(-19).dp).size(86.dp).graphicsLayer{rotationZ=-16f}.background(RoomColors.Ink))
                    RoomCover(artwork,"收藏的发行",Modifier.align(Alignment.BottomCenter).offset(y=(-17).dp).size(86.dp).graphicsLayer{rotationZ=-6f},sleeve=true)
                }
                1 -> FoldedWorldPrint(Modifier.align(Alignment.BottomCenter).offset(y=(-17).dp).size(107.dp,90.dp).graphicsLayer{rotationZ=4f})
                2,3 -> {
                    val red=kind==2
                    Box(Modifier.align(Alignment.BottomCenter).offset(y=(-17).dp).size(108.dp,82.dp).graphicsLayer{rotationZ=if(red)-3f else 3f;shadowElevation=3.dp.toPx()}
                        .background(Brush.linearGradient(if(red)listOf(Color(0xFFCF5141),RoomColors.Red)else listOf(Color(0xFFE9E7DC),Color(0xFFBFC7B6))))) {
                        Column(Modifier.padding(11.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                            Text(value,fontSize=if(red)36.sp else 25.sp,fontWeight=FontWeight.Normal,color=if(red)RoomColors.Paper else RoomColors.Ink,maxLines=1)
                            Text(if(red)"MY / JOURNEY"else "PERFORMANCE\nBEST",fontSize=8.sp,letterSpacing=.5.sp,lineHeight=11.sp,color=if(red)RoomColors.Paper else RoomColors.Ink)
                        }
                    }
                }
            }
            AsyncImage(roomAsset("wood"),null,Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(8.dp).graphicsLayer{shadowElevation=3.dp.toPx()},contentScale=ContentScale.Crop)
        }
        Row(Modifier.fillMaxWidth().padding(top=10.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            Text(label,fontSize=14.sp,color=RoomColors.Ink)
            Text(english,fontSize=9.sp,letterSpacing=.4.sp,color=RoomColors.Red)
        }
    }
}

@Composable private fun FoldedWorldPrint(modifier:Modifier) {
    val context=LocalContext.current
    val geography=remember(context){context.assets.open("design/music-space/home-globe-map.svg").use{SVG.getFromInputStream(it).renderToPicture()}}
    Canvas(modifier) {
        val w=size.width;val h=size.height
        val paper=listOf(Color(0xFFE4E5D1),Color(0xFFCDD2BD),Color(0xFFE0E2CE))
        repeat(3){index->
            val left=w*index/3f
            val path=Path().apply{moveTo(left,if(index%2==0)h*.09f else 0f);lineTo(left+w/3,if(index%2==0)0f else h*.09f);lineTo(left+w/3,if(index%2==0)h*.9f else h);lineTo(left,if(index%2==0)h else h*.9f);close()}
            drawPath(path,paper[index])
            drawIntoCanvas {canvas->val native=canvas.nativeCanvas;native.save();native.clipRect(left,0f,left+w/3,h);native.saveLayerAlpha(0f,0f,w,h,55);native.drawPicture(geography,RectF(0f,0f,w,h));native.restore();native.restore()}
            drawLine(Color(0xFFB7BEA8),Offset(left,0f),Offset(left,h),.5.dp.toPx())
        }
    }
}
