package cn.musicpilgrimage.app.ui.room

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.ConsoleMessage
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.doOnLayout
import androidx.core.graphics.drawable.toBitmap
import cn.musicpilgrimage.app.BuildConfig
import cn.musicpilgrimage.app.PilgrimageApplication
import cn.musicpilgrimage.app.data.*
import cn.musicpilgrimage.app.ui.AppMotionDialog
import cn.musicpilgrimage.app.ui.AppPredictiveBackHandler
import cn.musicpilgrimage.app.ui.CoverTransitionCoordinator
import cn.musicpilgrimage.app.ui.LocalCoverCoordinator
import cn.musicpilgrimage.app.ui.LocalPhysicalMotion
import cn.musicpilgrimage.app.ui.LocalRouteActive
import cn.musicpilgrimage.app.ui.LocalRouteEntryId
import cn.musicpilgrimage.app.ui.LocalRouteIsCurrent
import cn.musicpilgrimage.app.ui.archiveCoverTopPx
import coil.compose.AsyncImage
import coil.imageLoader
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

private val webViewInstanceSeq = AtomicLong(1L)
private const val DetailFontOrigin = "https://details.tingzong.local/fonts/"
private const val DetailMapUrl = "https://details.tingzong.local/map/countries.json"
private const val DetailPage = "file:///android_asset/design/swiss-details/index.html"
private const val CoverPreviewWaitMillis = 120L
private data class CachedCoverPreview(val entryId: String?, val data: String?)

/** Exercises the WebView handoff protocol used by the approved design prototype. */
@SuppressLint("SetJavaScriptEnabled")
@Composable internal fun SwissWebDetail(
    data:JSONObject,
    onArchivePhoto:((JSONObject,suspend ()->Unit)->Unit)?=null,
    onAction:(String,JSONObject)->Unit,
) {
    val context=LocalContext.current
    val coordinator=LocalCoverCoordinator.current
    val entryId=LocalRouteEntryId.current
    val isAlbum=data.optString("page")=="album"
    val releaseId=data.optJSONArray("releases")?.optJSONObject(0)?.optString("id")?.takeIf{it.isNotBlank()}
    val morphKey=if(isAlbum&&releaseId!=null)"release:$releaseId" else null
    val morphRoute=if(isAlbum&&releaseId!=null)"album/${Uri.encode(releaseId)}" else null
    var canCreateWebView by remember{mutableStateOf(false)}
    var startupSlow by remember{mutableStateOf(false)}
    LaunchedEffect(context) {
        (context.applicationContext as PilgrimageApplication).awaitWebViewStartup()
        canCreateWebView=true
    }
    if(!canCreateWebView) {
        LaunchedEffect(Unit) { delay(3600); startupSlow=true }
        val retry={canCreateWebView=true}
        val slow=startupSlow
        if(data.optString("page")=="place")PlaceBodyLoading(slow,retry)
        else ArchiveLoadingPage(data,slow,{onAction("back",JSONObject())},retry,coordinator,morphKey,morphRoute,entryId)
        return
    }
    SwissWebDetailContent(data,morphKey,morphRoute,onArchivePhoto,onAction)
}

