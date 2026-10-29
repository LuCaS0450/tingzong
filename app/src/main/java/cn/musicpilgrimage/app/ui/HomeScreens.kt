package cn.musicpilgrimage.app.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import cn.musicpilgrimage.app.*
import cn.musicpilgrimage.app.data.*
import cn.musicpilgrimage.app.design.BauhausMediaFrame
import cn.musicpilgrimage.app.design.BauhausMediaInsetShape
import cn.musicpilgrimage.app.design.BauhausPanel
import kotlinx.coroutines.launch
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable internal fun ExploreScreen(
    vm: ExploreViewModel, session: SessionViewModel, onPlace: (String) -> Unit,
    onLogin: () -> Unit, initialMode:String?=null
) {
    val query by vm.query.collectAsState()
    val hits by vm.hits.collectAsState()
    val offline by vm.offline.collectAsState()
    val owner by session.signedIn.collectAsState()
    val catalog by session.services.catalog.collectAsState()
    val fix by vm.fix.collectAsState()
    val prefs = LocalContext.current.getSharedPreferences("tingzong-explore", android.content.Context.MODE_PRIVATE)
    var mode by rememberSaveable { mutableStateOf(initialMode ?: prefs.getString("view", "works") ?: "works") }
    var work by rememberSaveable { mutableStateOf(prefs.getString("work", null)) }
    var camera by rememberSaveable { mutableStateOf<List<Double>?>(null) }
    var restoredQuery by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        if(!restoredQuery) { vm.query.value=prefs.getString("query", "").orEmpty(); restoredQuery=true }
    }
    LaunchedEffect(query) { if(restoredQuery) prefs.edit().putString("query", query).apply() }
    LaunchedEffect(work) { prefs.edit().putString("work", work).apply() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) {camera=null; vm.nearby()}
        else vm.error.value = "未获得精确位置权限。仍可搜索和浏览地点。"
    }
    // Opening exploration does not request location or move a previously saved map.
    // Apply the current query synchronously while the asynchronous remote search catches up.
    // Otherwise a just-opened map can frame the previous query and save that old camera.
    val visible=CatalogSearch.filter(hits.map{it.place},catalog.artists,query).filter { p->work==null || p.scenes.any {"${it.artistId}|${it.work}"==work} }
    val works=remember(hits,query,catalog.artists) {
        hits.flatMap { hit-> hit.place.scenes.filter { s->
            CatalogSearch.filter(listOf(hit.place.copy(scenes=listOf(s))),catalog.artists,query).isNotEmpty()
        }.map { s-> Triple("${s.artistId}|${s.work}",hit.place,s) } }
            .groupBy {it.first}.values.toList()
    }
    Column(Modifier.fillMaxSize()) {
        PageTitle("探索","从一首歌，走进一座城")
        OutlinedTextField(query,{vm.query.value=it;camera=null},Modifier.fillMaxWidth().padding(horizontal=20.dp),
            placeholder={Text("搜索歌曲、专辑、歌手或城市")},leadingIcon={Icon(Icons.Rounded.Search,null)},
            trailingIcon={if(query.isNotBlank())IconButton({vm.query.value="";camera=null}){Icon(Icons.Rounded.Close,"清空搜索")}},
            singleLine=true,shape=RoundedCornerShape(5.dp),keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),
            keyboardActions=KeyboardActions(onSearch={focus.clearFocus();keyboard?.hide()}))
        FlowRow(Modifier.fillMaxWidth().padding(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("works" to "作品","list" to "地点","map" to "地图").forEach {(value,label)->
                FilterChip(mode==value,{
                    focus.clearFocus();keyboard?.hide();mode=value;prefs.edit().putString("view",value).apply()
                },label={Text(label)},modifier=Modifier.testTag("explore-$value"))
            }
            if(mode!="works") IconButton({permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))}) {
                Icon(Icons.Rounded.MyLocation,"获取附近地点")
            }
        }
        if(work!=null) Row(Modifier.padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(formatWorkTitle(work!!.substringAfter('|')),Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
            TextButton({work=null;camera=null}){Text("全部作品")}
        }
        if(offline) Text("本机目录",Modifier.padding(horizontal=20.dp),color=Muted,style=MaterialTheme.typography.bodySmall)
        val error by vm.error.collectAsState()
        error?.let {Row(Modifier.padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically){
            Text(it,Modifier.weight(1f),color=Muted,style=MaterialTheme.typography.bodySmall)
            IconButton(vm::clearError){Icon(Icons.Rounded.Close,"关闭提示")}
        }}
        when(mode) {
            "works" -> LazyColumn(Modifier.weight(1f).testTag("work-grid"),
                contentPadding=PaddingValues(20.dp,12.dp,20.dp,24.dp),
                verticalArrangement=Arrangement.spacedBy(18.dp)) {
                if(works.isEmpty())item{StatusCard("还没找到这部作品","试试歌曲、歌手别名或城市。",Icons.Rounded.SearchOff)}
                items(works,key={it.first().first}) { group->
                    val entry=group.first(); val scene=entry.third
                    BauhausPanel(Modifier.testTag("work-${scene.id}").clickable {
                        work=entry.first;camera=null;mode="list";prefs.edit().putString("view",mode).apply()
                    }) {Row(Modifier.fillMaxWidth().heightIn(min=148.dp).padding(12.dp),
                        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                        ReleaseCover(entry.second.copy(scenes=listOf(scene)),Modifier.size(112.dp))
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                            Text(formatWorkTitle(scene.work),style=MaterialTheme.typography.titleMedium)
                            Text(scene.artistName,color=Muted,style=MaterialTheme.typography.bodyMedium)
                        }
                    }}
                }
            }
            "map" -> {
                val mapPlaces=visible
                BauhausMediaFrame(Modifier.weight(1f).padding(12.dp,8.dp,12.dp,12.dp).testTag("explore-map-viewport")) {
                    MapPanel(mapPlaces,BuildConfig.MAPTILER_KEY,{p->
                        onPlace(p.id)
                    },Modifier.fillMaxSize(),mediaShape=BauhausMediaInsetShape,
                        selected=fix?.let{it.latitude to it.longitude},savedCamera=camera,onCameraChanged={camera=it})
                }
            }
            else -> LazyColumn(Modifier.weight(1f).testTag("place-list"),contentPadding=PaddingValues(20.dp,8.dp,20.dp,24.dp)) {
                item {Text("${visible.size} 个地点",color=Muted)}
                if(visible.isEmpty())item {StatusCard("还没找到这个地方","试试歌曲名称、歌手别名或城市。",Icons.Rounded.SearchOff)}
                items(visible,key={it.id}) {p->PlaceRow(p){onPlace(p.id)}}
            }
        }
    }
}

