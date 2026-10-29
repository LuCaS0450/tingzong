package cn.musicpilgrimage.app.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.*
import cn.musicpilgrimage.app.*
import cn.musicpilgrimage.app.data.*
import coil.compose.AsyncImage
import org.json.JSONObject
import cn.musicpilgrimage.app.design.*
import cn.musicpilgrimage.app.design.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.launch

@Composable internal fun AuthScreen(session:SessionViewModel,onBack:()->Unit) {
 androidx.activity.compose.BackHandler {onBack()}
 var email by rememberSaveable{mutableStateOf("")};var password by remember{mutableStateOf("")};var register by rememberSaveable{mutableStateOf(false)}
 var reset by rememberSaveable{mutableStateOf(false)};var visible by remember{mutableStateOf(false)}
 val busy by session.busy.collectAsState();val recovery by session.recovery.collectAsState()
 Column(Modifier.fillMaxSize()){BackBar(if(recovery)"设置新密码" else if(reset)"找回密码" else if(register)"注册" else "登录",onBack)
  Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
   Text("听踪",style=MaterialTheme.typography.headlineLarge)
   ViewModelStatus(session)
   if(!recovery)OutlinedTextField(email,{email=it},Modifier.fillMaxWidth().testTag("sign-in-email"),label={Text("邮箱")},singleLine=true,keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=KeyboardType.Email))
   if(!reset||recovery)OutlinedTextField(password,{password=it},Modifier.fillMaxWidth().testTag("sign-in-password"),label={Text(if(recovery)"新密码" else "密码")},singleLine=true,
    visualTransformation=if(visible)VisualTransformation.None else PasswordVisualTransformation(),keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=KeyboardType.Password),
    trailingIcon={IconButton({visible=!visible}){Icon(if(visible)Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,if(visible)"隐藏密码" else "显示密码")}},
    supportingText={if(register||recovery)Text("10–128位")})
   Button({if(recovery)session.password(password,onBack) else if(reset)session.reset(email) else session.authenticate(email,password,register)},
    Modifier.fillMaxWidth().heightIn(min=52.dp).testTag("sign-in-submit"),enabled=!busy&&(if(recovery)password.length in 10..128 else email.contains('@')&&(reset||password.isNotBlank()))){
    Text(if(busy)"请稍候…" else if(recovery)"保存新密码" else if(reset)"发送重置邮件" else if(register)"注册" else "登录")}
   if(!recovery){
    if(reset)TextButton({reset=false;session.clearError();session.notice.value=null}){Text("返回登录")}
    else {
     Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
      TextButton({register=!register;password="";session.clearError();session.notice.value=null},enabled=!busy){Text(if(register)"已有账号，登录" else "注册账号")}
      TextButton({reset=true;session.clearError();session.notice.value=null},enabled=!busy){Text("忘记密码")}
     }
     if(register)TextButton({session.resend(email)},enabled=!busy&&email.contains('@')){Text("重发验证邮件")}
    }
    if(register||reset)Text("在此设备打开邮件中的链接。",color=Muted,style=MaterialTheme.typography.bodySmall)
   }
  }
 }
}

@Composable internal fun EditProfileScreen(session:SessionViewModel,onBack:()->Unit,onSaved:()->Unit=onBack) {
 androidx.activity.compose.BackHandler {onBack()}
 val p by session.profile.collectAsState();val busy by session.busy.collectAsState()
 var nickname by rememberSaveable(p?.optString("id")){mutableStateOf(p?.optString("nickname").orEmpty())};var bio by rememberSaveable(p?.optString("id")){mutableStateOf(p?.optString("bio").orEmpty())}
 Column {BackBar("个人资料",onBack);Column(Modifier.verticalScroll(rememberScrollState()).imePadding().padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
  ViewModelStatus(session);Text("昵称用于公开内容与主页。",color=Muted,style=MaterialTheme.typography.bodySmall)
  OutlinedTextField(nickname,{nickname=it.take(32)},Modifier.fillMaxWidth().testTag("profile-nickname"),label={Text("昵称（2–32字）")},singleLine=true)
  OutlinedTextField(bio,{bio=it.take(200)},Modifier.fillMaxWidth(),label={Text("简介")},minLines=3,supportingText={Text("${bio.length}/200")})
  Button({session.updateProfile(JSONObject().put("nickname",nickname).put("bio",bio),onSaved)},Modifier.fillMaxWidth().testTag("profile-save"),enabled=!busy&&nickname.trim().length>=2&&p?.optString("status")=="active"){Text("保存")}
 }}
}