@SuppressLint("SetJavaScriptEnabled")
@Composable private fun SwissWebDetailContent(
    data:JSONObject,morphKey:String?,morphRoute:String?,
    onArchivePhoto:((JSONObject,suspend ()->Unit)->Unit)?,onAction:(String,JSONObject)->Unit,
) {
    val context=LocalContext.current
    val motion=LocalPhysicalMotion.current
    val active=LocalRouteActive.current
    val routeEntryId=LocalRouteEntryId.current
    val orientation=LocalConfiguration.current.orientation
    val coordinator=LocalCoverCoordinator.current
    val isCurrent by rememberUpdatedState(LocalRouteIsCurrent.current)
    val latestMotion by rememberUpdatedState(motion)
    val latestAction by rememberUpdatedState(onAction)
    val latestArchivePhoto by rememberUpdatedState(onArchivePhoto)
    val json=data.toString()
    val latestJson by rememberUpdatedState(json)
    val identity=data.optString("identity")
    val page=data.optString("page")
    val web=remember(context){WebView(context)}
    val webInstanceId=remember(web){webViewInstanceSeq.getAndIncrement()}
    var boundGeneration by remember(web,routeEntryId,morphRoute,morphKey){mutableLongStateOf(0L)}
    SideEffect {
        if(!isCurrent())return@SideEffect
        coordinator?.registerWebInstance(routeEntryId,webInstanceId)
        if(morphRoute!=null&&morphKey!=null) {
            val g=coordinator?.bindDestinationEntry(morphRoute,morphKey,routeEntryId)
            if(g!=null&&boundGeneration==0L)boundGeneration=g
        }
    }
    val matchingMorph=coordinator?.activeMorph?.takeIf { morph ->
        (morphRoute==null || morph.route==morphRoute) &&
            (morphKey==null || morph.key==morphKey) &&
            when(morph.mode) {
                CoverTransitionCoordinator.Mode.ENTERING ->
                    morph.targetNavEntryId==routeEntryId && (boundGeneration==0L || morph.generation==boundGeneration)
                else -> morph.sourceNavEntryId==null || morph.sourceNavEntryId==routeEntryId
            }
    }
    val holdHeroHidden=coordinator?.shouldHoldHeroHidden(morphKey,morphRoute,routeEntryId)==true
    val activeRevealGeneration=matchingMorph?.takeIf {
        it.mode==CoverTransitionCoordinator.Mode.ENTERING &&
            it.revealDispatched &&
            !it.revealConfirmed
    }?.generation
    val recoveringCover=morphKey!=null&&coordinator?.needsCoverRecovery(routeEntryId)==true
    val coverSource=morphKey?.let{coordinator?.sourceFor(it)}?.takeIf{it.imageLoaded}
    val placeBody=page=="place"
    var archiveScroll by rememberSaveable(identity){mutableStateOf(0.0)}
    var placeBodyHeight by rememberSaveable(identity){mutableIntStateOf(0)}
    val latestCoordinator by rememberUpdatedState(coordinator)
    val latestIdentity by rememberUpdatedState(identity)
    val latestRouteEntryId by rememberUpdatedState(routeEntryId)
    val latestMorphKey by rememberUpdatedState(morphKey)
    val latestMorphRoute by rememberUpdatedState(morphRoute)
    val latestOrientation by rememberUpdatedState(orientation)
    val latestBoundGeneration by rememberUpdatedState(boundGeneration)
    val latestPlaceBody by rememberUpdatedState(placeBody)
    var webBoundsInRoot by remember{mutableStateOf(Rect.Zero)}
    val rendered=remember(web){arrayOf<String?>(null)}
    val alive=remember(web){booleanArrayOf(true)}
    var ready by remember(web){mutableStateOf(false)}
    var firstPaint by remember(web){mutableStateOf(false)}
    var failed by remember(web){mutableStateOf(false)}
    var loadAttempt by remember(web){mutableIntStateOf(1)}
    var renderToken by remember(web){mutableLongStateOf(0L)}
    var activeHeroIdentity by remember(web){mutableStateOf(identity)}
    var heroDecodedToken by remember(web){mutableLongStateOf(0L)}
    var heroDrawnToken by remember(web){mutableLongStateOf(0L)}
    var drawnPendingToken by remember(web){mutableLongStateOf(0L)}
    var heroFailed by remember(web){mutableStateOf(false)}
    var activeImageViewerId by remember(web){mutableLongStateOf(0L)}
    var imageViewerClosing by remember(web){mutableStateOf(false)}
    fun domRectToRoot(rectObj:JSONObject?):Rect? {
        if(rectObj==null||webBoundsInRoot.width<=1f)return null
        val vw=rectObj.optDouble("vw",0.0).toFloat()
        if(vw<=0f)return null
        val s=webBoundsInRoot.width/vw
        return Rect(
            left=webBoundsInRoot.left+rectObj.optDouble("left").toFloat()*s,
            top=webBoundsInRoot.top+rectObj.optDouble("top").toFloat()*s,
            right=webBoundsInRoot.left+rectObj.optDouble("right").toFloat()*s,
            bottom=webBoundsInRoot.top+rectObj.optDouble("bottom").toFloat()*s,
        )
    }
    SideEffect {
        web.importantForAccessibility=if(firstPaint&&active)android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
            else android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
    val photoCoordinator=cn.musicpilgrimage.app.ui.LocalPhotoViewerCoordinator.current
    val hiddenPhotoId=photoCoordinator?.hiddenPhotoId
    LaunchedEffect(identity) {
        if(activeHeroIdentity!=identity) {
            activeHeroIdentity=identity
            heroDecodedToken=0L
            heroDrawnToken=0L
            drawnPendingToken=0L
            heroFailed=false
            firstPaint=false
            activeImageViewerId=0L
            imageViewerClosing=false
        }
    }
    LaunchedEffect(web,ready,motion){if(ready)web.evaluateJavascript("setDetailMotion($motion)",null)}
    val cachedCover by produceState<CachedCoverPreview?>(null,routeEntryId,recoveringCover,coverSource?.model,coverSource?.memoryCacheKey) {
        value=null
        if(recoveringCover&&coverSource!=null) {
            val preview=withTimeoutOrNull(CoverPreviewWaitMillis){loadCachedCoverPreview(context,coverSource)}
            value=CachedCoverPreview(routeEntryId,preview)
        }
    }
    val coverPreviewReady=coverSource==null||cachedCover?.entryId==routeEntryId
    val coverPreview=cachedCover?.takeIf{recoveringCover&&it.entryId==routeEntryId}?.data
    LaunchedEffect(web,ready,firstPaint,renderToken,holdHeroHidden,activeRevealGeneration,recoveringCover,coverPreview,coverPreviewReady){
        if(!ready||renderToken==0L)return@LaunchedEffect
        val generation=activeRevealGeneration?.takeIf{!holdHeroHidden&&firstPaint}?:0L
        if(!holdHeroHidden&&recoveringCover&&!coverPreviewReady)return@LaunchedEffect
        val preview=coverPreview?.let(JSONObject::quote)?:"null"
        web.evaluateJavascript(
            "window.__presentArchiveCover($holdHeroHidden,$recoveringCover,$preview,$renderToken,$generation)",
            null,
        )
    }
    LaunchedEffect(web,ready,hiddenPhotoId){if(ready)web.evaluateJavascript("window.__setArchivePhotoHidden&&window.__setArchivePhotoHidden(${JSONObject.quote(hiddenPhotoId.orEmpty())})",null)}
    LaunchedEffect(web,loadAttempt,firstPaint) {
        if(!firstPaint){delay(3600);failed=true}
    }
    AppPredictiveBackHandler(
        enabled=active&&!placeBody&&activeImageViewerId!=0L,
        onProgress={ event ->
            if(latestMotion&&!imageViewerClosing&&alive[0]) {
                val p=event.progress.coerceIn(0f,1f)
                web.evaluateJavascript("window.__previewNativeImageBack&&window.__previewNativeImageBack($p)",null)
            }
        },
        onCancel={
            if(!imageViewerClosing&&alive[0]) {
                web.evaluateJavascript("window.__cancelNativeImageBack&&window.__cancelNativeImageBack()",null)
            }
        },
        onBack={
            if(alive[0]&&!imageViewerClosing) {
                imageViewerClosing=true
                web.evaluateJavascript("closeNativeImage()",null)
            }
        },
    )
    DisposableEffect(web) {
        onDispose {
            alive[0]=false
            activeImageViewerId=0L
            imageViewerClosing=false
            web.removeJavascriptInterface("TingzongDetail")
            web.stopLoading()
            web.destroy()
            latestCoordinator?.releaseWebInstance(
                webInstanceId=webInstanceId,
                navEntryId=latestRouteEntryId,
                generation=latestBoundGeneration.takeIf{it>0L},
            )
        }
    }
    DisposableEffect(routeEntryId) {
        onDispose {
            coordinator?.releaseEntrySession(
                navEntryId=routeEntryId,
                generation=boundGeneration.takeIf{it>0L},
                webInstanceId=webInstanceId,
            )
        }
    }
    fun render(value:String,scroll:Double=0.0) {
        val attempt=loadAttempt
        val token=++renderToken
        activeHeroIdentity=identity
        heroDecodedToken=0L
        heroDrawnToken=0L
        drawnPendingToken=0L
        heroFailed=false
        web.renderDetail(value,scroll,holdHeroHidden,token,attempt,webInstanceId) { renderedBody ->
            if(!alive[0]||loadAttempt!=attempt||renderToken!=token||activeHeroIdentity!=identity)return@renderDetail
            if(!renderedBody)failed=true
        }
    }
    fun documentReady() {
        if(!alive[0]||ready)return
        web.evaluateJavascript("setDetailMotion($latestMotion)",null)
        ready=true
        rendered[0]=latestJson
        render(latestJson,archiveScroll)
    }
    val latestRender by rememberUpdatedState { value: String, scroll: Double -> render(value, scroll) }
    val latestDocumentReady by rememberUpdatedState { documentReady() }
    val latestDomRectToRoot by rememberUpdatedState { rectObj: JSONObject? -> domRectToRoot(rectObj) }
    val latestSetArchiveScroll by rememberUpdatedState { offset: Double -> archiveScroll = offset }
    val latestGetArchiveScroll by rememberUpdatedState { archiveScroll }
    val latestSetPlaceBodyHeight by rememberUpdatedState { height: Int -> placeBodyHeight = height }
    Box((if(placeBody)Modifier.fillMaxWidth().height((placeBodyHeight.takeIf{it>0}?:200).dp) else Modifier.fillMaxSize()).onGloballyPositioned {
        webBoundsInRoot=it.boundsInRoot()
    }) {
    AndroidView(factory={
        if(BuildConfig.DEBUG)WebView.setWebContentsDebuggingEnabled(true)
        web.apply {
        setBackgroundColor(android.graphics.Color.rgb(247,246,242))
        settings.javaScriptEnabled=true;settings.domStorageEnabled=false;settings.textZoom=100
        settings.allowFileAccess=false;settings.allowContentAccess=false
        settings.mixedContentMode=android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        isVerticalScrollBarEnabled=false;isHorizontalScrollBarEnabled=false
        importantForAccessibility=android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
        webChromeClient=object:WebChromeClient(){override fun onConsoleMessage(message:ConsoleMessage):Boolean {
            if(message.messageLevel()==ConsoleMessage.MessageLevel.ERROR)android.util.Log.e("SwissDetail",message.message()+" @ "+message.sourceId()+":"+message.lineNumber())
            return true
        }}
        addJavascriptInterface(object {
            @JavascriptInterface fun postMessage(raw:String) {
                if(raw.length>8192)return
                val message=runCatching{JSONObject(raw)}.getOrNull()?:return
                post{
                    if(!alive[0])return@post
                    val msgWebId=message.optLong("webInstanceId",0L)
                    if(msgWebId>0L&&msgWebId!=webInstanceId)return@post
                    val msgAttempt=message.optInt("loadAttempt",0)
                    if(msgAttempt>0&&msgAttempt!=loadAttempt)return@post
                    val offset=message.optDouble("scrollTop")
                    if(offset.isFinite()&&offset>=0)latestSetArchiveScroll(offset)
                    val curCoordinator=latestCoordinator
                    val curIdentity=latestIdentity
                    val curRouteEntryId=latestRouteEntryId
                    val curMorphKey=latestMorphKey
                    val curMorphRoute=latestMorphRoute
                    val curOrientation=latestOrientation
                    val expectedGen=latestBoundGeneration.takeIf{it>0L}
                    when(val action=message.optString("action")) {
                        "__document-ready"->latestDocumentReady()
                        "__scroll"->Unit
                        "__body-typeset"->{
                            val token=message.optLong("renderToken")
                            val attempt=loadAttempt
                            val renderedIdentity=message.optString("identity")
                            fun isCurrentRender()=alive[0]&&loadAttempt==attempt&&
                                renderToken==token&&activeHeroIdentity==renderedIdentity
                            if(firstPaint||!isCurrentRender())return@post
                            web.doOnLayout { view ->
                                if(!isCurrentRender()||!view.isAttachedToWindow||view.width<=0||view.height<=0)return@doOnLayout
                                web.postVisualStateCallback(token,object:WebView.VisualStateCallback(){
                                    override fun onComplete(requestId:Long) {
                                        if(requestId==token&&isCurrentRender()){
                                            firstPaint=true
                                            failed=false
                                        }
                                    }
                                })
                            }
                        }
                        "__cover-presented"->{
                            val generation=message.optLong("generation")
                            val token=message.optLong("renderToken")
                            val attempt=loadAttempt
                            val morph=curCoordinator?.activeMorph
                            if(!isCurrent()||!firstPaint||token!=renderToken||morph==null||
                                morph.generation!=generation||morph.targetNavEntryId!=curRouteEntryId||
                                (morph.webInstanceId!=0L&&morph.webInstanceId!=webInstanceId)||
                                !morph.revealDispatched||!web.isAttachedToWindow)return@post
                            web.postVisualStateCallback(generation,object:WebView.VisualStateCallback(){
                                override fun onComplete(requestId:Long) {
                                    if(alive[0]&&isCurrent()&&requestId==generation&&loadAttempt==attempt&&renderToken==token) {
                                        curCoordinator.markRevealConfirmed(generation)
                                    }
                                }
                            })
                        }
                        "__hero-metrics"->{
                            val msgIdentity=message.optString("identity")
                            val msgToken=message.optLong("renderToken",0L)
                            if(msgIdentity.isNotBlank()&&msgIdentity!=activeHeroIdentity)return@post
                            if(msgToken>0L&&msgToken!=renderToken)return@post
                            val activeToken=msgToken.takeIf{it>0L}?:renderToken
                            val activeAttempt=loadAttempt
                            val present=message.optBoolean("present",false)
                            val decoded=message.optBoolean("decoded",false)
                            val heroImgFailed=message.optBoolean("failed",false)
                            val decodedAtMs=message.optLong("decodedAtMs",0L).takeIf{it>0L}
                            val rectInRoot=if(present)latestDomRectToRoot(message.optJSONObject("rect"))else null
                            heroFailed=heroImgFailed
                            if(decoded&&!heroImgFailed)heroDecodedToken=activeToken
                            val alreadyDrawn=!heroImgFailed&&heroDrawnToken==activeToken
                            if(curMorphKey!=null&&curMorphRoute!=null) {
                                curCoordinator?.reportDestinationHero(
                                    route=curMorphRoute,
                                    key=curMorphKey,
                                    heroRectInRoot=rectInRoot,
                                    firstPaint=firstPaint,
                                    navEntryId=curRouteEntryId,
                                    identity=curIdentity,
                                    renderToken=activeToken,
                                    decoded=decoded&&!heroImgFailed,
                                    drawn=alreadyDrawn,
                                    failed=heroImgFailed,
                                    orientation=curOrientation,
                                    timestampMs=decodedAtMs?:System.currentTimeMillis(),
                                    webInstanceId=webInstanceId,
                                    loadAttempt=activeAttempt,
                                    expectedGeneration=expectedGen,
                                )
                                if(decoded&&!heroImgFailed&&rectInRoot!=null&&heroDrawnToken!=activeToken&&drawnPendingToken!=activeToken&&web.isAttachedToWindow) {
                                    drawnPendingToken=activeToken
                                    val confirmEntryId=curRouteEntryId
                                    val confirmRoute=curMorphRoute
                                    val confirmKey=curMorphKey
                                    val confirmIdentity=curIdentity
                                    val confirmOrientation=curOrientation
                                    val confirmRect=rectInRoot
                                    val confirmWebId=webInstanceId
                                    val confirmAttempt=activeAttempt
                                    val confirmGen=expectedGen
                                    web.postVisualStateCallback(activeToken,object:WebView.VisualStateCallback() {
                                        override fun onComplete(requestId:Long) {
                                            web.post {
                                                if(!alive[0]||!web.isAttachedToWindow)return@post
                                                if(loadAttempt!=confirmAttempt||requestId!=renderToken||activeHeroIdentity!=confirmIdentity||heroDecodedToken!=requestId||heroFailed)return@post
                                                heroDrawnToken=requestId
                                                curCoordinator?.reportDestinationHero(
                                                    route=confirmRoute,
                                                    key=confirmKey,
                                                    heroRectInRoot=confirmRect,
                                                    firstPaint=true,
                                                    navEntryId=confirmEntryId,
                                                    identity=confirmIdentity,
                                                    renderToken=requestId,
                                                    decoded=true,
                                                    drawn=true,
                                                    failed=false,
                                                    orientation=confirmOrientation,
                                                    webInstanceId=confirmWebId,
                                                    loadAttempt=confirmAttempt,
                                                    expectedGeneration=confirmGen,
                                                )
                                                if(BuildConfig.DEBUG)android.util.Log.d("SwissDetail","Archive hero drawn (token=$requestId, web=$confirmWebId)")
                                                curCoordinator?.clearCoverRecovery(confirmEntryId)
                                            }
                                        }
                                    })
                                }
                            } else {
                                curCoordinator?.updateDestinationHeroRect(
                                    identity=curIdentity,
                                    rectInRoot=rectInRoot,
                                    navEntryId=curRouteEntryId,
                                    orientation=curOrientation,
                                    renderToken=activeToken,
                                    decoded=decoded&&!heroImgFailed,
                                    drawn=alreadyDrawn,
                                    failed=heroImgFailed,
                                    timestampMs=decodedAtMs,
                                    webInstanceId=webInstanceId,
                                    loadAttempt=activeAttempt,
                                    expectedGeneration=expectedGen,
                                )
                            }
                        }
                        "hero-retry"->if(isCurrent()) {
                            heroFailed=false
                            loadAttempt++
                            latestRender(latestJson,latestGetArchiveScroll())
                        }
                        "__image-opened"->{
                            val vId=message.optLong("viewerId",0L).takeIf{it>0L}?:1L
                            activeImageViewerId=vId
                            imageViewerClosing=false
                        }
                        "__image-closing"->{
                            val vId=message.optLong("viewerId",0L)
                            if(vId==0L||vId==activeImageViewerId)imageViewerClosing=true
                        }
                        "__image-closed"->{
                            val vId=message.optLong("viewerId",0L)
                            if(vId==0L||vId==activeImageViewerId) {
                                activeImageViewerId=0L
                                imageViewerClosing=false
                            }
                        }
                        "__place-body-height"->if(latestPlaceBody)message.optInt("height").takeIf{it>0}?.let{latestSetPlaceBodyHeight(it)}
                        "archive-image"->{
                            latestDomRectToRoot(message.optJSONObject("rect"))?.let { rootRect ->
                                message.put("rootLeft",rootRect.left.toDouble())
                                message.put("rootTop",rootRect.top.toDouble())
                                message.put("rootRight",rootRect.right.toDouble())
                                message.put("rootBottom",rootRect.bottom.toDouble())
                            }
                            if(firstPaint&&isCurrent()) {
                                val openPhoto=latestArchivePhoto
                                val photoId=message.optString("id")
                                if(openPhoto!=null&&photoId.isNotBlank())openPhoto(message) {
                                    if(alive[0]&&isCurrent()&&web.isAttachedToWindow) {
                                        withTimeoutOrNull(160L){web.restoreArchivePhoto(photoId)}
                                    }
                                }
                                else latestAction(action,message)
                            }
                        }
                        "back"->if(isCurrent())latestAction(action,message)
                        else->if(firstPaint&&isCurrent())latestAction(action,message)
                    }
                }
            }
        },"TingzongDetail")
        webViewClient=object:WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.url.toString() == DetailMapUrl) return WebResourceResponse("application/json", "UTF-8", 200, "OK",
                    mapOf("Access-Control-Allow-Origin" to "*"), context.assets.open("design/swiss-world/countries.json"))
                // The file-backed document needs a CORS-enabled origin for bundled web fonts.
                val name = when (request.url.toString()) {
                    DetailFontOrigin + "inter-variable.woff2" -> "inter-variable.woff2"
                    DetailFontOrigin + "swiss-sans.woff2" -> "swiss-sans.woff2"
                    else -> return null
                }
                return WebResourceResponse("font/woff2", null, 200, "OK",
                    mapOf("Access-Control-Allow-Origin" to "*"), context.assets.open("design/swiss-details/fonts/$name"))
            }

            override fun onPageFinished(view:WebView,url:String) {
                if(url==DetailPage)latestDocumentReady()
            }
            override fun onReceivedError(view:WebView,request:WebResourceRequest,error:WebResourceError) {
                if(request.isForMainFrame&&alive[0])failed=true
            }
            override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean =
                !request.url.toString().startsWith("file:///android_asset/design/swiss-details/")
        }
        addOnLayoutChangeListener { view,_,_,_,_,_,_,_,_->if(view.width>0&&view.height>0&&ready)evaluateJavascript("sizeDetail()",null)}
        loadUrl(DetailPage)
    }},modifier=Modifier.fillMaxSize().graphicsLayer{alpha=if(firstPaint)1f else 0f}
        .testTag("swiss-web-detail"),update={
        if(ready&&rendered[0]!=json){rendered[0]=json;render(json)}
    })
    val retry={
        failed=false;ready=false;loadAttempt++;rendered[0]=null
        web.stopLoading();web.loadUrl(DetailPage)
    }
    if(!firstPaint) {
        if(placeBody)PlaceBodyLoading(failed,retry)
        else ArchiveLoadingPage(data,failed,{latestAction("back",JSONObject())},retry,coordinator,morphKey,morphRoute,routeEntryId)
    }
    }
}

