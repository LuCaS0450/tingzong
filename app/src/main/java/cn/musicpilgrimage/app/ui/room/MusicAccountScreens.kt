package cn.musicpilgrimage.app.ui.room

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.musicpilgrimage.app.MusicProfileDraft
import cn.musicpilgrimage.app.MusicAccountViewModel
import cn.musicpilgrimage.app.data.*
import cn.musicpilgrimage.app.design.AppPreferencesState
import cn.musicpilgrimage.app.ui.LocalPhysicalMotion
import coil.compose.AsyncImage
import org.json.JSONObject

@Composable internal fun RoomEmailLoginRoute(account:MusicAccountViewModel,onBack:(()->Unit)?=null) {
    val state by account.otp.state.collectAsState()
    RoomEmailLogin(state,account.otp::email,account.otp::code,account.otp::send,account.otp::verify,account.otp::registration,account.otp::help,onBack)
}

@Composable internal fun RoomEmailLogin(
    state:EmailOtpState,onEmail:(String)->Unit,onCode:(String)->Unit,onSend:()->Unit,
    onVerify:()->Unit,onRegister:(Boolean)->Unit,onHelp:(Boolean)->Unit,onBack:(()->Unit)?=null
) {
    val codeFocus=remember{FocusRequester()}
    val emailFocus=remember{FocusRequester()}
    val busy=state.busy!=null||state.verified
    val motion=LocalPhysicalMotion.current
    val registrationShake=remember{Animatable(0f)}
    LaunchedEffect(state.registrationRequired,motion) {
        registrationShake.snapTo(0f)
        if(state.registrationRequired&&motion)registrationShake.animateTo(0f,keyframes {
            durationMillis=300
            -4f at 65
            4f at 135
            -2f at 215
        })
    }
    BackHandler(state.help||onBack!=null){if(state.help)onHelp(false)else if(!busy)onBack?.invoke()}
    LaunchedEffect(state.focusCode,state.help){if(state.focusCode>0&&!state.help){withFrameNanos{};runCatching{codeFocus.requestFocus()}}}
    LaunchedEffect(state.emailError,state.codeError){withFrameNanos{};if(state.emailError!=null)runCatching{emailFocus.requestFocus()}else if(state.codeError!=null&&!state.help)runCatching{codeFocus.requestFocus()}}
    val title=when {
        state.help->"收不到验证码？"
        state.register->"注册听踪"
        else->"登录听踪"
    }
    val introduction=when {
        state.help->"检查邮箱地址、垃圾邮件与收件箱空间。"
        state.register->"用邮箱验证码创建你的音乐空间。"
        else->"用邮箱验证码进入你的音乐空间。"
    }
    Column(Modifier.fillMaxSize().testTag("room-email-login")) {
        if(onBack!=null||state.help)Row(Modifier.fillMaxWidth().height(54.dp).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){IconButton({if(state.help)onHelp(false)else onBack?.invoke()},enabled=!busy){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"返回登录")}}
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal=24.dp)) {
            Spacer(Modifier.height(if(onBack==null&&!state.help)30.dp else 8.dp))
            Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                SwissEyebrow("TINGZONG / ACCOUNT")
                Text(title,fontSize=34.sp,lineHeight=42.sp,fontWeight=FontWeight.SemiBold,color=RoomColors.Ink)
                Text(introduction,fontSize=14.sp,color=RoomColors.Muted,lineHeight=23.sp)
            }
            HorizontalDivider(Modifier.padding(top=18.dp,bottom=16.dp),color=RoomColors.Line)
            OutlinedTextField(state.email,onEmail,Modifier.fillMaxWidth().focusRequester(emailFocus).testTag("account-email"),label={Text("邮箱")},singleLine=true,enabled=!busy,isError=state.emailError!=null,
                supportingText={Text(state.emailError.orEmpty(),fontSize=12.sp)},shape=androidx.compose.ui.graphics.RectangleShape,
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email,imeAction=ImeAction.Next),keyboardActions=KeyboardActions(onNext={if(!state.help)codeFocus.requestFocus()}),
                colors=accountFieldColors())
            if(!state.help) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.Top) {
                    OutlinedTextField(state.code,onCode,Modifier.weight(1f).focusRequester(codeFocus).testTag("account-code"),label={Text("验证码")},singleLine=true,enabled=!busy,isError=state.codeError!=null,
                        supportingText={Text(state.codeError.orEmpty(),fontSize=12.sp)},shape=androidx.compose.ui.graphics.RectangleShape,
                        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword,imeAction=ImeAction.Done),keyboardActions=KeyboardActions(onDone={onVerify()}),colors=accountFieldColors())
                    SwissOutlinedButton(onSend,Modifier.width(125.dp).padding(top=8.dp).height(56.dp).testTag("account-send-code"),enabled=!busy&&state.remainingSeconds==0,shape=androidx.compose.ui.graphics.RectangleShape,contentPadding=PaddingValues(5.dp)) {
                        if(state.busy==OtpOperation.SEND)AccountWaiting("发送中",motion)
                        else Text(if(state.remainingSeconds>0)"${state.remainingSeconds}秒后重发" else "获取验证码",fontSize=13.sp,maxLines=1)
                    }
                }
                Spacer(Modifier.height(10.dp))
                SwissButton(onVerify,Modifier.fillMaxWidth().height(52.dp).testTag("account-sign-in"),enabled=!busy,shape=androidx.compose.ui.graphics.RectangleShape,colors=ButtonDefaults.buttonColors(containerColor=RoomColors.Navy)) {
                    if(state.busy==OtpOperation.VERIFY||state.verified)AccountWaiting(if(state.register)"注册中" else "登录中",motion)
                    else Text(if(state.register)"注册并进入" else "登录")
                }
            } else {
                Text("验证码可能需要一点时间送达。确认邮箱无误后，可以重新获取；不要向他人提供验证码。",fontSize=14.sp,lineHeight=23.sp,color=RoomColors.Muted)
                Spacer(Modifier.height(18.dp))
                SwissButton(onSend,Modifier.fillMaxWidth().height(52.dp).testTag("account-help-resend"),enabled=!busy&&state.remainingSeconds==0,shape=androidx.compose.ui.graphics.RectangleShape) {
                    if(state.busy==OtpOperation.SEND)AccountWaiting("发送中",motion)else Text(if(state.remainingSeconds>0)"${state.remainingSeconds}秒后重发" else "重新获取验证码")
                }
            }
            Box(Modifier.fillMaxWidth().heightIn(min=40.dp).padding(top=8.dp)) {
                Text(state.formError?:state.feedback.orEmpty(),fontSize=12.sp,lineHeight=18.sp,
                    color=if(state.formError!=null)MaterialTheme.colorScheme.error else RoomColors.Muted,
                    modifier=Modifier.testTag("account-feedback").semantics{liveRegion=LiveRegionMode.Polite})
            }
            if(!state.help)HorizontalDivider(color=RoomColors.Line)
            if(!state.help)Row(Modifier.fillMaxWidth().padding(top=4.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                SwissTextButton({onRegister(!state.register)},enabled=!busy,modifier=Modifier.testTag("account-registration")
                    .graphicsLayer{translationX=registrationShake.value.dp.toPx()}) {
                    Text(if(state.register)"已有账号，登录" else "注册账号",fontSize=13.sp,
                        color=if(state.registrationRequired)MaterialTheme.colorScheme.error else LocalContentColor.current)
                }
                SwissTextButton({onHelp(true)},enabled=!busy,modifier=Modifier.testTag("account-code-help")){Text("收不到验证码？",fontSize=13.sp)}
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable private fun AccountWaiting(text:String,motion:Boolean) {
    if(motion)CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=1.7.dp,color=LocalContentColor.current)else Icon(Icons.Outlined.MoreHoriz,null,Modifier.size(16.dp))
    Spacer(Modifier.width(7.dp));Text(text,fontSize=13.sp)
}
@Composable private fun accountFieldColors()=OutlinedTextFieldDefaults.colors(focusedContainerColor=RoomColors.Paper,unfocusedContainerColor=RoomColors.Paper,disabledContainerColor=RoomColors.Paper.copy(alpha=.7f))

@Composable internal fun RoomAccountAvatar(value:String?,modifier:Modifier=Modifier) {
    val bitmap=remember(value){AccountAvatar.bitmap(value)?.asImageBitmap()}
    Box(modifier.clip(CircleShape).background(RoomColors.Wall),contentAlignment=Alignment.Center) {
        if(bitmap!=null)Image(bitmap,"个人头像",Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
        else Icon(Icons.Outlined.PersonOutline,"个人头像",Modifier.fillMaxSize().padding(12.dp),tint=RoomColors.Muted)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun RoomProfileManagement(
    email:String,favorites:Int?,visits:Int?,disabled:Boolean,
    onEdit:()->Unit,onRoute:(String)->Unit,error:String?,onRetry:()->Unit
) {
    var expanded by remember {mutableStateOf(false)}
    RoomPaper(Modifier.fillMaxWidth().padding(start=18.dp,end=18.dp,bottom=8.dp)) {
        Box(Modifier.padding(horizontal=14.dp).testTag("account-profile-management")) {
            AccountLink("本人管理",Icons.Outlined.PersonOutline,last=true){expanded=true}
        }
    }
    if(expanded)ModalBottomSheet(onDismissRequest={expanded=false},containerColor=RoomColors.Paper,
        sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),shape=RoundedCornerShape(0.dp)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start=24.dp,end=24.dp,bottom=24.dp),
            verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("本人管理",fontSize=22.sp,fontWeight=FontWeight.SemiBold,color=RoomColors.Ink)
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text(email,Modifier.weight(1f),fontSize=13.sp,color=RoomColors.Muted)
                SwissTextButton({expanded=false;onEdit()},Modifier.testTag("account-edit-profile")){Text("编辑资料")}
            }
            if(error!=null)Column {
                Text(error,color=MaterialTheme.colorScheme.error,fontSize=13.sp)
                SwissTextButton(onRetry){Text("重新加载资料")}
            }
            if(disabled)Text("账号已停用，仍可查看和管理已有个人内容。",color=MaterialTheme.colorScheme.error,fontSize=13.sp)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf(Triple("收藏",favorites,"archive/releases"),Triple("到访",visits,"archive/footprints")).forEach{(label,count,route)->
                    Column(Modifier.weight(1f).clickable{expanded=false;onRoute(route)}.padding(vertical=12.dp),horizontalAlignment=Alignment.CenterHorizontally,
                        verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(count?.toString()?:"—",fontSize=25.sp,color=RoomColors.Ink,fontWeight=FontWeight.Medium)
                        Text(label,fontSize=13.sp,color=RoomColors.Muted)
                    }
                }
            }
        }
    }
}

