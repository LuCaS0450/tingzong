package cn.musicpilgrimage.app.data

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.delay
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.RequestBody
import okio.BufferedSink

internal class ContentDeletionAttempt : EventListener() {
    @Volatile
    private var requestStarted = false

    override fun requestHeadersStart(call: Call) {
        requestStarted = true
    }

    fun canRetry(failure: IOException): Boolean =
        !requestStarted && (failure is ConnectException || failure is NoRouteToHostException ||
            failure is UnknownHostException || failure is SocketTimeoutException)
}

internal fun RequestBody.asOneShotDeletion(): RequestBody = object : RequestBody() {
    override fun contentType() = this@asOneShotDeletion.contentType()
    override fun contentLength() = this@asOneShotDeletion.contentLength()
    override fun writeTo(sink: BufferedSink) = this@asOneShotDeletion.writeTo(sink)
    override fun isOneShot() = true
}

internal suspend fun <T> retryUnsentContentDeletion(
    operation: suspend (ContentDeletionAttempt) -> T,
): T {
    val attempt = ContentDeletionAttempt()
    return try {
        operation(attempt)
    } catch (failure: IOException) {
        // A lost response may follow a committed deletion and a newer edit on another device.
        if (!attempt.canRetry(failure)) throw failure
        delay(350)
        operation(attempt)
    }
}
