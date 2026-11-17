package cn.musicpilgrimage.app.ui.room

import cn.musicpilgrimage.app.design.AppArt
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.musicpilgrimage.app.data.ReleaseArtwork
import coil.compose.AsyncImage
import org.json.JSONObject

internal val LocalRoomSpaceAction = staticCompositionLocalOf<(() -> Unit)?> { null }
internal val LocalRoomSpaceOrigin = staticCompositionLocalOf<(Offset) -> Unit> { {} }

internal object RoomColors {
    val Wall = AppArt.Base
    val Paper = AppArt.Paper
    val Ink = AppArt.Ink
    val Muted = AppArt.Muted
    val Navy = Ink
    val Red = AppArt.Red
    val Line = AppArt.Line
    val ArchiveWall = Wall
}
internal fun roomAsset(name:String,extension:String="webp") = "file:///android_asset/design/music-space/$name.$extension"
internal object BundledCovers {
    private var index:JSONObject?=null
    @Synchronized fun model(context:android.content.Context,id:String,remote:String?):String? {
        val values=index ?: runCatching {JSONObject(context.assets.open("design/music-space/covers/index.json").bufferedReader().use{it.readText()})}.getOrDefault(JSONObject()).also{index=it}
        return values.optJSONObject(id)?.optString("file")?.takeIf{it.isNotEmpty()}?.let{"file:///android_asset/design/music-space/covers/$it"} ?: remote
    }
}

internal fun releaseCoverModel(context:android.content.Context,art:ReleaseArtwork?):String? =
    art?.takeIf { it.approved }?.let { BundledCovers.model(context,it.id,it.imageUrl?.takeIf(String::isNotBlank)?:it.thumbnailUrl) }

@Composable internal fun RoomWall(modifier:Modifier=Modifier, baseColor:androidx.compose.ui.graphics.Color=RoomColors.Wall, content:@Composable BoxScope.()->Unit) {
    // Only the four main rooms paint a textured wall; reading and editing stay flat.
    Box(modifier.background(baseColor),content=content)
}

@Composable internal fun RoomHeader(
    title:String, onBack:(()->Unit)?=null, onSearch:(()->Unit)?=null,
    onSpace:()->Unit, onSpaceOrigin:(Offset)->Unit={}, modifier:Modifier=Modifier, enabled:Boolean=true, onTitleClick:(()->Unit)?=null,
    titleAlpha:Float=1f, titleAccessibilityVisible:Boolean=true,
) {
    val titleSemantics=if(titleAccessibilityVisible)Modifier else Modifier.clearAndSetSemantics{}
    Row(modifier.fillMaxWidth().height(56.dp).padding(start=8.dp,end=16.dp), verticalAlignment=Alignment.CenterVertically) {
        if(onBack!=null) IconButton(onBack,Modifier.testTag("room-back"),enabled=enabled) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,"返回") }
        if(onTitleClick!=null) Box(Modifier.weight(1f).graphicsLayer{alpha=titleAlpha}.then(titleSemantics)) {
            SwissTextButton(onTitleClick,Modifier.testTag("room-open-catalogue"),enabled=enabled,contentPadding=PaddingValues(horizontal=8.dp,vertical=8.dp)) {
                Text(title,fontSize=18.sp,fontWeight=FontWeight.Medium,color=RoomColors.Ink)
                Icon(Icons.Outlined.ChevronRight,null,tint=RoomColors.Ink)
            }
        }
        else Text(title,Modifier.weight(1f).padding(start=if(onBack==null)8.dp else 2.dp).graphicsLayer{alpha=titleAlpha}.then(titleSemantics),
            fontSize=18.sp,fontWeight=FontWeight.Medium,color=RoomColors.Ink,maxLines=1,overflow=TextOverflow.Ellipsis)
        if(onSearch!=null) IconButton(onSearch,Modifier.testTag("room-search-button"),enabled=enabled) { Icon(Icons.Outlined.Search,"搜索歌曲") }
    }
}

@Composable internal fun RoomPaper(modifier:Modifier=Modifier, woodEdge:Boolean=false, content:@Composable BoxScope.()->Unit) {
    Box(modifier.background(RoomColors.Paper),content=content)
}

