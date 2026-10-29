package cn.musicpilgrimage.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import cn.musicpilgrimage.app.FeatureViewModel
import cn.musicpilgrimage.app.data.*
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import cn.musicpilgrimage.app.design.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.semantics.Role
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import cn.musicpilgrimage.app.ui.room.LocalRoomSpaceAction
import cn.musicpilgrimage.app.ui.room.LocalRoomSpaceOrigin
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot

internal val Ink=Color(0xFF242B27)
internal val Muted=Color(0xFF686E67)
internal val Acid=Ink
internal val Panel=Color(0xFFF7F6F2)
internal val Line=Color(0xFFD8D8D0)
@Composable fun PilgrimageTheme(content:@Composable ()->Unit) {
 PhysicalMotionProvider {
 MaterialTheme(colorScheme=lightColorScheme(primary=Acid,onPrimary=Color.White,primaryContainer=Panel,onPrimaryContainer=Ink,
  secondary=Color(0xFFBD382B),secondaryContainer=Panel,onSecondaryContainer=Ink,tertiary=Ink,tertiaryContainer=Panel,onTertiaryContainer=Ink,
  background=Panel,surface=Panel,onSurface=Ink,onBackground=Ink,surfaceVariant=Panel,
  surfaceContainer=Panel,surfaceContainerHigh=Panel,surfaceContainerHighest=Panel,surfaceContainerLow=Panel,surfaceContainerLowest=Panel,
  onSurfaceVariant=Muted,outline=Line,outlineVariant=Line,error=Color(0xFF932119),surfaceTint=Color.Transparent),
  shapes=Shapes(RoundedCornerShape(0.dp),RoundedCornerShape(0.dp),RoundedCornerShape(0.dp),RoundedCornerShape(0.dp),RoundedCornerShape(0.dp)),
  typography=physicalTypography().let { t -> t.copy(
   headlineLarge=t.headlineLarge.copy(fontSize=27.sp,lineHeight=35.sp,fontWeight=FontWeight.SemiBold),
   titleLarge=t.titleLarge.copy(fontSize=21.sp,lineHeight=28.sp),
   bodyLarge=t.bodyLarge.copy(fontSize=16.sp,lineHeight=24.sp),bodyMedium=t.bodyMedium.copy(fontSize=16.sp,lineHeight=24.sp),
   bodySmall=t.bodySmall.copy(fontSize=13.sp,lineHeight=20.sp)) },content=content)
 }
}
@Composable internal fun PageTitle(title:String,subtitle:String?=null,action:(@Composable ()->Unit)?=null) {
 val space=LocalRoomSpaceAction.current
 val spaceOrigin=LocalRoomSpaceOrigin.current
 Column(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
  Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
   Text("听踪 / MUSIC ATLAS",Modifier.weight(1f),color=Acid,style=MaterialTheme.typography.labelMedium)
  }
  Row(verticalAlignment=Alignment.CenterVertically) {
   Column(Modifier.weight(1f)){Text(title,style=MaterialTheme.typography.headlineLarge);subtitle?.let{Text(it,color=Muted,style=MaterialTheme.typography.bodySmall)}}
   action?.invoke()
   if(space!=null)TextButton(space){Icon(Icons.Rounded.GridView,"打开空间导航",Modifier.onGloballyPositioned{spaceOrigin(it.boundsInRoot().center)});Text("空间")}
  }
 }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun BackBar(title:String,onBack:()->Unit) { TopAppBar(title={Text(title,style=MaterialTheme.typography.titleLarge)},navigationIcon={IconButton(onBack){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"返回")}},colors=TopAppBarDefaults.topAppBarColors(containerColor=AppArt.Base)) }
