package cn.musicpilgrimage.app.ui.room

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import cn.musicpilgrimage.app.FormViewModel
import cn.musicpilgrimage.app.data.*
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt

internal fun roomRecordTitle(kind:String)=when(kind){"message"->"地点留言";"visit"->"到访记录";"recreation"->"复刻记录";else->"编辑记录"}

@Composable internal fun RoomRecordEditor(
    kind:String,target:String,editing:ContentItem?,vm:FormViewModel,onBack:()->Unit,onSaved:()->Unit,onCamera:(String)->Unit,
    registerLeave:(((()->Unit)->Unit)?)->Unit,onBusy:(Boolean)->Unit
) {
    val fields by vm.fields.collectAsState()
    val busy by vm.busy.collectAsState()
    val ready by vm.initialized.collectAsState()
    val error by vm.error.collectAsState()
    val catalog by vm.services.catalog.collectAsState()
    val context=LocalContext.current
    var leave by remember{mutableStateOf<(() -> Unit)?>(null)}
    var cameraPath by rememberSaveable{mutableStateOf<String?>(null)}
    val currentRegister by rememberUpdatedState(registerLeave)
    val currentLeave by rememberUpdatedState<(()->Unit)->Unit>({action->if(!busy){if(vm.hasChanges())leave=action else action()}})
    LaunchedEffect(kind,target){vm.initialize(kind,target,editing)}
    DisposableEffect(vm){currentRegister{action->currentLeave(action)};onDispose{currentRegister(null);onBusy(false)}}
    SideEffect{onBusy(busy)}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){result->
        if(result[Manifest.permission.ACCESS_FINE_LOCATION]==true)vm.submit(onSaved)
        else vm.error.value="需要精确位置权限才能进行现场认证。草稿已保留。"
    }
    val album=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()){uri->uri?.let{vm.photo(it)}}
    val capture=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()){success->
        val file=cameraPath?.let(::File);cameraPath=null
        if(file!=null) {
            if(success)vm.photo(FileProvider.getUriForFile(context,"${context.packageName}.files",file)){file.delete()}
            else file.delete()
        }
    }
    val beginCapture:()->Unit={
        runCatching {
            val directory=File(context.cacheDir,"camera").apply{mkdirs()}
            val file=File(directory,"record-${UUID.randomUUID()}.jpg")
            cameraPath=file.path
            capture.launch(FileProvider.getUriForFile(context,"${context.packageName}.files",file))
        }.onFailure{cameraPath?.let{File(it).delete()};cameraPath=null;vm.error.value="暂时无法打开相机，可使用相册选择。"}
    }
    val cameraPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->if(granted)beginCapture()else vm.error.value="未开启相机权限，已有草稿会保留。"}
    val scene=catalog.places.flatMap{it.scenes}.firstOrNull{it.id==target||it.id==editing?.sceneId}
    val place=catalog.places.firstOrNull{it.id==target||it.id==editing?.location||it.scenes.any{s->s.id==scene?.id}}
    val local=fields.stringOrNull("local_photo")
    val stored=roomPrivatePhotoModel(editing?.mediaId?.takeUnless{fields.optBoolean("clear_media")},vm.services)
    val photo=local?.let{File(it).toURI().toString()} ?: stored
    val original=remember(scene?.id,scene?.imageUrl){scene?.let{BundledCovers.model(context,"reference-${it.id}",it.imageUrl)}}
    val onsite=RecordDraftRules.requiresLocation(kind,editing!=null,local!=null)
    val scope=rememberCoroutineScope()
    var locating by remember(kind,target){mutableStateOf(false)}
    var locationStatus by remember(kind,target){mutableStateOf("尚未定位")}
    val locate:()->Unit={if(!locating&&place!=null){scope.launch {
        locating=true;locationStatus="正在查看当前位置…"
        try {
            val fix=vm.services.location.locate()
            val distance=ArrivalRules.distanceMeters(fix.latitude,fix.longitude,place.latitude,place.longitude).roundToInt()
            val problem=ArrivalRules.rejection(place,fix)
            locationStatus=when {
                problem==null->"距离这里 ${distance} 米 · 已在打卡范围内"
                distance>place.radiusMeters&&!fix.isMock&&fix.accuracy<=100&&fix.ageMillis<=120_000->"距离这里 ${distance} 米 · 还需靠近一些"
                else->problem?:"暂时无法定位，请稍后再试"
            }
        } catch(cancel:CancellationException){throw cancel}
        catch(error:Exception){locationStatus=error.message?:"暂时无法定位，请稍后再试"}
        finally {locating=false}
    }}}
    val locationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){result->
        if(result[Manifest.permission.ACCESS_FINE_LOCATION]==true)locate()else locationStatus="开启精确定位后，就能查看与这里的距离。"
    }
    RoomRecordEditorContent(kind,place?.let{PlaceEditorialCopy.name(context,it)}?:editing?.placeName.orEmpty(),editing?.raw?.stringOrNull("verified_at")?.take(10),fields,photo,
        RecordDraftRules.hasPhoto(fields,editing?.mediaId),original,busy,ready,error,onsite,
        vm::set,{album.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))},
        {if(kind=="recreation"&&editing==null)onCamera(target)else if(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)beginCapture()else cameraPermission.launch(Manifest.permission.CAMERA)},
        {vm.removePhoto()},{if(onsite)permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))else vm.submit(onSaved)},onBack,editing=editing!=null,
        visitRadius=place?.radiusMeters,locationStatus=locationStatus,locationBusy=locating,onLocate={
            if(ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED)locate()
            else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))
        })
    if(leave!=null)RoomRecordDiscardDialog(busy,{leave=null},{vm.discard{val action=leave;leave=null;action?.invoke()}},error)
}

