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
import cn.musicpilgrimage.app.SessionViewModel
import androidx.compose.ui.platform.testTag

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
