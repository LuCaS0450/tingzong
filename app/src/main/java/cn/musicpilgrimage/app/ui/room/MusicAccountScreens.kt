package cn.musicpilgrimage.app.ui.room

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.musicpilgrimage.app.MusicProfileDraft
import cn.musicpilgrimage.app.MusicAccountViewModel
import cn.musicpilgrimage.app.data.*
import cn.musicpilgrimage.app.design.AppPreferencesState
import cn.musicpilgrimage.app.performance.PerformanceResult
import cn.musicpilgrimage.app.performance.SongPackage
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
    BackHandler(state.help||onBack!=null){if(state.help)onHelp(false)else if(!busy)onBack?.invoke()}
    LaunchedEffect(state.focusCode,state.help){if(state.focusCode>0&&!state.help){withFrameNanos{};runCatching{codeFocus.requestFocus()}}}
    LaunchedEffect(state.emailError,state.codeError){withFrameNanos{};if(state.emailError!=null)runCatching{emailFocus.requestFocus()}else if(state.codeError!=null&&!state.help)runCatching{codeFocus.requestFocus()}}
    Column(Modifier.fillMaxSize().testTag("room-email-login")) {
        if(onBack!=null||state.help)Row(Modifier.fillMaxWidth().height(54.dp).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){IconButton({if(state.help)onHelp(false)else onBack?.invoke()},enabled=!busy){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"返回登录")}}
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal=25.dp),verticalArrangement=Arrangement.spacedBy(0.dp)) {
            Spacer(Modifier.height(if(onBack==null&&!state.help)30.dp else 8.dp))
            if(!state.help){SwissEyebrow("WELCOME / 听踪");Spacer(Modifier.height(14.dp))}
            Text(if(state.help)"收不到验证码？" else if(state.register)"注册" else "把你的旅途\n带在身边。",fontSize=30.sp,lineHeight=39.sp,fontWeight=FontWeight.SemiBold,color=RoomColors.Ink)
            Spacer(Modifier.height(8.dp))
            Text(if(state.help)"检查邮箱地址、垃圾邮件与收件箱空间。" else if(state.register)"用邮箱创建你的音乐空间" else "登录你的音乐空间",fontSize=14.sp,color=RoomColors.Muted,lineHeight=22.sp)
            Spacer(Modifier.height(24.dp))
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
                    if(state.busy==OtpOperation.VERIFY||state.verified)AccountWaiting("登录中",motion)else Text(if(state.register)"注册并进入" else "登录")
                }
            } else {
                Text("验证码可能需要一点时间送达。确认邮箱无误后，可以重新获取；不要向他人提供验证码。",fontSize=14.sp,lineHeight=23.sp,color=RoomColors.Muted)
                Spacer(Modifier.height(18.dp))
                SwissButton(onSend,Modifier.fillMaxWidth().height(52.dp).testTag("account-help-resend"),enabled=!busy&&state.remainingSeconds==0,shape=androidx.compose.ui.graphics.RectangleShape) {
                    if(state.busy==OtpOperation.SEND)AccountWaiting("发送中",motion)else Text(if(state.remainingSeconds>0)"${state.remainingSeconds}秒后重发" else "重新获取验证码")
                }
            }
            Box(Modifier.fillMaxWidth().height(48.dp).padding(top=8.dp)) {Text(state.formError?:state.feedback.orEmpty(),fontSize=12.sp,lineHeight=18.sp,maxLines=2,color=if(state.formError!=null)MaterialTheme.colorScheme.error else RoomColors.Muted,modifier=Modifier.testTag("account-feedback"))}
            if(!state.help)Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                SwissTextButton({onRegister(!state.register)},enabled=!busy,modifier=Modifier.testTag("account-registration")){Text(if(state.register)"已有账号，登录" else "注册账号",fontSize=13.sp)}
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