@Composable internal fun AccountLink(title:String,icon:ImageVector,subtitle:String?=null,last:Boolean=false,onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick=onClick).heightIn(min=58.dp).padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(13.dp)) {
        Icon(icon,null,Modifier.size(21.dp),tint=RoomColors.Ink)
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){Text(title,fontSize=15.sp,color=RoomColors.Ink);if(subtitle!=null)Text(subtitle,fontSize=12.sp,color=RoomColors.Muted)}
        Icon(Icons.Outlined.ChevronRight,null,Modifier.size(21.dp),tint=RoomColors.Muted)
    }
    if(!last)HorizontalDivider(color=RoomColors.Line.copy(alpha=.65f),thickness=.5.dp)
}

@Composable internal fun RoomProfileEditor(draft:MusicProfileDraft,onNickname:(String)->Unit,onPhoto:(android.net.Uri)->Unit,onRemovePhoto:()->Unit,onSave:()->Unit,onRetry:()->Unit) {
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()){uri->uri?.let(onPhoto)}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(24.dp).testTag("room-profile-editor"),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        if(draft.loading)LinearProgressIndicator(Modifier.fillMaxWidth())
        AccountSection("01","头像") {
            Row(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                RoomAccountAvatar(draft.avatar,Modifier.size(64.dp))
                Column {
                    SwissTextButton({picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))},
                        enabled=draft.active&&!draft.saving&&!draft.processingPhoto) {
                        Text(if(draft.processingPhoto)"正在整理头像…" else "选择头像")
                    }
                    if(draft.avatar!=null)SwissTextButton(onRemovePhoto,enabled=!draft.saving&&!draft.processingPhoto){Text("移除头像",fontSize=12.sp)}
                }
            }
        }
        OutlinedTextField(draft.nickname,onNickname,Modifier.fillMaxWidth().testTag("profile-nickname"),label={Text("昵称")},singleLine=true,enabled=draft.active&&!draft.saving,shape=androidx.compose.ui.graphics.RectangleShape,supportingText={Text("2—32个字",fontSize=12.sp)},colors=accountFieldColors())
        draft.error?.let{Text(it,color=MaterialTheme.colorScheme.error,fontSize=13.sp,lineHeight=20.sp)}
        if(!draft.active&&!draft.loading)SwissTextButton(onRetry){Text("重新加载资料")}
        if(!draft.active&&!draft.loading&&draft.error==null)Text("当前账号无法修改资料。",fontSize=13.sp,color=RoomColors.Muted)
        SwissButton(onSave,Modifier.fillMaxWidth().height(52.dp).testTag("profile-save"),enabled=draft.active&&!draft.saving&&!draft.processingPhoto&&(draft.dirty||draft.error!=null),shape=androidx.compose.ui.graphics.RectangleShape) {if(draft.saving)AccountWaiting("保存中",LocalPhysicalMotion.current)else Text("保存")}
    }
}

