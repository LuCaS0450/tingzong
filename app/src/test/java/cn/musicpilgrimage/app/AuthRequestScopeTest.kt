package cn.musicpilgrimage.app

import cn.musicpilgrimage.app.data.AuthRequestScope
import org.junit.Assert.*
import org.junit.Test

class AuthRequestScopeTest {
    @Test fun accountSwitchCannotBorrowTheNewAccountsToken(){
        val captured=AuthRequestScope("account-a","token-a")
        assertEquals("token-a",captured.authorization("account-a","account-a"))
        val switched=AuthRequestScope("account-b","token-b")
        assertTrue(runCatching{switched.authorization("account-a","account-a")}.isFailure)
        assertTrue(runCatching{captured.authorization("account-a","account-b")}.isFailure)
        assertEquals("token-b",switched.authorization("account-b","account-b"))
    }
    @Test fun anonymousRequestsNeverUseAnUnattributedToken(){
        assertNull(AuthRequestScope(null,"unattributed-token").authorization(null,null))
        assertTrue(runCatching{AuthRequestScope("account-a",null).authorization("account-a","account-a")}.isFailure)
    }
}
