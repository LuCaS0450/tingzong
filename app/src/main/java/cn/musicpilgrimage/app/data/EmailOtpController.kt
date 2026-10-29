package cn.musicpilgrimage.app.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

internal interface EmailOtpGateway {
    suspend fun send(email:String,register:Boolean)
    suspend fun verify(email:String,code:String)
}
internal enum class OtpFailureKind { CODE, RATE_LIMIT, SEND, REGISTRATION_CLOSED }
internal class EmailOtpFailure(val kind:OtpFailureKind,message:String):Exception(message)
internal enum class OtpOperation { SEND, VERIFY }
internal data class EmailOtpState(
    val email:String="",val code:String="",val register:Boolean=false,val help:Boolean=false,
    val busy:OtpOperation?=null,val remainingSeconds:Int=0,val emailError:String?=null,
    val codeError:String?=null,val formError:String?=null,val feedback:String?=null,
    val focusCode:Int=0,val verified:Boolean=false
)

/** Codes stay in memory. Cooldowns follow the email; stale responses cannot change a new form. */
internal class EmailOtpController(
    private val gateway:EmailOtpGateway,private val scope:CoroutineScope,
    private val now:()->Long={System.nanoTime()/1_000_000}
) {
    private val mutable=MutableStateFlow(EmailOtpState())
    val state=mutable.asStateFlow()
    private val cooldowns=mutableMapOf<String,Long>()
    private var operation:Job?=null
    private var revision=0L
    init {scope.launch{while(isActive){delay(1000);tick()}}}
    companion object {
        fun normalized(email:String)=email.trim().lowercase(Locale.ROOT)
        fun validEmail(email:String)=email.length<=254&&Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(email.trim())
        fun validCode(code:String)=code.length in 6..10&&code.all{it in '0'..'9'}
    }
    private fun seconds(email:String)=(((cooldowns[normalized(email)]?:0L)-now()+999)/1000).coerceIn(0,60).toInt()
    internal fun tick(){mutable.value=mutable.value.copy(remainingSeconds=seconds(mutable.value.email))}
    fun email(value:String) {
        if(value==mutable.value.email)return
        revision++;operation?.cancel()
        mutable.value=mutable.value.copy(email=value.take(254),code="",busy=null,emailError=null,codeError=null,formError=null,feedback=null,remainingSeconds=seconds(value),verified=false)
    }
    fun code(value:String){mutable.value=mutable.value.copy(code=value.filter{it in '0'..'9'}.take(10),codeError=null,formError=null)}
    fun reset(){revision++;operation?.cancel();mutable.value=EmailOtpState()}
    fun registration(value:Boolean){if(mutable.value.busy!=null)return;mutable.value=mutable.value.copy(register=value,code="",codeError=null,formError=null,feedback=null)}
    fun help(value:Boolean){if(mutable.value.busy==null)mutable.value=mutable.value.copy(help=value,formError=null,feedback=null)}
    fun send() {
        val previous=mutable.value
        if(previous.busy!=null||previous.verified)return
        if(!validEmail(previous.email)){mutable.value=previous.copy(emailError="请输入完整的邮箱地址",formError=null);return}
        tick();if(mutable.value.remainingSeconds>0)return
        val email=normalized(previous.email);val request=++revision
        mutable.value=previous.copy(busy=OtpOperation.SEND,emailError=null,codeError=null,formError=null,feedback=null)
        operation=scope.launch {
            try {
                gateway.send(email,previous.register)
                if(request!=revision)return@launch
                cooldowns[email]=now()+60_000
                mutable.value=mutable.value.copy(email=email,code="",busy=null,help=false,remainingSeconds=60,feedback="验证码已发送，请查收邮件",focusCode=mutable.value.focusCode+1)
            }catch(e:CancellationException){throw e}
            catch(e:Exception){if(request==revision){
                if(e is EmailOtpFailure&&e.kind==OtpFailureKind.RATE_LIMIT)cooldowns[email]=now()+60_000
                mutable.value=mutable.value.copy(busy=null,formError=if(e is EmailOtpFailure)e.message else "暂时无法发送，请检查网络后重试",remainingSeconds=seconds(email))
            }}
        }
    }
    fun verify() {
        val previous=mutable.value
        if(previous.busy!=null||previous.verified)return
        val emailError=if(validEmail(previous.email))null else "请输入完整的邮箱地址"
        val codeError=if(validCode(previous.code))null else "请输入邮件中的完整验证码"
        if(emailError!=null||codeError!=null){mutable.value=previous.copy(emailError=emailError,codeError=codeError,formError=null);return}
        val request=++revision
        mutable.value=previous.copy(busy=OtpOperation.VERIFY,emailError=null,codeError=null,formError=null,feedback=null)
        operation=scope.launch {
            try {
                gateway.verify(normalized(previous.email),previous.code)
                if(request==revision)mutable.value=mutable.value.copy(code="",verified=true)
            }catch(e:CancellationException){throw e}
            catch(e:Exception){if(request==revision){
                val codeFailure=e is EmailOtpFailure&&e.kind==OtpFailureKind.CODE
                mutable.value=mutable.value.copy(busy=null,codeError=if(codeFailure)e.message else null,formError=if(codeFailure)null else if(e is EmailOtpFailure)e.message else "验证未完成，请检查网络后重试")
            }}
        }
    }
}
