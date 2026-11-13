package cn.musicpilgrimage.app

import android.app.Application
import android.os.Build
import android.util.Log
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewOutcomeReceiver
import androidx.webkit.WebViewStartUpConfig
import androidx.webkit.WebViewStartUpResult
import androidx.webkit.WebViewStartupException
import cn.musicpilgrimage.app.data.AppServices
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.SvgDecoder
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred

class PilgrimageApplication : Application(), ImageLoaderFactory {
    val services by lazy { AppServices(this) }
    private val webViewStartup = CompletableDeferred<Unit>()

    override fun onCreate() {
        super.onCreate()
        val background = Executors.newSingleThreadExecutor()
        WebViewCompat.startUpWebView(this, WebViewStartUpConfig.Builder(background).build(),
            object : WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException> {
                override fun onResult(result: WebViewStartUpResult) = finish()

                override fun onError(error: WebViewStartupException) {
                    Log.w("TingzongWebView", "Asynchronous WebView startup failed", error)
                    finish()
                }

                private fun finish() {
                    background.shutdown()
                    webViewStartup.complete(Unit)
                }
            })
    }

    internal suspend fun awaitWebViewStartup() = webViewStartup.await()

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                if (Build.VERSION.SDK_INT >= 28) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
                add(SvgDecoder.Factory())
            }
            .crossfade(true)
            .build()
    }
}
