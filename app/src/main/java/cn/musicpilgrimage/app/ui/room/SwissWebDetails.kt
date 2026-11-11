package cn.musicpilgrimage.app.ui.room

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.ConsoleMessage
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.doOnLayout
import cn.musicpilgrimage.app.BuildConfig
import cn.musicpilgrimage.app.data.*
import cn.musicpilgrimage.app.ui.AppMotionDialog
import cn.musicpilgrimage.app.ui.LocalArchiveBack
import cn.musicpilgrimage.app.ui.LocalArchiveDrawn
import cn.musicpilgrimage.app.ui.LocalPhysicalMotion
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

private const val DetailFontOrigin = "https://details.tingzong.local/fonts/"
private const val DetailPage = "file:///android_asset/design/swiss-details/index.html"

/** The approved HTML/CSS is the rendering source of truth; data and actions remain native. */
@SuppressLint("SetJavaScriptEnabled")
@Composable internal fun SwissWebDetail(data:JSONObject,onAction:(String,JSONObject)->Unit) {
    val context=LocalContext.current
    val motion=LocalPhysicalMotion.current
    val archiveBack=LocalArchiveBack.current
    val latestDrawn by rememberUpdatedState(LocalArchiveDrawn.current)
    val latestMotion by rememberUpdatedState(motion)
    val latestAction by rememberUpdatedState(onAction)
    val json=data.toString()
    val latestJson by rememberUpdatedState(json)
    var archiveScroll by rememberSaveable(data.optString("identity")){mutableStateOf(0.0)}
    val web=remember(context){WebView(context)}
    val rendered=remember(web){arrayOf<String?>(null)}
    val alive=remember(web){booleanArrayOf(true)}
    var ready by remember(web){mutableStateOf(false)}
    var firstPaint by remember(web){mutableStateOf(false)}
    var failed by remember(web){mutableStateOf(false)}
    var loadAttempt by remember(web){mutableIntStateOf(0)}
    var imageOpen by remember(web){mutableStateOf(false)}
    LaunchedEffect(web,ready,motion){if(ready)web.evaluateJavascript("setDetailMotion($motion)",null)}
    LaunchedEffect(web,loadAttempt,firstPaint) {
        if(!firstPaint){delay(3600);failed=true}
    }
    LaunchedEffect(firstPaint,failed) {
        if(firstPaint||failed){withFrameNanos{};latestDrawn()}
    }
    BackHandler(imageOpen||archiveBack!=null){
        if(imageOpen)web.evaluateJavascript("closeNativeImage()",null) else archiveBack?.invoke()
    }
    DisposableEffect(web) {onDispose{alive[0]=false;web.removeJavascriptInterface("TingzongDetail");web.stopLoading();web.destroy()}}
    fun render(value:String,scroll:Double=0.0) {
        web.renderDetail(value,scroll) { renderedBody ->
            if(!alive[0]||firstPaint)return@renderDetail
            if(!renderedBody){failed=true;return@renderDetail}
            web.doOnLayout { view ->
                if(alive[0]&&view.isAttachedToWindow&&view.width>0&&view.height>0) {
                    web.postVisualStateCallback(loadAttempt.toLong(),object:WebView.VisualStateCallback() {
                        override fun onComplete(requestId:Long) {
                            if(alive[0]&&requestId==loadAttempt.toLong()){
                                firstPaint=true;failed=false
                                if(BuildConfig.DEBUG)android.util.Log.d("SwissDetail","Archive first frame ready")
                            }
                        }
                    })
                }
            }
        }
    }
    fun documentReady() {
        if(!alive[0]||ready)return
        web.evaluateJavascript("setDetailMotion($latestMotion)",null)
        ready=true
        rendered[0]=latestJson
        render(latestJson,archiveScroll)
    }
    Box(Modifier.fillMaxSize()) {
    AndroidView(factory={web.apply {
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
                    val offset=message.optDouble("scrollTop")
                    if(offset.isFinite()&&offset>=0)archiveScroll=offset
                    when(val action=message.optString("action")) {
                        "__document-ready"->documentReady()
                        "__image-opened"->imageOpen=true
                        "__image-closed"->imageOpen=false
                        else->latestAction(action,message)
                    }
                }
            }
        },"TingzongDetail")
        webViewClient=object:WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
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
                if(url==DetailPage)documentReady()
            }
            override fun onReceivedError(view:WebView,request:WebResourceRequest,error:WebResourceError) {
                if(request.isForMainFrame&&alive[0])failed=true
            }
            override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean =
                !request.url.toString().startsWith("file:///android_asset/design/swiss-details/")
        }
        addOnLayoutChangeListener { view,_,_,_,_,_,_,_,_->if(view.width>0&&view.height>0&&ready)evaluateJavascript("sizeDetail()",null)}
        loadUrl(DetailPage)
    }},modifier=Modifier.fillMaxSize().testTag("swiss-web-detail"),update={
        if(ready&&rendered[0]!=json){rendered[0]=json;render(json)}
    })
    if(!firstPaint)ArchiveLoadingPage(data,failed,{latestAction("back",JSONObject())}) {
        failed=false;ready=false;loadAttempt++;rendered[0]=null
        web.stopLoading();web.loadUrl(DetailPage)
    }
    }
}