@Composable internal fun RoomSettings(pref:AppPreferencesState,signedIn:Boolean,busy:Boolean,error:String?,onMuted:(Boolean)->Unit,onMotion:(Boolean)->Unit,onRoute:(String)->Unit,onSignOut:()->Unit) {
    var signOut by rememberSaveable{mutableStateOf(false)}
    LazyColumn(Modifier.fillMaxSize().testTag("room-settings"),contentPadding=PaddingValues(24.dp,14.dp,24.dp,30.dp),
        verticalArrangement=Arrangement.spacedBy(24.dp)) {
        item {
            AccountSection("01","播放与动效") {
                SettingToggle("静音",pref.muted,onMuted,"account-muted")
                SettingToggle("减少动态",pref.reducedMotion,onMotion,"account-reduced-motion")
            }
        }
        item {
            AccountSection("02","账号与隐私") {
                AccountLink("邮箱账号",Icons.Outlined.MailOutline){onRoute("account-email")}
                AccountLink("位置与公开范围",Icons.Outlined.LocationOn){onRoute("account-privacy")}
                AccountLink("本机草稿与图片",Icons.Outlined.PhotoLibrary,last=true){onRoute("account-storage")}
            }
        }
        item {
            AccountSection("03","应用信息") {
                AccountLink("关于听踪",Icons.Outlined.Info,last=true){onRoute("account-about")}
            }
        }
        if(error!=null)item{Text(error,color=MaterialTheme.colorScheme.error,fontSize=13.sp)}
        if(signedIn)item{SwissOutlinedButton({signOut=true},Modifier.fillMaxWidth().height(50.dp).testTag("account-sign-out"),enabled=!busy,shape=androidx.compose.ui.graphics.RectangleShape){Text("退出登录")}}
    }
    if(signOut)AlertDialog(onDismissRequest={if(!busy)signOut=false},shape=androidx.compose.ui.graphics.RectangleShape,title={Text("退出登录？")},text={Column{Text("退出后需重新登录才能查看个人内容。");if(error!=null)Text(error,color=MaterialTheme.colorScheme.error,fontSize=13.sp)}},confirmButton={SwissTextButton({onSignOut()},enabled=!busy){Text(if(busy)"正在退出…" else "退出登录")}},dismissButton={SwissTextButton({signOut=false},enabled=!busy){Text("取消")}})
}

