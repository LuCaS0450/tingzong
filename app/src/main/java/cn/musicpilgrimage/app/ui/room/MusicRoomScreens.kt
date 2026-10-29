package cn.musicpilgrimage.app.ui.room

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.musicpilgrimage.app.data.*
import coil.compose.AsyncImage

@Composable internal fun RoomHome(catalog:MusicCatalogue,onCatalogue:()->Unit,onMap:()->Unit,onPerformance:()->Unit) {
    val featured=remember(catalog) {
        val chosen=listOf("reused-mont-saint-michel-jay-1","reused-palaceside-jay-cover","jay-bedtime-2016-9a9f3e4e1893d933","eagles-original-cover-eden")
        (chosen.mapNotNull { id->catalog.releases.find{it.artwork?.id==id} }+catalog.releases).distinctBy{it.id}.take(4)
    }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("room-home")) {
        val unit=maxWidth/360f
        val canvasHeight=maxHeight.coerceAtLeast(unit*660f)
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().height(canvasHeight)) {
            RoomShelfWall(Modifier.matchParentSize())
            RoomHomeFurniture(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(unit*224f),onMap,onPerformance)
            RoomRecordRack(Modifier.offset(x=unit*11f,y=unit*14f).width(unit*338f).height(unit*354f),listOf(151f,336f))
            featured.forEachIndexed { index,release ->
                val x=if(index%2==0)31f else 188f
                val row=index/2
                RoomCover(release.artwork,"${release.title} · ${release.artist}",Modifier.offset(x=unit*x,y=unit*(if(row==0)23f else 208f)).size(unit*141f),sleeve=true)
            }
            // Whole rack is one accessible entrance, rather than misleading independent cover hotspots.
            Box(Modifier.offset(x=unit*11f,y=unit*14f).width(unit*338f).height(unit*354f)
                .clickable(role=Role.Button,onClickLabel="浏览完整唱片架",onClick=onCatalogue))
            SwissTextButton(onMap,Modifier.offset(x=unit*16.3f,y=canvasHeight-unit*272f).testTag("room-map-label")) {Text("地图与旅行",color=RoomColors.Ink,fontSize=18.sp);Icon(Icons.Outlined.ChevronRight,null)}
            SwissTextButton(onPerformance,Modifier.offset(x=unit*193.3f,y=canvasHeight-unit*272f).testTag("room-performance-label")) {Text("演奏",color=RoomColors.Ink,fontSize=18.sp);Icon(Icons.Outlined.ChevronRight,null)}
        }
        }
    }
}

@Composable internal fun RoomCatalogue(catalog:MusicCatalogue,onRelease:(MusicRelease)->Unit) {
    val state=rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize().testTag("room-catalogue"),state=state,contentPadding=PaddingValues(16.dp,10.dp,16.dp,32.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        items(catalog.releases.chunked(2),key={it.first().id}) { releases ->
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val unit=maxWidth/338f
                val cover=unit*141f
                Box(Modifier.fillMaxWidth().height(unit*170f)) {
                    RoomRecordRack(Modifier.matchParentSize(),listOf(148f))
                    releases.forEachIndexed { index,release ->
                        val x=unit*(20f+157f*index)
                        Box(Modifier.offset(x=x,y=unit*7f).size(cover)
                            .testTag("room-release-${release.id}").clickable(role=Role.Button,onClickLabel="打开${release.title}"){onRelease(release)}) {
                                RoomCover(release.artwork,"${release.title} · ${release.artist}",Modifier.size(cover),sleeve=true)
                        }
                    }
                }
            }
        }
    }
}