private fun WebView.renderDetail(json:String,initialScroll:Double,onRendered:(Boolean)->Unit) {
    evaluateJavascript("(function(){try{updateNativeDetail($json,$initialScroll);return !!document.querySelector('.scroll-content');}catch(e){console.error('Native detail render: '+e.name+': '+e.message);return false;}})()") {
        onRendered(it=="true")
    }
}

@Composable private fun ArchiveLoadingPage(data:JSONObject,failed:Boolean,onBack:()->Unit,onRetry:()->Unit) {
    val release=data.optJSONArray("releases")?.optJSONObject(0)
    val (header,title)=when(data.optString("page")) {
        "album"->"发行档案" to release?.optString("title").orEmpty()
        "work"->"作品档案" to release?.optJSONArray("works")?.optJSONObject(0)?.optString("title").orEmpty()
        "place"->"地点档案" to data.optJSONArray("places")?.optJSONObject(0)?.optString("name").orEmpty()
        else->"行程档案" to data.optJSONObject("trip")?.optString("name").orEmpty()
    }
    RoomWall(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.fillMaxSize()) {
            RoomHeader(header,onBack=onBack,onSpace={})
            Column(Modifier.fillMaxWidth().padding(24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                if(title.isNotBlank())Text(title,fontSize=27.sp,color=RoomColors.Ink)
                Text(if(failed)"档案暂时无法载入。"else "正在打开档案…",fontSize=14.sp,color=RoomColors.Muted)
                if(failed)TextButton(onRetry,Modifier.align(Alignment.Start)){Text("重试")}
            }
        }
    }
}

internal class SwissDetailMedia(private val context:Context) {
    private val reviewed by lazy{JSONObject(context.assets.open("design/swiss-details/media-index.json").bufferedReader().use{it.readText()})}
    val fallbacks=JSONObject()
    fun artwork(art:ReleaseArtwork?):String {
        if(art?.approved!=true)return ""
        return reviewed.optString(art.id).ifBlank{BundledCovers.model(context,art.id,art.imageUrl).orEmpty()}
    }
    fun scene(scene:Scene?):String {
        if(scene?.imageApproved!=true)return ""
        reviewed.optString(scene.id).takeIf{it.isNotBlank()}?.let{return it}
        if(scene.referenceKind=="release_cover")scene.displayCover?.takeIf{it.approved}?.let{return artwork(it)}
        val source=scene.imageUrl.orEmpty()
        BundledCovers.model(context,"reference-${scene.id}",source)?.takeIf{it!=source}?.let{return it}
        return source
    }
    fun archivePhoto(place: Place): ArchivePhoto? = ArchivePhotography.photo(context, place)?.also {
        fallbacks.put(it.asset, it.url)
    }

