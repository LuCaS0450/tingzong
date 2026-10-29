package cn.musicpilgrimage.app.ui.room

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import cn.musicpilgrimage.app.data.AppServices
import cn.musicpilgrimage.app.ui.LocalPhysicalMotion
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest

@Composable internal fun roomPrivatePhotoModel(id:String?,services:AppServices):Any? {
    val context=LocalContext.current
    val owner by services.userId.collectAsState()
    val revision by services.changed.collectAsState()
    return remember(id,owner,revision){id?.let{ImageRequest.Builder(context).data(services.mediaUrl(it))
        .addHeader("Authorization","Bearer ${services.token().orEmpty()}")
        .addHeader("apikey",cn.musicpilgrimage.app.BuildConfig.SUPABASE_ANON_KEY)
        .diskCachePolicy(CachePolicy.DISABLED).memoryCachePolicy(CachePolicy.DISABLED).size(coil.size.Size.ORIGINAL).build()}}
}

@Composable internal fun RoomCommunityAvatar(name:String,modifier:Modifier=Modifier) {
    Box(modifier.size(38.dp).clip(CircleShape).background(RoomColors.Navy.copy(alpha=.10f)),contentAlignment=Alignment.Center) {
        Text(name.take(1).ifBlank{"听"},color=RoomColors.Navy,fontSize=16.sp)
    }
}

/** Both the reading image and its viewer preserve the decoded source's full ratio. */
@Composable internal fun RoomCommunityPhoto(model:Any?,description:String,modifier:Modifier=Modifier,maxWidth:Dp=600.dp,tag:String="") {
    var ratio by remember(model){mutableFloatStateOf(1f)}
    var failed by remember(model){mutableStateOf(false)}
    var loaded by remember(model){mutableStateOf(false)}
    var viewing by remember(model){mutableStateOf(false)}
    var retry by remember(model){mutableIntStateOf(0)}
    val context=LocalContext.current
    BoxWithConstraints(modifier.fillMaxWidth(),contentAlignment=Alignment.TopCenter) {
        val available=minOf(this.maxWidth,maxWidth)
        val width=minOf(available,420.dp*ratio)
        val height=width/ratio
        Box(Modifier.width(width).height(height).clip(androidx.compose.ui.graphics.RectangleShape).background(RoomColors.Wall)
            .testTag(tag).clickable(enabled=model!=null&&loaded,onClickLabel="查看大图：$description"){viewing=true},contentAlignment=Alignment.Center) {
            if(model!=null)key(model,retry){AsyncImage(
                remember(model,retry){if(model is ImageRequest)model.newBuilder().build()else ImageRequest.Builder(context).data(model).size(coil.size.Size.ORIGINAL).build()},
                description,Modifier.fillMaxSize(),contentScale=ContentScale.Fit,
                onSuccess={result->val image=result.result.drawable;if(image.intrinsicWidth>0&&image.intrinsicHeight>0)ratio=image.intrinsicWidth.toFloat()/image.intrinsicHeight;loaded=true;failed=false},
                onError={failed=true;loaded=false})}
            if(!loaded)Column(Modifier.padding(12.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Text(if(model==null)"暂无照片"else if(failed)"照片暂时无法加载"else "正在载入照片…",color=RoomColors.Muted,fontSize=12.sp)
                if(failed)SwissTextButton({failed=false;retry++}){Text("重试")}
            }
        }
    }
    // The native dialog restores window focus; an explicit request would scroll a partly visible photo.
    if(viewing)RoomCommunityPhotoViewer(model,description){viewing=false}
}

@Composable private fun RoomCommunityPhotoViewer(model:Any?,description:String,onClosed:()->Unit) {
    val motion=LocalPhysicalMotion.current
    var closing by remember{mutableStateOf(false)}
    val close by rememberUpdatedState(onClosed)
    val alpha=remember{Animatable(0f)}
    LaunchedEffect(closing){
        alpha.animateTo(if(closing)0f else 1f,tween(if(motion)if(closing)140 else 190 else 0))
        if(closing)close()
    }
    Dialog(onDismissRequest={closing=true},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        val view=LocalView.current
        SideEffect{(view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f)}
        Box(Modifier.fillMaxSize().background(Color(0xFF1C2B38).copy(alpha=.35f*alpha.value)).clickable{closing=true}.padding(20.dp),contentAlignment=Alignment.Center) {
            RoomPaper(Modifier.fillMaxWidth().widthIn(max=480.dp).graphicsLayer{this.alpha=alpha.value}.testTag("room-community-photo-viewer").pointerInput(Unit){detectTapGestures{}},woodEdge=false) {
                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(description,Modifier.weight(1f),color=RoomColors.Ink,fontSize=14.sp)
                        IconButton({closing=true}){Icon(Icons.Outlined.Close,"关闭大图",tint=RoomColors.Ink)}
                    }
                    AsyncImage(model,description,Modifier.fillMaxWidth().heightIn(min=120.dp,max=480.dp),contentScale=ContentScale.Fit)
                }
            }
        }
    }
}