@Composable internal fun MeScreen(
    session: SessionViewModel,
    onLogin: () -> Unit,
    onRoute: (String) -> Unit
) {
    val owner by session.signedIn.collectAsState()
    val rawProfile by session.profile.collectAsState()
    val p=rawProfile?.takeIf{it.optString("id")==owner}
    LazyColumn(Modifier.testTag("me-list"), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            PageTitle("我的", null) {
                IconButton({ onRoute("settings") }) {
                    Icon(Icons.Rounded.Settings, "设置", tint = Muted)
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                if (owner == null) LoginGate(onLogin)
                else {
                    Row(
                        Modifier.fillMaxWidth().clickable { onRoute("edit-profile") }.padding(vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Avatar(p?.optString("nickname").orEmpty(), owner.orEmpty(), Modifier.size(72.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p?.optString("nickname")?.takeIf { it.isNotBlank() } ?: "设置你的昵称", style = MaterialTheme.typography.titleLarge)
                            Text(p?.optString("bio")?.takeIf { it.isNotBlank() } ?: "完善简介", color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(Icons.Rounded.ChevronRight, null)
                    }
                    ViewModelStatus(session)
                    if (p?.optString("status") == "disabled") {
                        ErrorNotice("账号已停用：可以浏览、管理收藏、删除内容或将既有内容转为私密。")
                    }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(5.dp)).background(Panel).padding(vertical = 20.dp)
                    ) {
                        listOf("巡礼" to "visit_count", "复刻" to "recreation_count").forEach { (label, key) ->
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text((p?.optInt(key) ?: 0).toString(), style = MaterialTheme.typography.titleLarge)
                                Text(label, color = Muted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    SectionTitle("记录")
                    ActionRow(Icons.Rounded.Place, "实地巡礼") { onRoute("mine/visit") }
                    ActionRow(Icons.Rounded.CameraAlt, "场景复刻") { onRoute("mine/recreation") }
                    ActionRow(Icons.Rounded.ChatBubbleOutline, "地点留言") { onRoute("mine/message") }
                    ActionRow(Icons.Rounded.EditLocationAlt, "我的投稿") { onRoute("submissions") }
                    ActionRow(Icons.Rounded.Person, "查看公开主页") { onRoute("profile/$owner") }
                    if (p?.optString("role") == "admin") {
                        ActionRow(Icons.Rounded.AdminPanelSettings, "管理中心") { onRoute("admin") }
                    }
                    SectionTitle("徽章")
                    val badges = p?.optJSONArray("badges")?.objects().orEmpty()
                    badges.forEach { b ->
                        val earned = b.optString("earned_at").isNotBlank()
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (earned) Icons.Rounded.Verified else Icons.Rounded.RadioButtonUnchecked,
                                if (earned) "已获得" else "未获得",
                                tint = if (earned) Acid else Muted
                            )
                            Column {
                                Text(b.optString("name"), fontWeight = FontWeight.Bold)
                                Text(b.optString("description"), color = Muted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