@Composable private fun SettingToggle(title:String,checked:Boolean,onChange:(Boolean)->Unit,tag:String) {
    Row(Modifier.fillMaxWidth().heightIn(min=63.dp),verticalAlignment=Alignment.CenterVertically){Text(title,Modifier.weight(1f),fontSize=15.sp,color=RoomColors.Ink);Switch(checked,onChange,Modifier.testTag(tag),colors=SwitchDefaults.colors(checkedTrackColor=RoomColors.Navy))}
    HorizontalDivider(color=RoomColors.Line.copy(alpha=.65f),thickness=.5.dp)
}

@Composable private fun AccountSection(number:String,title:String,content:@Composable ColumnScope.()->Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        SwissNumberedHeading(number,title)
        HorizontalDivider(color=RoomColors.Line)
        Column(content=content)
    }
}

@Composable private fun AccountParagraph(text:String) {
    Text(text,fontSize=15.sp,lineHeight=25.sp,color=RoomColors.Muted,modifier=Modifier.padding(top=5.dp))
}

@Composable private fun AboutSource(label:String,detail:String,url:String) {
    val context=LocalContext.current
    Row(Modifier.fillMaxWidth().clickable(role=Role.Button) {
        runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))}
            .onFailure{Toast.makeText(context,"暂时无法打开链接",Toast.LENGTH_SHORT).show()}
    }.heightIn(min=56.dp).padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically,
        horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(label,fontSize=15.sp,fontWeight=FontWeight.Medium,color=RoomColors.Ink)
            Text(detail,fontSize=12.sp,lineHeight=18.sp,color=RoomColors.Muted)
        }
        Icon(Icons.Outlined.OpenInNew,null,Modifier.size(18.dp),tint=RoomColors.Muted)
    }
}