@Composable internal fun RoomCover(
    artwork:ReleaseArtwork?,
    title:String,
    modifier:Modifier=Modifier,
    sleeve:Boolean=false,
    shadow:Boolean=true,
    archiveResolution:Boolean=false,
    pinnedCover:ImageBitmap?=null,
    onLoadFailure:(()->Unit)?=null,
    onImageReady:((Boolean, String?, ImageBitmap?)->Unit)?=null,
) {
    val context=LocalContext.current
    val model=remember(artwork,archiveResolution){if(archiveResolution)releaseCoverModel(context,artwork)
        else artwork?.let{BundledCovers.model(context,it.id,it.thumbnailUrl?:it.imageUrl)}}
    val archiveWidth=androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width.coerceIn(1,1080)
    BoxWithConstraints(modifier.then(when {
        !shadow -> Modifier
        sleeve -> Modifier.sleeveShadow()
        else -> Modifier.coverShadow()
    }).then(if(sleeve)Modifier.coverSleeve()else Modifier).clip(RectangleShape).background(RoomColors.Paper),contentAlignment=Alignment.Center) {
        // A 282px wall cover was reused in a 281px rack slot. Keep each actual pixel target
        // in its own cache entry so repeated visits do not add a second resampling pass.
        val width=if(archiveResolution)archiveWidth else constraints.maxWidth.coerceAtLeast(1)
        val height=if(archiveResolution)(width/(artwork?.aspectRatio?:1f).coerceIn(.35f,3f)).toInt().coerceAtLeast(1) else constraints.maxHeight.coerceAtLeast(1)
        val memCacheKey=remember(model,width,height){"room-cover:$model:${width}x$height"}
        var loaded by remember(memCacheKey) { mutableStateOf(false) }
        var failed by remember(memCacheKey) { mutableStateOf(false) }
        var decodedCover by remember(memCacheKey) { mutableStateOf<ImageBitmap?>(null) }
        val request=remember(model,width,height,context,memCacheKey){coil.request.ImageRequest.Builder(context).data(model)
            .size(width,height).scale(coil.size.Scale.FIT).precision(coil.size.Precision.EXACT)
            .memoryCacheKey(memCacheKey).build()}
        val imageReady by rememberUpdatedState(onImageReady)
        LaunchedEffect(memCacheKey, artwork?.approved, loaded, failed, pinnedCover, decodedCover) {
            imageReady?.invoke(artwork?.approved == true && (pinnedCover!=null||loaded&&!failed),
                memCacheKey.takeIf { artwork?.approved==true },pinnedCover?:decodedCover)
        }
        if(pinnedCover!=null) Image(pinnedCover,"$title · 专辑封面",Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
        else if(artwork?.approved==true) AsyncImage(request,"$title · 专辑封面",
            Modifier.fillMaxSize(),contentScale=ContentScale.Fit,onError={failed=true;loaded=false;decodedCover=null},onSuccess={
                failed=false;loaded=true
                if(archiveResolution)decodedCover=(it.result.drawable as? BitmapDrawable)?.bitmap?.asImageBitmap()
            })
        if(artwork?.approved!=true || (failed&&pinnedCover==null)) Icon(Icons.Outlined.Album,if(failed)"封面暂时无法加载" else "封面待补",Modifier.size(28.dp),tint=RoomColors.Muted)
        val failureAction by rememberUpdatedState(onLoadFailure)
        LaunchedEffect(failed) { if(failed)failureAction?.invoke() }
    }
}

@Composable internal fun SwissEyebrow(label:String,modifier:Modifier=Modifier) {
    Text(label,modifier,color=RoomColors.Red,fontSize=11.sp,letterSpacing=1.2.sp,fontWeight=FontWeight.Medium)
}

@Composable internal fun SwissNumberedHeading(number:String,title:String) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        SwissEyebrow(number)
        Text(title,fontSize=20.sp,fontWeight=FontWeight.Medium,color=RoomColors.Ink)
    }
}

@Composable internal fun RoomReference(scene:cn.musicpilgrimage.app.data.Scene,description:String,modifier:Modifier=Modifier,onReady:(Boolean)->Unit={}) {
    val context=LocalContext.current
    val model=remember(scene.id,scene.imageUrl){BundledCovers.model(context,"reference-${scene.id}",scene.imageUrl)}
    // Animated card constraints can briefly be tiny; decode the reviewed original
    // so that a low-resolution interim request cannot remain after the card opens.
    val request=remember(model,context){coil.request.ImageRequest.Builder(context).data(model).size(coil.size.Size.ORIGINAL).build()}
    var failed by remember(model){mutableStateOf(false)}
    val readyCallback by rememberUpdatedState(onReady)
    LaunchedEffect(model){readyCallback(false)}
    val coverReference=scene.referenceKind in setOf("release_cover","album_photo")
    Box(modifier.then(if(coverReference)Modifier.coverShadow()else Modifier).background(RoomColors.Wall),contentAlignment=Alignment.Center) {
        AsyncImage(request,description,Modifier.fillMaxSize(),contentScale=ContentScale.Fit,onError={failed=true;readyCallback(false)},onSuccess={failed=false;readyCallback(true)})
        if(failed)Icon(Icons.Outlined.Image,"图片暂时无法加载",tint=RoomColors.Muted)
    }
}