@Composable internal fun RoomMyAccount(
    profile:JSONObject?,signedIn:Boolean,email:String,avatar:String?,favorites:Int?,visits:Int?,performances:Int,
    onLogin:()->Unit,onEdit:()->Unit,onRoute:(String)->Unit,error:String?=null,onRetry:()->Unit={}
) {
    LazyColumn(Modifier.fillMaxSize().testTag("room-my-account"),state=rememberLazyListState(),contentPadding=PaddingValues(24.dp,10.dp,24.dp,30.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        item {RoomPaper(Modifier.fillMaxWidth()) {Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            RoomAccountAvatar(avatar,Modifier.size(54.dp))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                Text(if(signedIn)profile?.optString("nickname")?.takeIf{it.isNotBlank()}?:"音乐旅人" else "我的音乐空间",fontSize=18.sp,fontWeight=FontWeight.Medium,color=RoomColors.Ink,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.testTag("account-nickname"))
                Text(if(signedIn)email else "收藏、足迹与演奏记录",fontSize=12.sp,color=RoomColors.Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
            if(signedIn)IconButton(onEdit,Modifier.testTag("account-edit-profile")){Icon(Icons.Outlined.Edit,"修改头像和昵称",tint=RoomColors.Navy)}else SwissTextButton(onLogin,Modifier.testTag("account-login-entry")){Text("登录")}
        }}}
        if(error!=null)item{Column {Text(error,color=MaterialTheme.colorScheme.error,fontSize=13.sp);SwissTextButton(onRetry){Text("重新加载资料")}}}
        item {Text("我的音乐足迹",fontSize=19.sp,fontWeight=FontWeight.Medium,color=RoomColors.Ink)
            Row(Modifier.fillMaxWidth().padding(top=18.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf(Triple("收藏",favorites,"library"),Triple("到访",visits,"archive/footprints"),Triple("演奏",performances,"account-scores")).forEach{(label,count,route)->
                    Column(Modifier.weight(1f).clip(androidx.compose.ui.graphics.RectangleShape).clickable{onRoute(route)}.padding(vertical=12.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(count?.toString()?:"—",fontSize=25.sp,color=RoomColors.Ink,fontWeight=FontWeight.Medium)
                        Text(label,fontSize=13.sp,color=RoomColors.Muted)
                    }
                }
            }
        }
        item {RoomPaper(Modifier.fillMaxWidth(),woodEdge=true){Column(Modifier.padding(horizontal=14.dp)) {
            AccountLink("演奏记录",Icons.Outlined.Piano){onRoute("account-scores")}
            AccountLink("我的发布与投稿",Icons.Outlined.EditNote){if(signedIn)onRoute("account-publications")else onLogin()}
            AccountLink("公开主页",Icons.Outlined.PersonOutline){if(signedIn)onRoute("profile/${profile?.optString("id").orEmpty()}")else onLogin()}
            AccountLink("徽章",Icons.Outlined.Verified){if(signedIn)onRoute("account-badges")else onLogin()}
            AccountLink("设置",Icons.Outlined.Settings,last=true){onRoute("settings")}
        }}}
        if(profile?.optString("status")=="disabled")item{Text("账号已停用，仍可查看和管理已有个人内容。",color=MaterialTheme.colorScheme.error,fontSize=13.sp)}
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
        RoomPaper(Modifier.fillMaxWidth()){Column(Modifier.fillMaxWidth().padding(20.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)) {
            RoomAccountAvatar(draft.avatar,Modifier.size(80.dp))
            SwissTextButton({picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))},enabled=draft.active&&!draft.saving&&!draft.processingPhoto){Text(if(draft.processingPhoto)"正在整理头像…" else "选择头像")}
            if(draft.avatar!=null)SwissTextButton(onRemovePhoto,enabled=!draft.saving&&!draft.processingPhoto){Text("移除头像",fontSize=12.sp)}
        }}
        OutlinedTextField(draft.nickname,onNickname,Modifier.fillMaxWidth().testTag("profile-nickname"),label={Text("昵称")},singleLine=true,enabled=draft.active&&!draft.saving,shape=androidx.compose.ui.graphics.RectangleShape,supportingText={Text("2—32个字",fontSize=12.sp)},colors=accountFieldColors())
        Text(draft.error.orEmpty(),color=MaterialTheme.colorScheme.error,fontSize=13.sp,lineHeight=20.sp)
        if(!draft.active&&!draft.loading)SwissTextButton(onRetry){Text("重新加载资料")}
        if(!draft.active&&!draft.loading&&draft.error==null)Text("当前账号无法修改资料。",fontSize=13.sp,color=RoomColors.Muted)
        SwissButton(onSave,Modifier.fillMaxWidth().height(52.dp).testTag("profile-save"),enabled=draft.active&&!draft.saving&&!draft.processingPhoto&&(draft.dirty||draft.error!=null),shape=androidx.compose.ui.graphics.RectangleShape) {if(draft.saving)AccountWaiting("保存中",LocalPhysicalMotion.current)else Text("保存")}
    }
}

@Composable internal fun RoomSettings(pref:AppPreferencesState,signedIn:Boolean,busy:Boolean,error:String?,onMuted:(Boolean)->Unit,onMotion:(Boolean)->Unit,onRoute:(String)->Unit,onSignOut:()->Unit) {
    var signOut by rememberSaveable{mutableStateOf(false)}
    LazyColumn(Modifier.fillMaxSize().testTag("room-settings"),state=rememberLazyListState(),contentPadding=PaddingValues(24.dp,10.dp,24.dp,30.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        item {RoomPaper(Modifier.fillMaxWidth()){Column(Modifier.padding(horizontal=15.dp)) {
            SettingToggle("静音",pref.muted,onMuted,"account-muted")
            SettingToggle("减少动态",pref.reducedMotion,onMotion,"account-reduced-motion")
            AccountLink("邮箱账号",Icons.Outlined.MailOutline){onRoute("account-email")}
            AccountLink("位置与公开范围",Icons.Outlined.LocationOn){onRoute("account-privacy")}
            AccountLink("本机草稿与图片",Icons.Outlined.PhotoLibrary){onRoute("account-storage")}
            AccountLink("关于听踪",Icons.Outlined.Info,last=true){onRoute("account-about")}
        }}}
        if(error!=null)item{Text(error,color=MaterialTheme.colorScheme.error,fontSize=13.sp)}
        if(signedIn)item{SwissOutlinedButton({signOut=true},Modifier.fillMaxWidth().height(50.dp).testTag("account-sign-out"),enabled=!busy,shape=androidx.compose.ui.graphics.RectangleShape){Text("退出登录")}}
    }
    if(signOut)AlertDialog(onDismissRequest={if(!busy)signOut=false},shape=androidx.compose.ui.graphics.RectangleShape,title={Text("退出登录？")},text={Column{Text("退出后需重新登录才能查看个人内容。");if(error!=null)Text(error,color=MaterialTheme.colorScheme.error,fontSize=13.sp)}},confirmButton={SwissTextButton({onSignOut()},enabled=!busy){Text(if(busy)"正在退出…" else "退出登录")}},dismissButton={SwissTextButton({signOut=false},enabled=!busy){Text("取消")}})
}

@Composable private fun SettingToggle(title:String,checked:Boolean,onChange:(Boolean)->Unit,tag:String) {
    Row(Modifier.fillMaxWidth().heightIn(min=63.dp),verticalAlignment=Alignment.CenterVertically){Text(title,Modifier.weight(1f),fontSize=15.sp,color=RoomColors.Ink);Switch(checked,onChange,Modifier.testTag(tag),colors=SwitchDefaults.colors(checkedTrackColor=RoomColors.Navy))}
    HorizontalDivider(color=RoomColors.Line.copy(alpha=.65f),thickness=.5.dp)
}

@Composable internal fun RoomAccountInfo(kind:String,email:String,signedIn:Boolean,busy:Boolean,error:String?,onDelete:()->Unit,onLogin:()->Unit) {
    var deleting by rememberSaveable{mutableStateOf(false)};var confirmation by rememberSaveable{mutableStateOf("")}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        RoomPaper(Modifier.fillMaxWidth()){Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            when(kind) {
                "email"->{Text("邮箱",fontSize=13.sp,color=RoomColors.Muted);Text(if(signedIn)email else "尚未登录",fontSize=17.sp,color=RoomColors.Ink);HorizontalDivider(color=RoomColors.Line);Text("登录方式：邮箱验证码",fontSize=14.sp,color=RoomColors.Ink)}
                "privacy"->{Text("现场定位认证",fontSize=18.sp,color=RoomColors.Ink);Text("到访认证记录时间、定位精度和距离，不长期保存原始定位。",fontSize=14.sp,lineHeight=24.sp);Text("公开范围",fontSize=18.sp,color=RoomColors.Ink);Text("到访和复刻默认仅自己可见。主动公开后，其他登录用户可以浏览、点赞或举报。",fontSize=14.sp,lineHeight=24.sp)}
                "storage"->{Text("本机草稿与图片",fontSize=18.sp,color=RoomColors.Ink);Text("草稿按账号保存在本机。断网不会自动发布，现场提交会重新获取定位。",fontSize=14.sp,lineHeight=24.sp);Text("上传前会优化图片并清除位置元数据。卸载应用会删除本机草稿。",fontSize=14.sp,lineHeight=24.sp)}
                else->{Text("听踪",fontSize=23.sp,fontWeight=FontWeight.Medium,color=RoomColors.Ink);Text("在音乐中发现地点，留下自己的足迹。",fontSize=14.sp,lineHeight=24.sp,color=RoomColors.Muted);Text(cn.musicpilgrimage.app.BuildConfig.VERSION_NAME,fontSize=12.sp,color=RoomColors.Muted)}
            }
        }}
        if(kind=="email") {if(signedIn)SwissTextButton({deleting=true},enabled=!busy,modifier=Modifier.testTag("account-delete-entry")){Text("注销账号",color=MaterialTheme.colorScheme.error)}else SwissButton(onLogin,shape=androidx.compose.ui.graphics.RectangleShape){Text("登录")}}
        if(kind=="about")RoomPaper(Modifier.fillMaxWidth()){Column(Modifier.padding(18.dp)) {
            Text("地图数据来源",fontSize=15.sp,color=RoomColors.Ink)
            cn.musicpilgrimage.app.ui.ExternalLink("底图服务与版权说明","https://www.maptiler.com/copyright/")
            cn.musicpilgrimage.app.ui.ExternalLink("地图数据许可说明","https://www.openstreetmap.org/copyright")
            Spacer(Modifier.height(12.dp))
            Text("地球影像：NASA Earth Observatory · Blue Marble Next Generation",fontSize=12.sp,color=RoomColors.Muted)
            cn.musicpilgrimage.app.ui.ExternalLink("地球影像来源与说明","https://science.nasa.gov/earth/earth-observatory/blue-marble-next-generation/base-map/")
        }}
    }
    if(deleting)AlertDialog(onDismissRequest={if(!busy)deleting=false},shape=androidx.compose.ui.graphics.RectangleShape,title={Text("注销账号")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){Text("账号注销后无法恢复，个人记录、媒体与互动将删除。留言匿名保留，已采纳投稿解除身份关联。");OutlinedTextField(confirmation,{confirmation=it},enabled=!busy,label={Text("输入“删除账号”")},singleLine=true);if(error!=null)Text(error,color=MaterialTheme.colorScheme.error,fontSize=13.sp)}},confirmButton={SwissTextButton(onDelete,enabled=confirmation=="删除账号"&&!busy){Text(if(busy)"正在注销…" else "确认注销")}},dismissButton={SwissTextButton({deleting=false},enabled=!busy){Text("取消")}})
}

