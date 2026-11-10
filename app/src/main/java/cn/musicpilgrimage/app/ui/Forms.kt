package cn.musicpilgrimage.app.ui

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
