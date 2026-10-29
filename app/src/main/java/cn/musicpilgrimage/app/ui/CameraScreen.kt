package cn.musicpilgrimage.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Rational
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.musicpilgrimage.app.data.Scene
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import java.io.File
import java.util.UUID

@Composable internal fun CameraScreen(scene:Scene?,onBack:()->Unit,onPhoto:(Uri)->Unit) {
 val context=LocalContext.current;val lifecycle=LocalLifecycleOwner.current
 var allowed by remember{mutableStateOf(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)}
 var error by remember{mutableStateOf<String?>(null)};var opacity by remember{mutableFloatStateOf(.4f)};var capturing by remember{mutableStateOf(false)}
 var retry by remember(scene?.id){mutableIntStateOf(0)}
 val configuration=LocalConfiguration.current
 // Decode before showing the viewfinder. An explicit size avoids waiting for
 // the not-yet-visible painter's draw constraints and keeps failure/retry live.
 val model=remember(scene?.imageUrl,retry){ImageRequest.Builder(context).data(scene?.imageUrl.takeIf{scene?.canRecreate==true}).size(coil.size.Size.ORIGINAL).allowHardware(false).setParameter("retry",retry).build()}
 val reference=rememberAsyncImagePainter(model)
 val imageReady=reference.state is AsyncImagePainter.State.Success && scene?.canRecreate==true
 val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){allowed=it;if(!it)error="相机权限未开启，可返回或重新授权。"}
 val preview=remember{PreviewView(context).apply{scaleType=PreviewView.ScaleType.FILL_CENTER;implementationMode=PreviewView.ImplementationMode.COMPATIBLE}}
 val capture=remember{ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()}
 var ready by remember{mutableStateOf(false)}
 DisposableEffect(allowed,lifecycle,imageReady,configuration.orientation,scene?.id) {
  var provider:ProcessCameraProvider?=null;var disposed=false
  if(allowed&&imageReady&&scene!=null){
   val future=ProcessCameraProvider.getInstance(context)
   future.addListener({if(!disposed)try{
    provider=future.get();val rotation=preview.display?.rotation?:android.view.Surface.ROTATION_0
    capture.targetRotation=rotation
    val viewfinder=Preview.Builder().setTargetRotation(rotation).build().also{it.setSurfaceProvider(preview.surfaceProvider)}
    val image=(reference.state as AsyncImagePainter.State.Success).result.drawable
    val viewport=ViewPort.Builder(Rational(image.intrinsicWidth,image.intrinsicHeight),rotation).setScaleType(ViewPort.FILL_CENTER).build()
    val group=UseCaseGroup.Builder().addUseCase(viewfinder).addUseCase(capture).setViewPort(viewport).build()
    provider!!.bindToLifecycle(lifecycle,CameraSelector.DEFAULT_BACK_CAMERA,group);ready=true
   }catch(failure:Exception){android.util.Log.w("PilgrimageCamera","Camera binding failed",failure);error="相机启动失败，请退出相机后重试"}},ContextCompat.getMainExecutor(context))
  }
  onDispose{disposed=true;ready=false;provider?.unbindAll()}
 }
 Column(Modifier.fillMaxSize()){BackBar("复刻镜头",onBack)
  if(scene?.canRecreate!=true)Box(Modifier.padding(20.dp)){StatusCard("暂不能复刻","尚无可靠机位。仍可记录到访。")}
  else if(!imageReady)Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
   Text(if(reference.state is AsyncImagePainter.State.Error)"原图加载失败，叠图已关闭" else "正在加载原作参考图",color=Muted)
   if(reference.state is AsyncImagePainter.State.Error)Button({retry++}){Text("重试原图")} else CircularProgressIndicator(color=Acid)
  }
  else if(!allowed)Box(Modifier.padding(20.dp)){StatusCard("使用相机","需要相机权限才能拍摄。",Icons.Rounded.CameraAlt,"允许使用相机"){permission.launch(Manifest.permission.CAMERA)}}
  else {
   BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).background(Color.Black),contentAlignment=Alignment.Center){
    val drawable=(reference.state as AsyncImagePainter.State.Success).result.drawable
    val ratio=drawable.intrinsicWidth.toFloat()/drawable.intrinsicHeight
    val frame=if(maxWidth/maxHeight>ratio)Modifier.fillMaxHeight().aspectRatio(ratio) else Modifier.fillMaxWidth().aspectRatio(ratio)
    Box(frame.clipToBounds()){AndroidView({preview},Modifier.fillMaxSize());Image(reference,"原作叠图参考",Modifier.fillMaxSize(),alpha=opacity,contentScale=ContentScale.Fit)}
   }
   Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
    ErrorNotice(error)
    if(scene?.associationPrecision!="camera")Text("场景构图对照；到达标记不是精确摄影机位。",style=MaterialTheme.typography.bodySmall,color=Muted)
    Text("原作透明度 ${(opacity*100).toInt()}%",color=Muted);Slider(opacity,{opacity=it})
    Text("叠图不会写入照片；提交需验证到访位置。",style=MaterialTheme.typography.bodySmall,color=Muted)
    Button({
     capturing=true;error=null
     val file=File(File(context.cacheDir,"camera").apply{mkdirs()},"${UUID.randomUUID()}.jpg")
     capture.targetRotation=preview.display?.rotation?:android.view.Surface.ROTATION_0
     capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(),ContextCompat.getMainExecutor(context),object:ImageCapture.OnImageSavedCallback {
      override fun onImageSaved(result:ImageCapture.OutputFileResults){capturing=false;onPhoto(Uri.fromFile(file))}
      override fun onError(exception:ImageCaptureException){capturing=false;file.delete();error="拍摄失败，请重试"}
     })
    },enabled=ready&&imageReady&&!capturing,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)){Text(if(capturing)"正在保存照片…" else "拍摄")}
   }
  }
 }
}
