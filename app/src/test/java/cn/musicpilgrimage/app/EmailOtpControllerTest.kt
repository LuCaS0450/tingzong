package cn.musicpilgrimage.app

import cn.musicpilgrimage.app.data.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class EmailOtpControllerTest {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
    private var now=0L
    private class Gateway:EmailOtpGateway {
        val sends=mutableListOf<Pair<String,Boolean>>()
        val verifies=mutableListOf<Pair<String,String>>()
        var sending:suspend ()->Unit={}
        var verifying:suspend ()->Unit={}
        override suspend fun send(email:String,register:Boolean){sends+=email to register;sending()}
        override suspend fun verify(email:String,code:String){verifies+=email to code;verifying()}
    }
    private val gateway=Gateway()
    private val controller=EmailOtpController(gateway,scope){now}
    @After fun stop(){scope.cancel()}

    @Test fun validatesLocallyAndUsesSixtySecondEmailCooldown(){
        controller.email("wrong");controller.send()
        assertNotNull(controller.state.value.emailError);assertTrue(gateway.sends.isEmpty())
        controller.email(" Music@Example.com ");controller.send()
        assertEquals("music@example.com",gateway.sends.single().first)
        assertFalse(gateway.sends.single().second)
        assertEquals(60,controller.state.value.remainingSeconds)
        controller.send();assertEquals(1,gateway.sends.size)
        now=59_001;controller.tick();assertEquals(1,controller.state.value.remainingSeconds)
        now=60_000;controller.tick();controller.send();assertEquals(2,gateway.sends.size)
    }
    @Test fun repeatedTapAndOldResponseCannotReplaceNewEmail()=runBlocking {
        val completion=CompletableDeferred<Unit>()
        gateway.sending={withContext(NonCancellable){completion.await()}}
        controller.email("first@example.com");controller.send();controller.send()
        assertEquals(1,gateway.sends.size)
        controller.email("second@example.com")
        completion.complete(Unit);yield()
        assertEquals("second@example.com",controller.state.value.email)
        assertNull(controller.state.value.feedback);assertEquals(0,controller.state.value.remainingSeconds)
    }
    @Test fun helpReturnsToLoginAndRequestsCodeFocusOnlyAfterSuccessfulSend(){
        controller.email("music@example.com");controller.help(true);controller.send()
        assertFalse(controller.state.value.help);assertEquals(1,controller.state.value.focusCode)
        assertEquals("music@example.com",controller.state.value.email)
    }
    @Test fun verificationShowsCodeErrorAndErasesCodeOnSuccess(){
        controller.email("music@example.com");controller.code("1");controller.verify()
        assertNotNull(controller.state.value.codeError);assertTrue(gateway.verifies.isEmpty())
        gateway.verifying={throw EmailOtpFailure(OtpFailureKind.CODE,"验证码已失效")}
        controller.code("654321");controller.verify()
        assertEquals("验证码已失效",controller.state.value.codeError)
        assertFalse(controller.state.value.verified)
        gateway.verifying={};controller.code("123456");controller.verify()
        assertTrue(controller.state.value.verified);assertEquals("",controller.state.value.code)
        assertEquals(2,gateway.verifies.size)
        controller.reset();assertFalse(controller.state.value.verified);assertEquals("",controller.state.value.email)
    }
    @Test fun rateLimitFollowsEmailAndRegistrationClosureRemainsVisible(){
        gateway.sending={throw EmailOtpFailure(OtpFailureKind.RATE_LIMIT,"稍后重试")}
        controller.email("one@example.com");controller.send();assertEquals(60,controller.state.value.remainingSeconds)
        controller.email("two@example.com");assertEquals(0,controller.state.value.remainingSeconds)
        controller.email("one@example.com");assertEquals(60,controller.state.value.remainingSeconds)
        controller.email("new@example.com");controller.registration(true)
        gateway.sending={throw EmailOtpFailure(OtpFailureKind.REGISTRATION_CLOSED,"暂未开放注册")}
        controller.send();assertTrue(gateway.sends.last().second)
        assertEquals("暂未开放注册",controller.state.value.formError);assertNull(controller.state.value.busy)
    }
}
