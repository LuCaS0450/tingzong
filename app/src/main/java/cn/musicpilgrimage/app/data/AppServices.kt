package cn.musicpilgrimage.app.data
import android.content.Context
import android.net.Uri
import android.util.Base64
import cn.musicpilgrimage.app.BuildConfig
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.serialization.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiException(val code:Int,message:String,val reason:String="unknown"):IOException(message)
class AppServices(context:Context) {
 private val app=context.applicationContext
 val secure=SecureSettings(app)
 val drafts=DraftStore(app)
 val photos=PhotoFiles(app)
 val location=DeviceLocation(app)
 init { LegacyChallengeCleanup.run(app) }
 val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
 init {scope.launch {drafts.retireCapsules()}}
 // The SDK updates its current session after metadata saves; serialize this with login/logout.
 private val authMutation=Mutex()
 val changed=MutableStateFlow(0)
 val recovery=MutableStateFlow(false)
 val catalog=MutableStateFlow(catalogFromJson(JSONObject(app.assets.open("catalog.json").bufferedReader().use { it.readText() })))
 val cloudCatalog=MutableStateFlow(false)
 val client=if(BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()) createSupabaseClient(BuildConfig.SUPABASE_URL,BuildConfig.SUPABASE_ANON_KEY) {
  install(Auth) {
   sessionManager=secure;codeVerifierCache=secure;flowType=FlowType.PKCE
   scheme="musicpilgrimage";host="auth";defaultRedirectUrl="musicpilgrimage://auth/callback"
  }
 } else null
 // Android Auth temporarily enters Initializing while the system camera/picker is open.
 // Keep the last confirmed owner then; explicit sign-out and refresh failure still clear it.
 val userId:StateFlow<String?> = client?.auth?.sessionStatus?.scan(null as String?) { previous,status ->
  when(status) {
   is SessionStatus.Authenticated -> status.session.user?.id
   SessionStatus.Initializing -> previous
   is SessionStatus.NotAuthenticated,is SessionStatus.RefreshFailure -> null
  }
 }?.distinctUntilChanged()
  ?.stateIn(scope,SharingStarted.Eagerly,null) ?: MutableStateFlow(null)
 val authReady=MutableStateFlow(client==null)
 init {scope.launch{try{ready();val id=client?.auth?.currentUserOrNull()?.id;withTimeoutOrNull(5000){userId.first{it==id}}}catch(e:CancellationException){throw e}catch(_:Exception){}finally{authReady.value=true}}}
 private val http=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(45,TimeUnit.SECONDS).build()
 val endpoint=BuildConfig.SUPABASE_URL.trimEnd('/')+"/functions/v1/pilgrimage-v2"
 val repositories=Repositories(this)
 internal val library by lazy{PersonalLibrarySync(app,this)}
 suspend fun ready() { client?.auth?.awaitInitialization() }
 fun token():String? = client?.auth?.currentAccessTokenOrNull()
 suspend fun call(action:String,payload:JSONObject=JSONObject(),expectedOwner:String?=userId.value):Any = withContext(Dispatchers.IO) {
  check(client!=null) { "尚未配置在线服务，仍可浏览官方目录" }
  client.auth.awaitInitialization()
  require(userId.value==expectedOwner) { "账号已切换，请重新打开页面" }
  val session=client.auth.currentSessionOrNull()
  val requestToken=AuthRequestScope(session?.user?.id,session?.accessToken).authorization(expectedOwner,userId.value)
  val libraryRpc=when(action){"library_state"->"personal_library_state";"library_sync"->"personal_library_sync";else->null}
  if(libraryRpc!=null)require(expectedOwner!=null&&requestToken!=null){"请先登录后同步"}
  val requestBody=if(libraryRpc=="personal_library_state")JSONObject()
   else if(libraryRpc=="personal_library_sync")JSONObject().put("p_kind",payload.getString("kind")).put("p_expected_revision",payload.getLong("expected_revision")).put("p_data",payload.getJSONObject("data")).put("p_client_id",payload.getString("client_id"))
   else JSONObject().put("action",action).put("payload",payload)
  val requestUrl=libraryRpc?.let{BuildConfig.SUPABASE_URL.trimEnd('/')+"/rest/v1/rpc/"+it}?:endpoint
  val request=Request.Builder().url(requestUrl).header("apikey",BuildConfig.SUPABASE_ANON_KEY)
   .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
  requestToken?.let { request.header("Authorization","Bearer "+it) }
  val call=http.newCall(request.build())
  val response=suspendCancellableCoroutine<Response> { continuation ->
   continuation.invokeOnCancellation { call.cancel() }
   call.enqueue(object:Callback {
    override fun onFailure(call:Call,e:IOException) { if(continuation.isActive)continuation.resumeWithException(e) }
    override fun onResponse(call:Call,response:Response) { continuation.resume(response) { _,value,_->value.close() } }
   })
  }
  response.use {
    val body=runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrElse { throw ApiException(response.code,"在线服务暂不可用") }
    require(userId.value==expectedOwner) { "账号已切换，请重新打开页面" }
    if(!response.isSuccessful) {
     if(libraryRpc!=null)throw ApiException(if(body.optString("code")=="PGRST202")503 else response.code,
      if(body.optString("code")=="P0001")body.optString("message","同步尚未完成，本机更改已保留。")else "同步服务暂不可用，本机更改已保留。",body.optString("code","unknown"))
     throw ApiException(response.code,body.optString("error",if(response.code==404) "V2 服务尚未上线，官方目录仍可浏览" else "请求失败，请重试"),body.optString("code","unknown"))
    }
    if(libraryRpc!=null)body else body.get("data")
  }
 }
 suspend fun mutate(action:String,payload:JSONObject=JSONObject(),expectedOwner:String?=userId.value):Any {
  val result=call(action,payload,expectedOwner);changed.update { it+1 };return result
 }
 suspend fun refreshCatalog() {
  catalog.value=catalogFromJson(call("catalog") as JSONObject);cloudCatalog.value=true
 }
 suspend fun signIn(email:String,password:String) {
  authMutation.withLock {
  require(email.contains('@')) { "请输入正确的邮箱" };require(password.length>=10) { "密码至少10位" }
  requireNotNull(client) { "在线服务尚未配置" }.auth.signInWith(Email) { this.email=email.trim();this.password=password };changed.update { it+1 }
  }
 }
 suspend fun sendEmailCode(email:String,register:Boolean) {
  authMutation.withLock {
  require(EmailOtpController.validEmail(email)){"请输入完整邮箱"}
  val auth=requireNotNull(client){"在线服务尚未配置"}.auth
  if(register) {
   val cfg=call("config") as JSONObject
   if(!cfg.optBoolean("registration_enabled"))throw EmailOtpFailure(OtpFailureKind.REGISTRATION_CLOSED,"暂未开放注册，已有账号可以返回登录")
  }
  try {auth.signInWith(OTP,redirectUrl="musicpilgrimage://auth/callback") {this.email=EmailOtpController.normalized(email);createUser=register}}
  catch(e:AuthRestException){throw emailOtpError(e,false)}
  }
 }
 suspend fun verifyEmailCode(email:String,code:String) {
  authMutation.withLock {
  require(EmailOtpController.validEmail(email)&&EmailOtpController.validCode(code)){"请输入邮箱和完整验证码"}
  try {requireNotNull(client).auth.verifyEmailOtp(type=OtpType.Email.EMAIL,email=EmailOtpController.normalized(email),token=code)}
  catch(e:AuthRestException){throw emailOtpError(e,true)}
  changed.update{it+1}
  withTimeout(5000){userId.first{it!=null}}
  }
 }
 private fun emailOtpError(e:AuthRestException,verifying:Boolean):EmailOtpFailure=when(e.error) {
  "otp_expired","invalid_credentials"->EmailOtpFailure(if(verifying)OtpFailureKind.CODE else OtpFailureKind.SEND,if(verifying)"验证码不正确或已失效，请重新获取" else "暂时无法发送，请确认邮箱后重试")
  "over_request_rate_limit","over_email_send_rate_limit","over_sms_send_rate_limit"->EmailOtpFailure(OtpFailureKind.RATE_LIMIT,"获取过于频繁，请稍后重试")
  "signup_disabled"->EmailOtpFailure(OtpFailureKind.REGISTRATION_CLOSED,"暂未开放注册，已有账号可以返回登录")
  "email_address_not_authorized"->EmailOtpFailure(OtpFailureKind.SEND,"此邮箱暂时无法接收验证码，请稍后重试")
  else->EmailOtpFailure(OtpFailureKind.SEND,if(verifying)"暂时无法验证，请稍后重试" else "暂时无法发送验证码，请稍后重试")
 }
 fun accountEmail(owner:String?=userId.value)=client?.auth?.currentUserOrNull()?.takeIf{it.id==owner}?.email.orEmpty()
 fun accountAvatar(owner:String?=userId.value)=(client?.auth?.currentUserOrNull()?.takeIf{it.id==owner}?.userMetadata?.get("music_space_avatar") as? JsonPrimitive)?.contentOrNull
 suspend fun updateAccountAvatar(value:String?,owner:String) {
  authMutation.withLock {
  require(userId.value==owner&&client?.auth?.currentUserOrNull()?.id==owner){"账号已切换，请重新打开页面"}
  require(value==null || value.startsWith("data:image/jpeg;base64,")&&value.length<=32_000){"头像无法保存，请重新选择"}
  requireNotNull(client).auth.updateUser {data=buildJsonObject{put("music_space_avatar",value?.let(::JsonPrimitive)?:JsonNull)}}
  require(userId.value==owner&&client.auth.currentUserOrNull()?.id==owner){"账号已切换，请重新打开页面"}
  }
 }
 suspend fun signUp(email:String,password:String) {
  authMutation.withLock {
  val cfg=call("config") as JSONObject
  require(cfg.optBoolean("registration_enabled")) { "新用户注册暂时关闭" }
  require(email.contains('@') && password.length in 10..128) { "请输入正确邮箱，密码需10–128位" }
  client!!.auth.signUpWith(Email,redirectUrl="musicpilgrimage://auth/callback") { this.email=email.trim();this.password=password }
  }
 }
 suspend fun resend(email:String) { requireNotNull(client).auth.resendEmail(OtpType.Email.SIGNUP,email.trim()) }
 suspend fun reset(email:String) { require(email.contains('@')) { "请输入邮箱" };requireNotNull(client).auth.resetPasswordForEmail(email.trim(),redirectUrl="musicpilgrimage://auth/callback?mode=recovery") }
 suspend fun newPassword(password:String) {
  authMutation.withLock {
  require(password.length in 10..128) { "密码需10–128位" }
  requireNotNull(client).auth.updateUser { this.password=password };recovery.value=false
  }
 }
 suspend fun callback(uri:Uri) {
  authMutation.withLock {
  require(uri.scheme=="musicpilgrimage" && uri.host=="auth" && uri.path=="/callback") { "无效认证链接" }
  uri.getQueryParameter("error_description")?.let { throw IllegalArgumentException("验证链接已失效，请重新发送") }
  val code=uri.getQueryParameter("code") ?: error("验证链接不完整，请重新发送")
  requireNotNull(client).auth.exchangeCodeForSession(code)
  recovery.value=uri.getQueryParameter("mode")=="recovery";changed.update { it+1 }
  }
 }
 suspend fun signOut() {
  authMutation.withLock {
  try { withTimeoutOrNull(5000) {client?.auth?.signOut()} }
  catch(cancelled:CancellationException) {throw cancelled}
  catch(error:Exception) { /* The local session must still close when revocation is offline. */ }
  finally { withContext(NonCancellable) {
   client?.auth?.clearSession()
   recovery.value=false
   coil.Coil.imageLoader(app).memoryCache?.clear()
   changed.update { it+1 }
  } }
  }
 }
 suspend fun upload(path:String,requestId:String,expectedOwner:String?=userId.value):String {
  val file=File(path).canonicalFile
  require(file.parentFile==File(app.filesDir,"photos").canonicalFile && file.length() in 4..409600) { "草稿照片不存在或过大，请重新选择" }
  val data=withContext(Dispatchers.IO) { Base64.encodeToString(file.readBytes(),Base64.NO_WRAP) }
  return (call("upload",JSONObject().put("request_id",requestId).put("base64",data),expectedOwner) as JSONObject).getString("id")
 }
 fun mediaUrl(id:String)=endpoint+"?media="+Uri.encode(id)
}