@Composable internal fun RoomAccountRecords(profile:JSONObject?,onRoute:(String)->Unit) {
    RoomPaper(Modifier.fillMaxWidth().padding(24.dp)){Column(Modifier.padding(horizontal=15.dp)) {
        AccountLink("我的到访",Icons.Outlined.LocationOn){onRoute("archive/footprints")}
        AccountLink("我的复刻",Icons.Outlined.PhotoCamera){onRoute("mine/recreation")}
        AccountLink("地点留言",Icons.Outlined.ChatBubbleOutline){onRoute("mine/message")}
        AccountLink("我的投稿",Icons.Outlined.EditLocationAlt,last=profile?.optString("role")!="admin"){onRoute("submissions")}
        if(profile?.optString("role")=="admin")AccountLink("管理中心",Icons.Outlined.AdminPanelSettings,last=true){onRoute("admin")}
    }}
}

@Composable internal fun RoomDiscardProfileDialog(onCancel:()->Unit,onDiscard:()->Unit) {
    AlertDialog(onDismissRequest=onCancel,shape=androidx.compose.ui.graphics.RectangleShape,title={Text("放弃修改？")},text={Text("尚未保存的头像或昵称修改会丢失。")},confirmButton={SwissTextButton(onDiscard,Modifier.testTag("account-discard-confirm")){Text("放弃修改")}},dismissButton={SwissTextButton(onCancel){Text("继续编辑")}})
}