@Composable internal fun ContentForm(kind:String,target:String,editing:ContentItem?,vm:FormViewModel,onBack:()->Unit,onCamera:(String)->Unit) {
 val fields by vm.fields.collectAsState();val busy by vm.busy.collectAsState();val ready by vm.initialized.collectAsState()
 val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){result->if(result[Manifest.permission.ACCESS_FINE_LOCATION]==true)vm.submit(onBack) else vm.error.value="需要精确位置权限才能进行现场认证。草稿已保留。"}
 val photo=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()){uri->if(uri!=null)vm.photo(uri)}
 LaunchedEffect(kind,target){vm.initialize(kind,target,editing)}
 val title=mapOf("message" to "地点留言","visit" to "实地巡礼","recreation" to "场景复刻","new_location" to "新地点投稿","correction" to "信息修正")[kind].orEmpty()
 val onsite=editing==null&&kind in listOf("visit","recreation")
 Column(Modifier.fillMaxSize()) {BackBar(title,onBack)
  Column(Modifier.verticalScroll(rememberScrollState()).imePadding().padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
   ViewModelStatus(vm)
   if(!ready)return@Column
   Text(if(onsite)"提交时验证当前位置 · 草稿自动保存在本机" else "草稿自动保存在本机",color=Muted,style=MaterialTheme.typography.bodySmall)
   if(kind in listOf("new_location","correction")) {
    Text("作品与地点的关联类型",style=MaterialTheme.typography.bodySmall,color=Muted)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
     listOf("mv","album","song_title","lyric").forEach{type->FilterChip(fields.optString("association_type")==type,{vm.set("association_type",type)},label={Text(associationLabel(type))})}
    }
    FormField("place_name","地点名称",fields,vm);FormField("title","相关作品",fields,vm);FormField("artist","歌手",fields,vm);FormField("source_url","可核实的来源链接",fields,vm)
    FormField("association_excerpt",if(fields.optString("association_type")=="lyric")"提到地点的短歌词" else "歌名或地点关联说明",fields,vm)
    FormField("canonical_work_url","原作或正式发行封面链接（可选）",fields,vm)
   } else if(kind=="message") {
    FormField("song","关联歌曲（可选）",fields,vm);FormField("artist","歌手（可选）",fields,vm)
   }
   OutlinedTextField(fields.optString("body"),{vm.set("body",it.take(1000))},Modifier.fillMaxWidth(),label={Text(if(kind in listOf("new_location","correction"))"投稿说明与核实依据" else "想说的话")},minLines=5,supportingText={Text("${fields.optString("body").length}/1000")})
   if(kind in listOf("visit","recreation","new_location","correction")) {
    val local=fields.stringOrNull("local_photo")
    if(local!=null)ReferenceImage(java.io.File(local).toURI().toString(),"草稿照片",Modifier.fillMaxWidth().aspectRatio(4f/3f))
    else if(!fields.optBoolean("clear_media"))editing?.mediaId?.let{PrivatePhoto(it,vm.services,Modifier.fillMaxWidth().aspectRatio(4f/3f))}
    if(kind=="recreation"&&editing==null)Button({onCamera(target)},enabled=!busy,modifier=Modifier.fillMaxWidth()){Icon(Icons.Rounded.CameraAlt,null);Spacer(Modifier.width(8.dp));Text("打开原作叠图相机")}
    else OutlinedButton({photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text(if(local!=null||editing?.mediaId!=null)"更换照片" else "选择照片")}
    if(local!=null||kind=="visit"&&editing?.mediaId!=null)TextButton(vm::removePhoto,enabled=!busy){Text("移除照片")}
   }
   if(kind in listOf("visit","recreation"))Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("公开到社区");Text("默认仅自己可见",style=MaterialTheme.typography.bodySmall,color=Muted)};Switch(fields.optString("visibility","private")=="public",{vm.set("visibility",if(it)"public" else "private")})}
   Button({if(onsite)permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)) else vm.submit(onBack)},Modifier.fillMaxWidth().heightIn(min=52.dp),enabled=!busy){Text(if(busy)"正在提交…" else if(onsite)"获取位置并提交" else "保存并提交")}
  }
 }
}
@Composable private fun FormField(key:String,title:String,fields:JSONObject,vm:FormViewModel) {OutlinedTextField(fields.optString(key),{vm.set(key,it.take(if(key=="source_url")1000 else 100))},Modifier.fillMaxWidth(),label={Text(title)},singleLine=true)}

@Composable internal fun SettingsScreen(session:SessionViewModel,onBack:()->Unit,onSignedOut:()->Unit,showHeader:Boolean=true) {
 val owner by session.signedIn.collectAsState();var delete by rememberSaveable{mutableStateOf(false)};var confirm by rememberSaveable{mutableStateOf("")};val busy by session.busy.collectAsState()
 val context=LocalContext.current;val preferences=remember {AppPreferences(context)};val pref by preferences.changes.collectAsState(AppPreferencesState());val scope=rememberCoroutineScope()
 Column{if(showHeader)BackBar("设置与隐私",onBack);Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
  Text("听踪",style=MaterialTheme.typography.headlineLarge);Text("${BuildConfig.VERSION_NAME}",color=Muted,style=MaterialTheme.typography.bodySmall);ViewModelStatus(session)
  BauhausSetting("静音",pref.muted,{scope.launch{preferences.set(pref.copy(muted=!pref.muted))}},Modifier.fillMaxWidth())
  BauhausSetting("减少动态",pref.reducedMotion,{scope.launch{preferences.set(pref.copy(reducedMotion=!pref.reducedMotion))}},Modifier.fillMaxWidth())
  BauhausDisclosure("位置与公开范围") {
   Text("现场认证保存时间、精度与距离，不长期保存原始定位。")
   Text("巡礼和复刻默认私密。公开内容可被其他登录用户浏览、点赞和举报。管理员不能任意查看私密照片。")
  }
  BauhausDisclosure("本机草稿与图片") {
   Text("草稿按账号保存在本机，断网不会自动发布，卸载后删除。现场提交会重新定位。")
   Text("上传前自动优化图片并清除位置元数据，云端最多保存100张图片。")
  }
  if(owner!=null){OutlinedButton({session.signOut(onSignedOut)},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("退出登录")};TextButton({delete=true},enabled=!busy){Text("注销账号")}}
 }}
 if(delete)AlertDialog(onDismissRequest={if(!busy)delete=false},title={Text("注销账号")},text={Column{Text("不可恢复。云端调查档案、纪念票、巡礼、复刻、媒体与互动会删除；留言匿名保留，已采纳投稿解除身份关联。请输入“删除账号”。");OutlinedTextField(confirm,{confirm=it},label={Text("删除账号")});ViewModelStatus(session)}},confirmButton={TextButton({session.deleteAccount{delete=false;onSignedOut()}},enabled=confirm=="删除账号"&&!busy){Text("确认注销")}},dismissButton={TextButton({delete=false},enabled=!busy){Text("取消")}})
}