    private fun archivePlaceIdentity(place: Place) = JSONObject().put("id", place.id)
        .put("name", PlaceEditorialCopy.name(context, place)).put("city", place.city).put("country", place.country)

    fun archiveLink(link: MusicPlaceLink): JSONObject = archivePlaceIdentity(link.place)
        .put("image", archivePhoto(link.place)?.asset.orEmpty())
        .put("relations", JSONArray(link.scenes.map { it.archiveConnection().label }.distinct()))

    fun placeArchive(place: Place, photo: ArchivePhoto?, entryScenes: Set<String>, entryTitle: String) = archivePlaceIdentity(place)
        .put("photo", photo?.json() ?: JSONObject.NULL).put("scope", place.archiveScope())
        .put("connections", place.archiveConnections(entryScenes)).put("entryTitle", entryTitle)

    fun place(place:Place,scene:Scene?=place.mapScene()):JSONObject=JSONObject()
        .put("id",place.id).put("name",PlaceEditorialCopy.name(context,place)).put("city",place.city).put("country",place.country)
        .put("description",PlaceEditorialCopy.intro(context,place,scene)).put("image",scene(scene)).put("work",scene?.work.orEmpty())
        .put("artist",scene?.artistName.orEmpty()).put("kind",scene?.kind.orEmpty()).put("associationLabel",scene?.kindLabel.orEmpty())
        .put("source",scene?.sourceUrl.orEmpty()).put("credit",scene?.imageCredit.orEmpty())
        .put("timestamp",scene?.timestampSeconds?:JSONObject.NULL).put("arrivalNote","")
        .put("mediaLabel",when {scene?.referenceKind=="film_preview"->"电影片段公开预览图";scene?.referenceKind=="soundtrack_mv_frame"->"原声曲目 MV · 非此地取景";scene?.referenceKind=="lyric_mv_frame"->"歌词关联 · MV 画面";scene?.referenceKind=="film_frame"->"电影原片";scene?.referenceKind=="release_cover"->"正式发行封面";scene?.referenceKind=="album_photo"->"专辑内页";scene?.kindLabel?.startsWith("官方现场")==true->"官方现场画面";else->"MV 画面"})
}

internal fun swissDetailData(page:String,id:String)=JSONObject().put("page",page).put("identity","$page:$id")
    .put("payload",JSONObject().put("id",id)).put("releases",JSONArray()).put("places",JSONArray()).put("favorites",JSONArray())

@Composable internal fun SwissReleaseDetail(release:MusicRelease,favorite:Boolean,onFavorite:()->Unit,
    onWork:(MusicWork)->Unit,onPlace:(String)->Unit,onBack:()->Unit,onSearch:()->Unit) {
    val context=LocalContext.current
    val media=remember(context){SwissDetailMedia(context)}
    val art=release.artwork
    val places=release.places.map(media::archiveLink)
    val row=JSONObject().put("id",release.id).put("title",release.title).put("artist",release.artist)
        .put("year",art?.releaseYear?:"").put("image",media.artwork(art)).put("credit",art?.imageCredit.orEmpty())
        .put("imageWidth",art?.imageWidth?:1).put("imageHeight",art?.imageHeight?:1)
        .put("works",JSONArray(release.works.map{JSONObject().put("title",it.title).put("places",JSONArray(it.places.map{link->link.place.id}))}))
        .put("places",JSONArray(release.places.map{it.place.id}))
    val data=swissDetailData("album",release.id).put("releases",JSONArray().put(row)).put("places",JSONArray(places))
        .put("favorites",JSONArray(if(favorite)listOf(release.id)else emptyList<String>())).put("fallbacks",media.fallbacks)
    var sources by remember(release.id){mutableStateOf(false)}
    SwissWebDetail(data){action,args->when(action) {
        "back"->onBack();"favorite"->onFavorite();"search-release"->onSearch()
        "work"->release.works.getOrNull(args.optInt("index"))?.let(onWork)
        "place"->args.optString("id").takeIf{id->release.places.any{it.place.id==id}}?.let(onPlace)
        "sources"->sources=true
    }}
    if(sources)SwissArtworkSources(art,release.title){sources=false}
}