@Composable internal fun RoomRecordDiscardDialog(busy:Boolean,onContinue:()->Unit,onDiscard:()->Unit,error:String?=null) {
    AlertDialog(onDismissRequest={if(!busy)onContinue()},containerColor=RoomColors.Paper,shape=androidx.compose.ui.graphics.RectangleShape,
        title={Text("放弃未保存的修改？")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("当前改动不会保存，已保存的原记录会保留。")
            if(error!=null)Text(error,color=MaterialTheme.colorScheme.error,fontSize=13.sp)
        }},confirmButton={SwissTextButton(onDiscard,enabled=!busy){Text(if(busy)"正在处理…"else "放弃修改")}},dismissButton={SwissTextButton(onContinue,enabled=!busy){Text("继续编辑")}})
}

@Composable internal fun RoomRecordEditorContent(
    kind:String,place:String,date:String?,fields:JSONObject,photo:Any?,hasPhoto:Boolean,original:Any?,busy:Boolean,ready:Boolean,error:String?,onsite:Boolean,
    onSet:(String,String)->Unit,onAlbum:()->Unit,onCamera:()->Unit,onRemove:()->Unit,onSave:()->Unit,onCancel:()->Unit,editing:Boolean=false,
    visitRadius:Int?=null,locationStatus:String="尚未定位",locationBusy:Boolean=false,onLocate:(()->Unit)?=null
) {
    val canPublish=kind!="visit"||fields.optString("body").isNotBlank()||hasPhoto
    val heading=remember(place){placeHeading(place)}
    LazyColumn(Modifier.fillMaxSize().imePadding().testTag("room-record-editor"),contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        if(busy||!ready)item("loading"){LinearProgressIndicator(Modifier.fillMaxWidth(),color=RoomColors.Navy)}
        if(error!=null)item("error"){Text(error,color=MaterialTheme.colorScheme.error,fontSize=13.sp)}
        if(ready) {
            item("form"){RoomPaper(Modifier.fillMaxWidth(),woodEdge=true){Column(Modifier.padding(17.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                Text(heading.title.ifBlank{roomRecordTitle(kind)},fontSize=23.sp,color=RoomColors.Ink)
                if(date!=null)Text("到访日期 · $date",fontSize=13.sp,color=RoomColors.Muted)
                if(onsite&&visitRadius!=null)Column(Modifier.fillMaxWidth().testTag("visit-location-status"),verticalArrangement=Arrangement.spacedBy(9.dp)) {
                    HorizontalDivider(color=RoomColors.Line)
                    Text("到访打卡",fontSize=15.sp,color=RoomColors.Ink)
                    Text("请到地图标记附近 ${visitRadius} 米内打卡。",fontSize=13.sp,lineHeight=22.sp,color=RoomColors.Muted)
                    Text(locationStatus,fontSize=13.sp,lineHeight=22.sp,color=RoomColors.Ink)
                    if(onLocate!=null)SwissOutlinedButton(onLocate,Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("visit-check-location"),enabled=!busy&&!locationBusy){Text(if(locationBusy)"正在定位…"else "查看当前位置")}
                    HorizontalDivider(color=RoomColors.Line)
                }
                if(kind=="message") {
                    OutlinedTextField(fields.optString("song"),{onSet("song",it.take(100))},Modifier.fillMaxWidth(),label={Text("关联歌曲（选填）")},singleLine=true,enabled=!busy)
                    OutlinedTextField(fields.optString("artist"),{onSet("artist",it.take(100))},Modifier.fillMaxWidth(),label={Text("歌手（选填）")},singleLine=true,enabled=!busy)
                }
                OutlinedTextField(fields.optString("body"),{onSet("body",it.take(1000))},Modifier.fillMaxWidth().testTag("room-record-body"),
                    label={Text(if(kind=="message")"留言"else if(kind=="recreation")"这次复刻的感想"else "到访感想（选填）")},minLines=4,enabled=!busy,
                    supportingText={Text("${fields.optString("body").length}/1000")})
                if(kind=="recreation"&&original!=null) {
                    Text("原作参考",fontSize=13.sp,color=RoomColors.Muted)
                    RoomCommunityPhoto(original,"原作参考图",maxWidth=200.dp,tag="room-record-original")
                }
                if(kind in listOf("visit","recreation")) {
                    Text(if(kind=="recreation")"复刻照片（一张）"else "照片（选填，一张）",fontSize=15.sp,color=RoomColors.Ink)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        SwissOutlinedButton(onAlbum,Modifier.weight(1f).heightIn(min=48.dp),enabled=!busy,shape=androidx.compose.ui.graphics.RectangleShape,colors=ButtonDefaults.outlinedButtonColors(contentColor=RoomColors.Navy)){Icon(Icons.Outlined.PhotoLibrary,null,Modifier.size(17.dp));Spacer(Modifier.width(7.dp));Text("相册选择",fontSize=13.sp)}
                        SwissOutlinedButton(onCamera,Modifier.weight(1f).heightIn(min=48.dp),enabled=!busy,shape=androidx.compose.ui.graphics.RectangleShape,colors=ButtonDefaults.outlinedButtonColors(contentColor=RoomColors.Navy)){Icon(Icons.Outlined.PhotoCamera,null,Modifier.size(17.dp));Spacer(Modifier.width(7.dp));Text("现场拍照",fontSize=13.sp)}
                    }
                    if(hasPhoto) {
                        RoomCommunityPhoto(photo,if(kind=="recreation")"复刻照片预览"else "到访照片预览",maxWidth=200.dp,tag="room-record-photo")
                        SwissTextButton(onRemove,enabled=!busy){Icon(Icons.Outlined.ImageNotSupported,null,Modifier.size(17.dp));Spacer(Modifier.width(7.dp));Text("移除照片")}
                    } else Text(if(kind=="visit")"可以只写感想，也可以只保留到访日期。"else "复刻记录需要保留一张照片。",fontSize=12.sp,lineHeight=20.sp,color=RoomColors.Muted)
                    HorizontalDivider(color=RoomColors.Ink.copy(alpha=.12f))
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("公开到社区",color=RoomColors.Ink,fontSize=15.sp)
                            Text(if(canPublish)"关闭后仅自己可见"else "只有到访日期的记录会保持私密",color=RoomColors.Muted,fontSize=12.sp,lineHeight=19.sp)
                        }
                        Switch(fields.optString("visibility","private")=="public"&&canPublish,{onSet("visibility",if(it)"public"else "private")},modifier=Modifier.testTag("room-record-public"),enabled=!busy&&canPublish)
                    }
                }
            }}}
            item("actions"){Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                SwissButton(onSave,Modifier.fillMaxWidth().heightIn(min=50.dp).testTag("room-record-save"),shape=androidx.compose.ui.graphics.RectangleShape,colors=ButtonDefaults.buttonColors(containerColor=RoomColors.Navy),enabled=!busy&&!locationBusy&&(kind!="message"||fields.optString("body").isNotBlank())&&(kind!="recreation"||hasPhoto)) {
                    Text(if(busy)"正在保存…"else if(onsite&&kind=="visit")"打卡并保存"else if(onsite)"确认位置并保存"else if(kind=="message"&&!editing)"保存留言"else "保存修改")
                }
                SwissTextButton(onCancel,Modifier.fillMaxWidth().heightIn(min=48.dp),enabled=!busy){Text("取消")}
            }}
        }
    }
}