@Composable internal fun SectionTitle(title:String,actionText:String?=null,onAction:()->Unit={}) {
 Row(Modifier.fillMaxWidth().padding(top=24.dp,bottom=12.dp),verticalAlignment=Alignment.CenterVertically){Text(title,style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f));actionText?.let {TextButton(onAction){Text(it)}}}
}
@Composable internal fun StatusCard(title:String,body:String="",icon:ImageVector=Icons.Rounded.Explore,button:String?=null,onClick:()->Unit={}) {
 BauhausPanel(Modifier.fillMaxWidth()) {Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
  Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {Icon(icon,null,Modifier.size(24.dp),tint=Muted);Text(title,style=MaterialTheme.typography.titleLarge)}
  if(body.isNotBlank())Text(body,color=Muted)
  button?.let{Button(onClick){Text(it)}}
 }}
}
@Composable internal fun ErrorNotice(message:String?,retry:(()->Unit)?=null) { message?.let {
 BauhausPanel(modifier=Modifier.fillMaxWidth()) {
  Column(Modifier.padding(12.dp)) {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall);retry?.let {TextButton(it){Text("重试")}}}
 }
} }
@Composable internal fun ViewModelStatus(vm:FeatureViewModel) {
 val busy by vm.busy.collectAsState();val error by vm.error.collectAsState();val notice by vm.notice.collectAsState()
 if(busy)LinearProgressIndicator(Modifier.fillMaxWidth(),color=Acid)
 ErrorNotice(error)
 notice?.let {Text(it,color=Muted,modifier=Modifier.padding(vertical=8.dp),style=MaterialTheme.typography.bodySmall)}
}
@Composable internal fun LoginGate(onLogin:()->Unit) {StatusCard("登录账号","收藏地点与发布内容需要登录。",Icons.Rounded.Person,"登录",onLogin)}
@Composable internal fun Cover(place:Place,modifier:Modifier=Modifier,compact:Boolean=false) {
 val scene=place.primaryScene()
 ReferenceImage(if(compact)scene?.thumbnailUrl?:scene?.imageUrl else scene?.imageUrl,"${place.name}作品参考图",modifier,compact=compact)
}
@Composable internal fun ReleaseCover(place:Place,modifier:Modifier=Modifier,compact:Boolean=false) {
 val cover=place.displayCover()
 ReferenceImage(if(compact)cover?.thumbnailUrl?:cover?.imageUrl else cover?.imageUrl,"${place.name}发行封面",modifier,compact=compact,
  missingLabel=if(compact)"封面待补" else "正式发行封面尚待补齐")
}
@Composable internal fun ReferenceImage(url:String?,description:String,modifier:Modifier=Modifier,compact:Boolean=false,onReady:(Boolean)->Unit={},missingLabel:String?=null) {
 var retry by remember(url){mutableIntStateOf(0)}
 var state by remember(url,retry){mutableIntStateOf(0)}
 val readyCallback by rememberUpdatedState(onReady)
 LaunchedEffect(url,state){readyCallback(url!=null&&state==1)}
 val shape=if(compact)ThumbnailArtworkShape else ArtworkShape
 Box(modifier.mediaShadow(shape,compact).clip(shape).background(materialBrush("walnut")).padding(if(compact)3.dp else 6.dp),contentAlignment=Alignment.Center) {
 Box(Modifier.fillMaxSize().clip(BauhausMediaInsetShape).background(Panel),contentAlignment=Alignment.Center) {
  if(url!=null)key(url,retry){AsyncImage(url,description,Modifier.fillMaxSize(),contentScale=ContentScale.Fit,
   onLoading={state=0},onSuccess={state=1},onError={state=2})}
  if(url==null||state!=1)Column(Modifier.padding(if(compact)4.dp else 16.dp),horizontalAlignment=Alignment.CenterHorizontally) {
   Text(when{url==null->missingLabel?:if(compact)"待补图" else "原作参考图尚未通过审核";state==2->"图片加载失败";else->"图片加载中…"},color=Muted,style=MaterialTheme.typography.bodySmall)
   if(url!=null&&state==2)TextButton({retry++}){Text("重试")}
   else if(url!=null&&state==0)CircularProgressIndicator(Modifier.padding(top=8.dp).size(20.dp),strokeWidth=2.dp,color=Acid)
  }
 }}
}
@Composable internal fun PlaceRow(place:Place,matched:String="",onClick:()->Unit) {
 Row(Modifier.fillMaxWidth().clickable(onClick=onClick).padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
  ReleaseCover(place,Modifier.size(72.dp),compact=true)
  Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
   Text(place.name,fontWeight=FontWeight.Bold,maxLines=2,overflow=TextOverflow.Ellipsis)
   Text("${place.city} · ${place.scenes.firstOrNull()?.artistName.orEmpty()}",color=Muted,style=MaterialTheme.typography.bodySmall)
   Text(if(matched.isNotBlank())"匹配：$matched" else place.scenes.joinToString(" / "){formatWorkTitle(it.work)},color=Muted,style=MaterialTheme.typography.bodySmall,maxLines=2,overflow=TextOverflow.Ellipsis)
   Text(place.associationLabels(),color=Acid,style=MaterialTheme.typography.bodySmall)
  }
  Icon(Icons.Rounded.ChevronRight,null,tint=Muted)
 }
 HorizontalDivider(color=Line)
}
@Composable internal fun ActionRow(icon:ImageVector,title:String,subtitle:String?=null,onClick:()->Unit) {
 Row(Modifier.fillMaxWidth().heightIn(min=60.dp).clickable(onClick=onClick).padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
  Icon(icon,null,tint=Muted);Column(Modifier.weight(1f)){Text(title);subtitle?.let{Text(it,color=Muted,style=MaterialTheme.typography.bodySmall)}};Icon(Icons.Rounded.ChevronRight,null,tint=Muted)
 };HorizontalDivider(color=Line)
}
@Composable internal fun ToggleIcon(selected:Boolean,icon:ImageVector,description:String,enabled:Boolean=true,onClick:()->Unit) {
 IconButton(onClick,enabled=enabled){Icon(icon,description,tint=if(selected)Acid else Muted)}
}
@Composable internal fun Avatar(name:String,id:String,modifier:Modifier=Modifier) {
  BauhausPanel(modifier.size(52.dp)) {Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text(name.take(1).ifBlank{"听"},fontFamily=PrintFont,fontWeight=FontWeight.SemiBold,color=Ink)}}
}
@Composable internal fun ExternalLink(label:String,url:String) {
 val context=LocalContext.current
 TextButton({if(url.startsWith("https://")||url.startsWith("http://")||url.startsWith("geo:"))runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))}}){Text(label,style=MaterialTheme.typography.bodySmall)}
}
@Composable internal fun PrivatePhoto(id:String,services:AppServices,modifier:Modifier=Modifier) {
 val context=LocalContext.current
 val user by services.userId.collectAsState()
 val model=remember(id,user){ImageRequest.Builder(context).data(services.mediaUrl(id)).addHeader("Authorization","Bearer ${services.token().orEmpty()}")
  .addHeader("apikey",cn.musicpilgrimage.app.BuildConfig.SUPABASE_ANON_KEY).diskCachePolicy(CachePolicy.DISABLED).memoryCachePolicy(CachePolicy.DISABLED).build()}
 AsyncImage(model,"用户分享的照片",modifier.mediaShadow(ArtworkShape).clip(ArtworkShape),contentScale=ContentScale.Fit)
}

@Composable internal fun Button(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,content:@Composable RowScope.()->Unit) {
 androidx.compose.material3.Button(onClick,modifier,enabled=enabled,shape=RoundedCornerShape(5.dp),content=content)
}
@Composable internal fun OutlinedButton(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,content:@Composable RowScope.()->Unit) {
 androidx.compose.material3.OutlinedButton(onClick,modifier,enabled=enabled,shape=RoundedCornerShape(5.dp),content=content)
}