@Composable internal fun RoomAccountInfo(kind:String,email:String,signedIn:Boolean,busy:Boolean,error:String?,onDelete:()->Unit,onLogin:()->Unit) {
    var deleting by rememberSaveable{mutableStateOf(false)};var confirmation by rememberSaveable{mutableStateOf("")}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=24.dp,vertical=20.dp),
        verticalArrangement=Arrangement.spacedBy(28.dp)) {
        when(kind) {
            "email"->{
                AccountSection("01","登录邮箱") {
                    Text(if(signedIn)email else "尚未登录",fontSize=20.sp,color=RoomColors.Ink,modifier=Modifier.padding(top=4.dp))
                    AccountParagraph("使用邮箱验证码登录。")
                }
                if(signedIn) AccountSection("02","账号管理") {
                    SwissTextButton({deleting=true},enabled=!busy,modifier=Modifier.testTag("account-delete-entry")){
                        Text("注销账号",color=MaterialTheme.colorScheme.error)
                    }
                } else SwissButton(onLogin,Modifier.fillMaxWidth()){Text("登录")}
            }
            "privacy"->{
                AccountSection("01","现场定位认证") {
                    AccountParagraph("到访认证记录时间、定位精度和距离，不长期保存原始定位。")
                }
                AccountSection("02","公开范围") {
                    AccountParagraph("到访和复刻默认仅自己可见。主动公开后，其他登录用户可以浏览、点赞或举报。")
                }
            }
            "storage"->{
                AccountSection("01","本机草稿") {
                    AccountParagraph("草稿按账号保存在本机。断网不会自动发布，现场提交会重新获取定位。卸载应用会删除本机草稿。")
                }
                AccountSection("02","图片上传") {
                    AccountParagraph("上传前会优化图片并清除位置元数据。")
                }
            }
            "about"->{
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    SwissEyebrow("TINGZONG / ${cn.musicpilgrimage.app.BuildConfig.VERSION_NAME}")
                    Text("听踪",fontSize=36.sp,lineHeight=44.sp,fontWeight=FontWeight.Medium,color=RoomColors.Ink)
                    AccountParagraph("在音乐中发现地点，留下自己的足迹。")
                }
                AccountSection("01","地图与许可") {
                    AboutSource("MapTiler","底图服务与版权说明","https://www.maptiler.com/copyright/")
                    HorizontalDivider(color=RoomColors.Line)
                    AboutSource("OpenStreetMap","地图数据许可说明","https://www.openstreetmap.org/copyright")
                }
                AccountSection("02","地球影像与海洋数据") {
                    AboutSource("NASA Earth Observatory","地球影像 · Blue Marble Next Generation",
                        "https://science.nasa.gov/earth/earth-observatory/blue-marble-next-generation/base-map/")
                    HorizontalDivider(color=RoomColors.Line)
                    AboutSource("NOAA NCEI","海洋水深 · ETOPO 2022","https://www.ncei.noaa.gov/products/etopo-global-relief-model")
                }
            }
        }
    }
    if(deleting)AlertDialog(onDismissRequest={if(!busy)deleting=false},shape=androidx.compose.ui.graphics.RectangleShape,title={Text("注销账号")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){Text("账号注销后无法恢复，个人记录、媒体与互动将删除。留言匿名保留，已采纳投稿解除身份关联。");OutlinedTextField(confirmation,{confirmation=it},enabled=!busy,label={Text("输入“删除账号”")},singleLine=true);if(error!=null)Text(error,color=MaterialTheme.colorScheme.error,fontSize=13.sp)}},confirmButton={SwissTextButton(onDelete,enabled=confirmation=="删除账号"&&!busy){Text(if(busy)"正在注销…" else "确认注销")}},dismissButton={SwissTextButton({deleting=false},enabled=!busy){Text("取消")}})
}

@Composable internal fun RoomDiscardProfileDialog(onCancel:()->Unit,onDiscard:()->Unit) {
    AlertDialog(onDismissRequest=onCancel,shape=androidx.compose.ui.graphics.RectangleShape,title={Text("放弃修改？")},text={Text("尚未保存的头像或昵称修改会丢失。")},confirmButton={SwissTextButton(onDiscard,Modifier.testTag("account-discard-confirm")){Text("放弃修改")}},dismissButton={SwissTextButton(onCancel){Text("继续编辑")}})
}
