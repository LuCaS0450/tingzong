package cn.musicpilgrimage.app.performance

import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.security.MessageDigest

class AudioHttpTransferTest {
    @get:Rule val temporary=TemporaryFolder()
    private val content=ByteArray(131072){(it%251).toByte()}
    private fun spec()=RecordingFile(content.size.toLong(),MessageDigest.getInstance("SHA-256").digest(content).joinToString(""){"%02x".format(it)})
    private fun server(action:(com.sun.net.httpserver.HttpExchange)->Unit,body:(String)->Unit) {
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/audio"){exchange->try{action(exchange)}finally{exchange.close()}}
        server.start()
        try{body("http://127.0.0.1:${server.address.port}/audio")}finally{server.stop(0)}
    }
    @Test fun resumesExistingPrefixAndChecksWholeFile() {
        val file=temporary.newFile();file.writeBytes(content.copyOfRange(0,32768));var range=""
        server({exchange->
            range=exchange.requestHeaders.getFirst("Range")
            exchange.responseHeaders.add("Content-Range","bytes 32768-${content.size-1}/${content.size}")
            exchange.sendResponseHeaders(206,(content.size-32768).toLong());exchange.responseBody.write(content,32768,content.size-32768)
        }){url->AudioHttpTransfer.download(OkHttpClient(),url,file,spec())}
        assertEquals("bytes=32768-",range);assertArrayEquals(content,file.readBytes());assertTrue(AudioHttpTransfer.valid(file,spec()))
    }
    @Test fun serverIgnoringRangeReplacesRatherThanAppends() {
        val file=temporary.newFile();file.writeBytes(content.copyOfRange(0,1024))
        server({exchange->exchange.sendResponseHeaders(200,content.size.toLong());exchange.responseBody.write(content)}){url->AudioHttpTransfer.download(OkHttpClient(),url,file,spec())}
        assertArrayEquals(content,file.readBytes())
    }
    @Test fun completeCorruptPayloadNeverBecomesReady() {
        val file=temporary.newFile();val corrupt=content.clone().apply{this[400]=99}
        server({exchange->exchange.sendResponseHeaders(200,corrupt.size.toLong());exchange.responseBody.write(corrupt)}){url->
            assertThrows(java.io.IOException::class.java){AudioHttpTransfer.download(OkHttpClient(),url,file,spec())}
        }
        assertFalse(file.exists())
    }
    @Test fun rejectsMismatchedResumeRangeWithoutChangingPrefix() {
        val file=temporary.newFile();val prefix=content.copyOfRange(0,1024);file.writeBytes(prefix)
        server({exchange->exchange.responseHeaders.add("Content-Range","bytes 2048-${content.size-1}/${content.size}");exchange.sendResponseHeaders(206,0)}){url->
            assertThrows(IllegalArgumentException::class.java){AudioHttpTransfer.download(OkHttpClient(),url,file,spec())}
        }
        assertArrayEquals(prefix,file.readBytes())
    }
    @Test fun oversizedResponseCannotGrowPastTrustedSize() {
        val file=temporary.newFile();val tooMuch=ByteArray(content.size+65536)
        server({exchange->exchange.sendResponseHeaders(200,tooMuch.size.toLong());exchange.responseBody.write(tooMuch)}){url->
            assertThrows(IllegalArgumentException::class.java){AudioHttpTransfer.download(OkHttpClient(),url,file,spec())}
        }
        assertTrue(file.length()<=content.size);assertFalse(AudioHttpTransfer.valid(file,spec()))
    }
}
