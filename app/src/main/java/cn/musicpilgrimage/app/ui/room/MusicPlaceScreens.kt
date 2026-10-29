package cn.musicpilgrimage.app.ui.room

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.musicpilgrimage.app.*
import cn.musicpilgrimage.app.data.*
import cn.musicpilgrimage.app.ui.ContentCard
import cn.musicpilgrimage.app.ui.ViewModelStatus

@Composable internal fun RoomPlaceDetail(
    place:Place,work:MusicWork?,vm:PlaceViewModel,session:SessionViewModel,
    favorite:Boolean,favoriteBusy:Boolean,onFavorite:()->Unit,onLogin:()->Unit,
    onForm:(String,String,ContentItem?)->Unit,onDelete:(ContentItem)->Unit,onCommunity:()->Unit,onAdd:()->Unit,
    onBack:()->Unit={},onMap:()->Unit={}
) {
    val owner by session.signedIn.collectAsStateWithLifecycle()
    val revision by session.services.changed.collectAsStateWithLifecycle()
    val own by vm.ownItems.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    LaunchedEffect(place.id,owner,revision){vm.load(place.id)}
    val context=LocalContext.current
    val hero=remember(place,work){place.mapScene(work)}
    val media=remember(context){SwissDetailMedia(context)}
    var sources by rememberSaveable(place.id){mutableStateOf(false)}
    var imageReady by remember(hero?.id){mutableStateOf(false)}
    val visits=own.filter{it.owner==owner&&it.kind=="visit"}.sortedByDescending{it.raw.optString("verified_at").ifBlank{it.raw.optString("created_at")}}
    val data=swissDetailData("place",place.id).put("places",org.json.JSONArray().put(media.place(place,hero)))
        .put("favorites",org.json.JSONArray(if(favorite)listOf(place.id)else emptyList<String>())).put("fallbacks",media.fallbacks)
    SwissWebDetail(data){action,_->when(action) {
        "back"->onBack();"favorite"->if(!favoriteBusy)onFavorite();"add-trip"->onAdd();"map-place"->onMap()
        "record-new"->if(owner==null)onLogin()else onForm("visit",place.id,null)
        "community"->onCommunity();"sources"->sources=true;"hero-ready"->imageReady=true
    }}
    if(sources)androidx.compose.ui.window.Dialog({sources=false},properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)) {
        RoomWall(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {Column {
            RoomHeader("资料来源",onBack={sources=false},onSpace={})
            LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(25.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                item {Text(PlaceEditorialCopy.name(context,place),fontSize=25.sp,lineHeight=34.sp,color=RoomColors.Ink)}
                items(place.scenes.filter{it.publiclyVisible}.distinctBy{it.work to it.sourceUrl},key={it.id}){scene->Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider(color=RoomColors.Line)
                    Text("${scene.work} · ${scene.artistName}",fontSize=18.sp,lineHeight=27.sp,color=RoomColors.Ink)
                    Text(scene.kindLabel.substringBefore(" · "),fontSize=12.sp,color=RoomColors.Muted)
                    scene.timestampSeconds?.let{Text("原作 ${it/60}:${(it%60).toString().padStart(2,'0')}",fontSize=12.sp,color=RoomColors.Muted)}
                    val credit=scene.imageCredit.orEmpty().replace(Regex("https?://\\S+"),"").trim(' ','·')
                    if(credit.isNotBlank())Text(credit,fontSize=12.sp,lineHeight=20.sp,color=RoomColors.Muted)
                    if(scene.sourceUrl.startsWith("http"))RoomSourceLink("查看作品来源",scene.sourceUrl)
                    if(scene.canonicalWorkUrl.startsWith("http")&&scene.canonicalWorkUrl!=scene.sourceUrl)RoomSourceLink("查看原作版本",scene.canonicalWorkUrl)
                }}
                if(hero?.canRecreate==true)item{SwissOutlinedButton({sources=false;if(owner==null)onLogin()else onForm("recreation",hero.id,own.find{it.kind=="recreation"&&it.sceneId==hero.id})},enabled=imageReady&&!busy,modifier=Modifier.fillMaxWidth()){Text(if(imageReady)"复刻这个镜头"else "正在加载原作…")}}
                item{SwissTextButton({sources=false;if(owner==null)onLogin()else onForm("correction",place.id,null)}){Text("补充地点信息")}}
            }
        }}
    }
}
@Composable private fun RoomDisclosureTitle(title:String,expanded:Boolean,onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().clickable(role=Role.Button,onClick=onClick),verticalAlignment=Alignment.CenterVertically){Text(title,Modifier.weight(1f),fontSize=17.sp,fontWeight=FontWeight.Medium,color=RoomColors.Ink);Icon(if(expanded)Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,if(expanded)"收起$title" else "展开$title",tint=RoomColors.Muted)}
}

@Composable internal fun RoomSourceLink(title:String,url:String) {
    val context=LocalContext.current
    SwissTextButton({runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))}.onFailure{Toast.makeText(context,"暂时无法打开链接",Toast.LENGTH_SHORT).show()}}){Icon(Icons.Outlined.OpenInNew,null,Modifier.size(15.dp));Spacer(Modifier.width(7.dp));Text(title)}
}