@Composable internal fun SwissWorkDetail(work:MusicWork,releaseTitle:String,favorite:Boolean,onFavorite:()->Unit,
    onPlace:(String)->Unit,onBack:()->Unit,onRelease:()->Unit) {
    val context=LocalContext.current
    val media=remember(context){SwissDetailMedia(context)}
    val art=work.artwork
    val release=JSONObject().put("id",work.releaseId).put("title",releaseTitle).put("artist",work.artist)
        .put("image",media.artwork(art)).put("credit",art?.imageCredit.orEmpty())
        .put("imageWidth",art?.imageWidth?:1).put("imageHeight",art?.imageHeight?:1)
        .put("places",JSONArray(work.places.map{it.place.id}))
        .put("works",JSONArray().put(JSONObject().put("title",work.title)
            .put("reason",archiveAdmissionReason(work.places){PlaceEditorialCopy.name(context,it)})
            .put("places",JSONArray(work.places.map{it.place.id}))))
    val data=swissDetailData("work",work.id).put("payload",JSONObject().put("release",work.releaseId).put("index",0))
        .put("releases",JSONArray().put(release)).put("places",JSONArray(work.places.map(media::archiveLink)))
        .put("favorites",JSONArray(if(favorite)listOf("work:${work.releaseId}:0")else emptyList<String>())).put("fallbacks",media.fallbacks)
    var sources by remember(work.id){mutableStateOf(false)}
    SwissWebDetail(data){action,args->when(action){"back"->onBack();"favorite"->onFavorite();"release"->onRelease()
        "sources"->sources=true
        "place"->args.optString("id").takeIf{id->work.places.any{it.place.id==id}}?.let(onPlace)}}
    if(sources)SwissArtworkSources(art,work.title,work.places){sources=false}
}

@Composable private fun SwissArtworkSources(art:ReleaseArtwork?,title:String,links:List<MusicPlaceLink> = emptyList(),onClose:()->Unit) {
    val context=LocalContext.current
    AppMotionDialog(onClose,properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)) {
        RoomWall(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {Column {
            RoomHeader("资料来源",onBack=onClose,onSpace={})
            LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(25.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                item { Text(title,fontSize=25.sp,lineHeight=34.sp,color=RoomColors.Ink) }
                if(art!=null)item {
                    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        val credit=art.imageCredit.replace(Regex("https?://\\S+"),"").trim(' ','·')
                        if(credit.isNotBlank())Text(credit,fontSize=14.sp,lineHeight=24.sp,color=RoomColors.Muted)
                        if(art.sourceUrl.startsWith("http"))RoomSourceLink("查看原始发行资料",art.sourceUrl)
                        else Text("这张图片暂无来源信息。",fontSize=13.sp,color=RoomColors.Muted)
                    }
                }
                if(links.isNotEmpty())item { Text("收录依据",fontSize=18.sp,color=RoomColors.Ink) }
                items(links,key={it.place.id}) { link ->
                    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        HorizontalDivider(color=RoomColors.Line)
                        Text(PlaceEditorialCopy.name(context,link.place),fontSize=16.sp,color=RoomColors.Ink)
                        link.scenes.distinctBy{it.archiveConnection() to (it.associationSourceUrl.ifBlank{it.sourceUrl})}.forEach { scene ->
                            val label=scene.archiveConnection().label
                            val source=scene.associationSourceUrl.ifBlank{scene.sourceUrl.ifBlank{scene.canonicalWorkUrl}}
                            if(source.startsWith("http"))RoomSourceLink("查看${label}依据",source)
                            else Text(label,fontSize=13.sp,color=RoomColors.Muted)
                        }
                    }
                }
            }
        }}
    }
}