@Composable internal fun RoomWorkRow(work:MusicWork,releaseTitle:String,onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().testTag("room-work-${work.id}").clickable(role=Role.Button,onClick=onClick).padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
        RoomCover(work.artwork,work.title,Modifier.size(64.dp))
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
            Text(work.title,color=RoomColors.Ink,fontSize=17.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text(work.artist,color=RoomColors.Muted,fontSize=13.sp,maxLines=1)
            Text(releaseTitle,color=RoomColors.Muted,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
        Icon(Icons.Outlined.ChevronRight,null,tint=RoomColors.Ink)
    }
}

@Composable internal fun RoomAlbum(release:MusicRelease,favorite:Boolean,onFavorite:()->Unit,onWork:(MusicWork)->Unit,onPlace:(String)->Unit,onBack:()->Unit={},onSearch:()->Unit={}) {
    SwissReleaseDetail(release,favorite,onFavorite,onWork,onPlace,onBack,onSearch)
}
@Composable internal fun RoomSearch(catalog:MusicCatalogue,initialQuery:String="",onWork:(MusicWork)->Unit) {
    var query by rememberSaveable(initialQuery) { mutableStateOf(initialQuery) }
    val requester=remember { FocusRequester() }
    val keyboard=LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { requester.requestFocus() }
    val hits=remember(catalog,query){catalog.search(query)}
    Column(Modifier.fillMaxSize().testTag("room-search")) {
        OutlinedTextField(query,{query=it},Modifier.fillMaxWidth().padding(horizontal=24.dp).focusRequester(requester).testTag("room-search-input"),
            placeholder={Text("歌名、歌手或专辑")},leadingIcon={Icon(Icons.Outlined.Search,null)},
            trailingIcon={if(query.isNotEmpty())IconButton({query=""}){Icon(Icons.Outlined.Close,"清空搜索")}},
            singleLine=true,shape=androidx.compose.ui.graphics.RectangleShape,keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),
            keyboardActions=KeyboardActions(onSearch={keyboard?.hide()}),colors=OutlinedTextFieldDefaults.colors(focusedContainerColor=RoomColors.Paper,unfocusedContainerColor=RoomColors.Paper))
        LazyColumn(Modifier.fillMaxSize().imePadding(),state=rememberLazyListState(),contentPadding=PaddingValues(24.dp,24.dp,24.dp,24.dp)) {
            if(query.isNotBlank())item { Text("歌曲",fontSize=19.sp,fontWeight=FontWeight.Medium,color=RoomColors.Ink);Spacer(Modifier.height(12.dp)) }
            if(query.isNotBlank()&&hits.isEmpty()) item { Text("没有找到相关歌曲，试试其他关键词。",color=RoomColors.Muted) }
            items(hits,key={it.id}) { work ->
                RoomWorkRow(work,catalog.releases.find{it.id==work.releaseId}?.title.orEmpty()) {keyboard?.hide();onWork(work)}
                HorizontalDivider(color=RoomColors.Line)
            }
        }
    }
}

@Composable internal fun RoomSong(work:MusicWork,releaseTitle:String,favorite:Boolean,onFavorite:()->Unit,onPlace:(String)->Unit,onPlay:(()->Unit)?,onBack:()->Unit={},onRelease:()->Unit={}) {
    SwissWorkDetail(work,releaseTitle,favorite,onFavorite,onPlace,onBack,onRelease)
}
@Composable private fun RoomPlaceCard(link:MusicPlaceLink,onPlace:(String)->Unit) {
    val context=LocalContext.current
    val heading=remember(link.place){link.place.heading()}
    val scene=link.scenes.firstOrNull{it.imageApproved}
    RoomPaper(Modifier.fillMaxWidth().padding(bottom=6.dp).clickable(role=Role.Button){onPlace(link.place.id)}.testTag("room-place-${link.place.id}"),woodEdge=true) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement=Arrangement.spacedBy(14.dp),verticalAlignment=Alignment.CenterVertically) {
                if(scene!=null)RoomReference(scene,"${link.place.name} · ${scene.kindLabel}",Modifier.width(122.dp).height(76.dp))
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                    Text(heading.title,Modifier.clearAndSetSemantics{contentDescription=link.place.name},color=RoomColors.Ink,fontSize=17.sp,fontWeight=FontWeight.Medium)
                    if(heading.qualifier.isNotBlank())Text(heading.qualifier,color=RoomColors.Muted,fontSize=13.sp,lineHeight=19.sp)
                    Text("${link.place.city} · ${link.place.country}",color=RoomColors.Muted,fontSize=12.sp)
                }
                Icon(Icons.Outlined.ChevronRight,null,tint=RoomColors.Ink)
            }
            Text(link.scenes.map{it.kindLabel}.distinct().joinToString(" · "),color=RoomColors.Navy,fontSize=12.sp)
            Text(link.scenes.firstOrNull()?.let{SceneSummaries.forScene(context,it)}?.ifBlank{link.place.description} ?: link.place.description,color=RoomColors.Muted,fontSize=13.sp,lineHeight=20.sp)
            if(link.scenes.any{it.referenceKind=="release_cover"&&it.kind in setOf("lyric","song_title")})Text("封面仅用于识别作品",color=RoomColors.Muted,fontSize=12.sp)
        }
    }
}