private suspend fun WebView.restoreArchivePhoto(id:String)=suspendCancellableCoroutine<Unit> { continuation ->
    evaluateJavascript("window.__restoreArchivePhoto(${JSONObject.quote(id)})") { restored ->
        if(!continuation.isActive)return@evaluateJavascript
        if(restored!="true"||!isAttachedToWindow)continuation.resume(Unit)
        else postVisualStateCallback(0L,object:WebView.VisualStateCallback(){
            override fun onComplete(requestId:Long) {
                if(continuation.isActive)continuation.resume(Unit)
            }
        })
    }
}

private suspend fun loadCachedCoverPreview(context:Context,source:CoverTransitionCoordinator.SourceRecord):String? {
    val loader=context.imageLoader
    val cached=source.memoryCacheKey?.let{loader.memoryCache?.get(MemoryCache.Key(it))?.bitmap}
    val bitmap=cached?:(
        loader.execute(ImageRequest.Builder(context).data(source.model).size(360).allowHardware(false)
            .networkCachePolicy(CachePolicy.DISABLED).build()) as? SuccessResult
    )?.drawable?.toBitmap()?:return null
    return withContext(Dispatchers.Default) {
        val scale=(360f/maxOf(bitmap.width,bitmap.height)).coerceAtMost(1f)
        val resized=if(scale==1f) bitmap else Bitmap.createScaledBitmap(
            bitmap,(bitmap.width*scale).toInt().coerceAtLeast(1),(bitmap.height*scale).toInt().coerceAtLeast(1),true,
        )
        val software=if(resized.config==Bitmap.Config.HARDWARE)resized.copy(Bitmap.Config.ARGB_8888,false) else resized
        @Suppress("DEPRECATION")
        val format=if(Build.VERSION.SDK_INT>=30)Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        val out=ByteArrayOutputStream()
        if(software?.compress(format,90,out)==true) {
            "data:image/webp;base64,${Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP)}"
        } else null
    }
}

