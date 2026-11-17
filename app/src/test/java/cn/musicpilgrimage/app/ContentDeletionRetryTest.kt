package cn.musicpilgrimage.app

import cn.musicpilgrimage.app.data.ApiException
import cn.musicpilgrimage.app.data.retryUnsentContentDeletion
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ContentDeletionRetryTest {
    @Test
    fun unsentConnectionFailureCanRecoverOnTheSecondAttempt() = runBlocking {
        var attempts = 0
        val result = retryUnsentContentDeletion {
            attempts++
            if (attempts == 1) throw ConnectException()
            "saved"
        }
        assertEquals("saved", result)
        assertEquals(2, attempts)
    }

    @Test
    fun repeatedConnectionFailureStopsAfterTwoAttempts() {
        var attempts = 0
        assertThrows(ConnectException::class.java) {
            runBlocking {
                retryUnsentContentDeletion {
                    attempts++
                    throw ConnectException()
                }
            }
        }
        assertEquals(2, attempts)
    }

    @Test
    fun missingResponseAfterTransmissionDoesNotRepeatTheDeletion() {
        val call = OkHttpClient().newCall(Request.Builder().url("https://example.invalid/").build())
        var attempts = 0
        assertThrows(SocketTimeoutException::class.java) {
            runBlocking {
                retryUnsentContentDeletion { attempt ->
                    attempts++
                    attempt.requestHeadersStart(call)
                    throw SocketTimeoutException()
                }
            }
        }
        assertEquals(1, attempts)
    }

    @Test
    fun rejectedSessionDoesNotRepeatTheDeletion() {
        var attempts = 0
        assertThrows(ApiException::class.java) {
            runBlocking {
                retryUnsentContentDeletion {
                    attempts++
                    throw ApiException(401, "Expired session")
                }
            }
        }
        assertEquals(1, attempts)
    }
}