@Composable internal fun RoomAccountBadges(profile:JSONObject?) {
    val badges=profile?.optJSONArray("badges")?.objects()?.filter{it.optString("id")!="first_capsule"}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(badges==null||badges.isEmpty())item{Text("这里会收好你获得的徽章。",fontSize=14.sp,color=RoomColors.Muted)}
        else items(badges.size){index->val badge=badges[index];val earned=badge.optString("earned_at").let{it.isNotBlank()&&it!="null"}
            RoomPaper(Modifier.fillMaxWidth()){Row(Modifier.padding(16.dp),horizontalArrangement=Arrangement.spacedBy(14.dp)){Icon(if(earned)Icons.Outlined.Verified else Icons.Outlined.RadioButtonUnchecked,if(earned)"已获得" else "未获得",tint=RoomColors.Navy);Column(verticalArrangement=Arrangement.spacedBy(6.dp)){Text(badge.optString("name"),fontSize=16.sp,color=RoomColors.Ink);Text(badge.optString("description"),fontSize=13.sp,color=RoomColors.Muted)}}}
        }
    }
}

@Composable internal fun RoomPerformanceRecords(results:List<PerformanceResult>,songs:List<SongPackage>,onSong:(String)->Unit) {
    val best=remember(results){results.groupBy{it.songId to it.difficulty}.values.map{it.maxBy{row->row.score}}.sortedByDescending{it.finishedAt}}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(best.isEmpty())item{Text("还没有演奏成绩，去钢琴区域选一首喜欢的曲目。",fontSize=14.sp,lineHeight=23.sp,color=RoomColors.Muted)}
        items(best,key={"${it.songId}:${it.difficulty}"}) {result->RoomPaper(Modifier.fillMaxWidth().clickable{onSong(result.songId)}){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(songs.find{it.id==result.songId}?.title?:result.songId,fontSize=17.sp,color=RoomColors.Ink);Text("${when(result.difficulty){1->"轻松";2->"标准";else->"难度 ${result.difficulty}"}} · 最高分 ${result.score}",fontSize=13.sp,color=RoomColors.Muted);Text("最高连击 ${result.bestCombo}",fontSize=12.sp,color=RoomColors.Muted)}}}
    }
}