@Composable private fun PlaceBodyLoading(failed:Boolean,onRetry:()->Unit) {
    if(!failed)return
    Column(Modifier.fillMaxWidth().heightIn(min=200.dp).background(RoomColors.Paper).padding(25.dp)) {
        Text("地点资料暂时无法载入。",color=RoomColors.Muted,fontSize=14.sp)
        SwissTextButton(onRetry){Text("重试")}
    }
}

private fun WebView.renderDetail(json:String,initialScroll:Double,heroHidden:Boolean=false,renderToken:Long=0L,loadAttempt:Int=0,webInstanceId:Long=0L,onRendered:(Boolean)->Unit) {
    evaluateJavascript("(function(){try{updateNativeDetail($json,$initialScroll,$heroHidden,$renderToken,$loadAttempt,$webInstanceId);return !!document.querySelector('.scroll-content');}catch(e){console.error('Native detail render: '+e.name+': '+e.message);return false;}})()") {
        onRendered(it=="true")
    }
}

@Composable private fun ArchiveLoadingPage(
    data:JSONObject,
    failed:Boolean,
    onBack:()->Unit,
    onRetry:()->Unit,
    coordinator:CoverTransitionCoordinator?=null,
    morphKey:String?=null,
    morphRoute:String?=null,
    routeEntryId:String?=null,
) {
    val context=LocalContext.current
    val release=data.optJSONArray("releases")?.optJSONObject(0)
    val title=release?.optString("title").orEmpty()
    val heroRatio=release?.let {
        val w=it.optDouble("imageWidth",1.0).toFloat().coerceAtLeast(1f)
        val h=it.optDouble("imageHeight",1.0).toFloat().coerceAtLeast(1f)
        (w/h).coerceIn(0.35f,3f)
    } ?: 1f
    val isAlbum=data.optString("page")=="album"&&release?.optString("image")?.isNotBlank()==true
    val preloadedSource=if(isAlbum&&morphKey!=null)coordinator?.sourceFor(morphKey)?.takeIf{it.imageLoaded&&it.model!=null} else null
    RoomWall(Modifier.fillMaxSize(),baseColor=RoomColors.Paper) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val unit=maxWidth/360
            val heroGap=(archiveCoverTopPx(maxWidth.value).dp-unit*88).coerceAtLeast(0.dp)
            Column(Modifier.fillMaxSize()) {
                Spacer(Modifier.height(unit*32))
                Row(Modifier.fillMaxWidth().height(unit*56).padding(start=unit*11),
                    verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onBack,Modifier.size(unit*32).testTag("room-back")) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack,"返回",Modifier.size(unit*20),tint=RoomColors.Ink)
                    }
                }
                if(isAlbum) {
                    Spacer(Modifier.height(heroGap))
                    Box(Modifier.fillMaxWidth().aspectRatio(heroRatio).background(Color(0xFFE9E6DD))) {
                        if(preloadedSource!=null&&
                            coordinator?.shouldHoldHeroHidden(morphKey,morphRoute,routeEntryId)!=true) {
                            val req=remember(preloadedSource.model,preloadedSource.memoryCacheKey,context) {
                                ImageRequest.Builder(context)
                                    .data(preloadedSource.model)
                                    .apply { preloadedSource.memoryCacheKey?.let { placeholderMemoryCacheKey(it) } }
                                    .crossfade(false)
                                    .build()
                            }
                            AsyncImage(req,title.ifBlank{"专辑封面"},Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
                        }
                    }
                }
                if(failed)Column(Modifier.fillMaxWidth().padding(24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                    Text("档案暂时无法载入。",fontSize=14.sp,color=RoomColors.Muted)
                    TextButton(onRetry,Modifier.align(Alignment.Start)){Text("重试")}
                }
            }
        }
    }
}

internal fun swissDetailData(page:String,id:String)=JSONObject().put("page",page).put("identity","$page:$id")
    .put("payload",JSONObject().put("id",id)).put("releases",JSONArray()).put("places",JSONArray()).put("favorites",JSONArray())
