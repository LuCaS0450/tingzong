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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.musicpilgrimage.app.data.*
import org.json.JSONArray
import org.json.JSONObject

/** The approved HTML/CSS is the rendering source of truth; data and actions remain native. */
@SuppressLint("SetJavaScriptEnabled")
@Composable internal fun SwissWebDetail(data:JSONObject,onAction:(String,JSONObject)->Unit) {
    val context=LocalContext.current
    val latestAction by rememberUpdatedState(onAction)
    val json=data.toString()
    val latestJson by rememberUpdatedState(json)
    val web=remember(context){WebView(context)}
    val rendered=remember(web){arrayOf<String?>(null)}
    var ready by remember(web){mutableStateOf(false)}
    var imageOpen by remember(web){mutableStateOf(false)}
    BackHandler(imageOpen){web.evaluateJavascript("closeNativeImage()",null)}
    DisposableEffect(web) {onDispose{web.removeJavascriptInterface("TingzongDetail");web.stopLoading();web.destroy()}}
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
                post{when(message.optString("action")){"__image-opened"->imageOpen=true;"__image-closed"->imageOpen=false;else->latestAction(message.optString("action"),message)}}
            }
        },"TingzongDetail")
        webViewClient=object:WebViewClient() {
            override fun onPageFinished(view:WebView,url:String) {
                if(url!="file:///android_asset/design/swiss-details/index.html")return
                android.util.Log.d("SwissDetail","Approved local page ready")
                ready=true;rendered[0]=latestJson;view.renderDetail(latestJson)
            }
            override fun onReceivedError(view:WebView,request:WebResourceRequest,error:WebResourceError) {
                if(request.isForMainFrame)android.util.Log.e("SwissDetail","Local page error "+error.errorCode+": "+error.description)
            }
            override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean =
                !request.url.toString().startsWith("file:///android_asset/design/swiss-details/")
        }
        addOnLayoutChangeListener { view,_,_,_,_,_,_,_,_->if(view.width>0&&view.height>0&&ready)evaluateJavascript("sizeDetail()",null)}
        loadUrl("file:///android_asset/design/swiss-details/index.html")
    }},modifier=Modifier.fillMaxSize().testTag("swiss-web-detail"),update={
        if(ready&&rendered[0]!=json){rendered[0]=json;it.renderDetail(json)}
    })
}

private fun WebView.renderDetail(json:String) {
    evaluateJavascript("(function(){try{updateNativeDetail($json);return JSON.stringify({width:innerWidth,height:innerHeight,sections:document.getElementById('app-view').children.length,scale:getComputedStyle(document.getElementById('phone')).transform});}catch(e){console.error('Native detail render: '+e.name+': '+e.message);return 'render-error';}})()") {
        android.util.Log.d("SwissDetail","Render metrics: $it")
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
        BundledCovers.model(context,"reference-${scene.id}",source)?.takeIf{it!=source}?.let{fallbacks.put(source,it)}
        return source
    }
    fun place(place:Place,scene:Scene?=place.mapScene()):JSONObject=JSONObject()
        .put("id",place.id).put("name",PlaceEditorialCopy.name(context,place)).put("city",place.city).put("country",place.country)
        .put("description",PlaceEditorialCopy.intro(context,place)).put("image",scene(scene)).put("work",scene?.work.orEmpty())
        .put("artist",scene?.artistName.orEmpty()).put("kind",scene?.kind.orEmpty())
        .put("source",scene?.sourceUrl.orEmpty()).put("credit",scene?.imageCredit.orEmpty())
        .put("timestamp",scene?.timestampSeconds?:JSONObject.NULL).put("arrivalNote","")
        .put("mediaLabel",when {scene?.referenceKind=="release_cover"->"正式发行封面";scene?.referenceKind=="album_photo"->"专辑内页";scene?.kindLabel?.startsWith("官方现场")==true->"官方现场画面";else->"MV 画面"})
}

internal fun swissDetailData(page:String,id:String)=JSONObject().put("page",page).put("identity","$page:$id")
    .put("payload",JSONObject().put("id",id)).put("releases",JSONArray()).put("places",JSONArray()).put("favorites",JSONArray())

@Composable internal fun SwissReleaseDetail(release:MusicRelease,favorite:Boolean,onFavorite:()->Unit,
    onWork:(MusicWork)->Unit,onPlace:(String)->Unit,onBack:()->Unit,onSearch:()->Unit) {
    val context=LocalContext.current
    val media=remember(context){SwissDetailMedia(context)}
    val art=release.artwork
    val places=release.places.map{media.place(it.place,it.scenes.firstOrNull{scene->scene.imageApproved})}
    val row=JSONObject().put("id",release.id).put("title",release.title).put("artist",release.artist)
        .put("year",art?.releaseYear?:"").put("image",media.artwork(art)).put("credit",art?.imageCredit.orEmpty())
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
    val release=JSONObject().put("id",work.releaseId).put("title",releaseTitle).put("artist",work.artist)
        .put("image",media.artwork(work.artwork)).put("places",JSONArray(work.places.map{it.place.id}))
        .put("works",JSONArray().put(JSONObject().put("title",work.title).put("places",JSONArray(work.places.map{it.place.id}))))
    val data=swissDetailData("work",work.id).put("payload",JSONObject().put("release",work.releaseId).put("index",0))
        .put("releases",JSONArray().put(release)).put("places",JSONArray(work.places.map{media.place(it.place,it.scenes.firstOrNull{scene->scene.imageApproved})}))
        .put("favorites",JSONArray(if(favorite)listOf("work:${work.releaseId}:0")else emptyList<String>())).put("fallbacks",media.fallbacks)
    var sources by remember(work.id){mutableStateOf(false)}
    SwissWebDetail(data){action,args->when(action){"back"->onBack();"favorite"->onFavorite();"release"->onRelease()
        "sources"->sources=true
        "place"->args.optString("id").takeIf{id->work.places.any{it.place.id==id}}?.let(onPlace)}}
    if(sources)SwissArtworkSources(work.artwork,releaseTitle){sources=false}
}

@Composable private fun SwissArtworkSources(art:ReleaseArtwork?,title:String,onClose:()->Unit) {
    androidx.compose.ui.window.Dialog(onClose,properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)) {
        RoomWall(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {Column {
            RoomHeader("资料来源",onBack=onClose,onSpace={})
            Column(Modifier.fillMaxWidth().padding(25.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                Text(title,fontSize=25.sp,lineHeight=34.sp,color=RoomColors.Ink)
                val credit=art?.imageCredit.orEmpty().replace(Regex("https?://\\S+"),"").trim(' ','·')
                if(credit.isNotBlank())Text(credit,fontSize=14.sp,lineHeight=24.sp,color=RoomColors.Muted)
                if(art?.sourceUrl?.startsWith("http")==true)RoomSourceLink("查看原始发行资料",art.sourceUrl)
                else Text("这张图片暂无来源信息。",fontSize=13.sp,color=RoomColors.Muted)
            }
        }}
    }
}
