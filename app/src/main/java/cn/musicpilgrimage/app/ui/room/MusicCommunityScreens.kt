package cn.musicpilgrimage.app.ui.room

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.musicpilgrimage.app.*
import cn.musicpilgrimage.app.R
import cn.musicpilgrimage.app.data.*
import cn.musicpilgrimage.app.ui.LocalPhysicalMotion
import cn.musicpilgrimage.app.ui.ViewModelStatus
import org.json.JSONObject

@Composable internal fun RoomPlaceCommunity(
    place:Place,vm:PlaceViewModel,session:SessionViewModel,onLogin:()->Unit,
    onForm:(String,String,ContentItem?)->Unit,onProfile:(String)->Unit,
    archive:@Composable ()->Unit
) {
    val owner by session.signedIn.collectAsState()
    val revision by session.services.changed.collectAsState()
    val rows by vm.items.collectAsState()
    val own by vm.ownItems.collectAsState()
    val loaded by vm.loaded.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    val notice by vm.notice.collectAsState()
    var tab by rememberSaveable(place.id){mutableIntStateOf(0)}
    LaunchedEffect(place.id,owner,revision){vm.load(place.id)}
    val visit=own.firstOrNull{it.kind=="visit"&&it.owner==owner}
    RoomCommunityFeedContent(owner,rows,loaded,busy,error,notice,tab,{tab=it},
        {onForm("message",place.id,own.firstOrNull{it.kind=="message"&&it.owner==owner})},
        visit,{onForm("visit",place.id,it)},onLogin,{vm.load(place.id,loaded&&error==null)},archive) {row->
        RoomContentCard(row,session,vm,{onForm(it.kind,if(it.kind=="recreation")it.sceneId else place.id,it)},onProfile)
    }
}

@Composable internal fun RoomCommunityFeedContent(
    owner:String?,rows:List<ContentItem>,loaded:Boolean,busy:Boolean,error:String?,notice:String?,tab:Int,onTab:(Int)->Unit,
    onMessage:()->Unit,visit:ContentItem?,onVisit:(ContentItem?)->Unit,onLogin:()->Unit,onMore:()->Unit,
    archive:@Composable ()->Unit,card:@Composable (ContentItem)->Unit
) {
    val list=rememberLazyListState()
    val motion=LocalPhysicalMotion.current
    val filtered=rows.filter{RecordDraftRules.publiclyVisible(it)&&(if(tab==0)it.kind=="message"else it.kind in listOf("visit","recreation"))}.sortedByDescending{it.raw.optString("created_at")}
    val inset=Modifier.padding(horizontal=25.dp)
    LazyColumn(Modifier.fillMaxSize().testTag("room-community"),state=list,contentPadding=PaddingValues(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item("archive"){archive()}
        item("community-heading") {
            Column(Modifier.fillMaxWidth().then(inset)) {
                HorizontalDivider(color = RoomColors.Line)
                PlaceCommunityHeading(owner != null, loaded && !busy, onMessage, visit, onVisit)
                if (owner != null) PlaceCommunityTabs(tab, onTab)
            }
        }
        if(owner==null)item("login"){SwissTextButton(onLogin,inset){Text("登录后查看社区")}}
        else {
            if ((!loaded && busy) || error != null || notice != null) item("status") {
                Column(inset, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!loaded && busy) Text("正在读取社区内容…", color = RoomColors.Muted, fontSize = 12.sp)
                    if (error != null) {
                        Text("暂时无法更新社区内容，请重试。", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                        SwissTextButton(onMore) { Text("重试") }
                    }
                    if (notice != null) Text(notice, color = RoomColors.Navy, fontSize = 13.sp)
                }
            }
            if (loaded && error == null && filtered.isEmpty()) item("empty") {
                Text("这里还没有公开内容。", inset, fontSize = 14.sp, color = RoomColors.Muted)
            }
            items(filtered,key={it.id}){row->Box(Modifier.animateItem(fadeInSpec=tween(if(motion)140 else 0),fadeOutSpec=tween(if(motion)140 else 0),placementSpec=null).then(inset)){card(row)}}
            if(rows.size>=30)item("more"){SwissTextButton(onMore,inset){Text("加载更多")}}
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaceCommunityHeading(
    showActions: Boolean,
    actionsEnabled: Boolean,
    onMessage: () -> Unit,
    visit: ContentItem?,
    onVisit: (ContentItem?) -> Unit,
) {
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            "地点社区",
            Modifier.align(Alignment.CenterVertically).padding(vertical = 8.dp).semantics { heading() },
            color = RoomColors.Ink,
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
        )
        if (showActions) Row(Modifier.align(Alignment.CenterVertically)) {
            val colors = ButtonDefaults.textButtonColors(contentColor = RoomColors.Ink)
            val padding = PaddingValues(horizontal = 8.dp)
            val iconColor = if (actionsEnabled) RoomColors.Red else RoomColors.Muted.copy(alpha = .55f)
            SwissTextButton(
                onMessage, Modifier.heightIn(min = 48.dp).testTag("place-community-message"),
                enabled = actionsEnabled, colors = colors, contentPadding = padding,
            ) {
                Icon(painterResource(R.drawable.ic_place_community_message), null, Modifier.size(20.dp), tint = iconColor)
                Spacer(Modifier.width(5.dp))
                Text("留言")
            }
            SwissTextButton(
                { onVisit(visit) },
                Modifier.heightIn(min = 48.dp).testTag("place-community-visit")
                    .semantics { if (visit != null) stateDescription = "已记录" },
                enabled = actionsEnabled, colors = colors, contentPadding = padding,
            ) {
                Icon(painterResource(R.drawable.ic_place_community_visit), null, Modifier.size(20.dp), tint = iconColor)
                Spacer(Modifier.width(5.dp))
                Text("到访")
            }
        }
    }
}

@Composable
private fun PlaceCommunityTabs(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectableGroup().drawBehind {
            val stroke = 1.dp.toPx()
            val baseline = size.height - stroke / 2f
            drawLine(RoomColors.Line, Offset(0f, baseline), Offset(size.width, baseline), stroke)
        },
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        listOf("留言", "到访与复刻").forEachIndexed { index, label ->
            val active = selected == index
            Box(
                Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .selectable(active, role = Role.Tab) { onSelect(index) }
                    .drawBehind {
                        if (active) {
                            val stroke = 2.dp.toPx()
                            val baseline = size.height - stroke / 2f
                            drawLine(RoomColors.Red, Offset(0f, baseline), Offset(size.width, baseline), stroke)
                        }
                    }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    label,
                    color = if (active) RoomColors.Ink else RoomColors.Muted,
                    fontSize = 14.sp,
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable internal fun RoomCommunityTabs(labels:List<String>,selected:Int,onSelect:(Int)->Unit,modifier:Modifier=Modifier) {
    Row(modifier,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed{index,label->SwissTextButton({onSelect(index)},Modifier.semantics{this.selected=index==selected},contentPadding=PaddingValues(horizontal=6.dp)) {
            Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(label,color=if(selected==index)RoomColors.Navy else RoomColors.Muted,fontSize=13.sp)
                HorizontalDivider(Modifier.width(if(label.length>4)72.dp else 40.dp),color=if(selected==index)RoomColors.Navy else androidx.compose.ui.graphics.Color.Transparent,thickness=2.dp)
            }
        }}
    }
}

@Composable internal fun RoomProfileScreen(
    id:String,vm:PersonalViewModel,session:SessionViewModel,onForm:(String,String,ContentItem?)->Unit,onProfile:(String)->Unit,
    ownerName:String?=null,ownerAvatar:String?=null,ownerManagement:(@Composable ()->Unit)?=null
) {
    val profile by vm.publicProfile.collectAsState()
    val revision by session.services.changed.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    LaunchedEffect(id,revision){vm.profile(id)}
    key(id) {
        RoomProfileContent(profile?.takeIf{it.optString("id")==id},busy,error,{vm.profile(id)},ownerName,ownerAvatar,ownerManagement) {item->
            RoomContentCard(item,session,vm,{onForm(it.kind,if(it.kind=="recreation")it.sceneId else it.location,it)},onProfile,showAuthor=false)
        }
    }
}

@Composable internal fun RoomProfileContent(
    profile:JSONObject?,busy:Boolean,error:String?,onRetry:()->Unit,
    ownerName:String?=null,ownerAvatar:String?=null,ownerManagement:(@Composable ()->Unit)?=null,
    card:@Composable (ContentItem)->Unit
) {
    var tab by rememberSaveable {mutableIntStateOf(1)}
    val list=rememberLazyListState()
    val rows=profile?.optJSONArray(if(tab==1)"recreations"else "visits")?.objects()?.map(::ContentItem)
        ?.filter{RecordDraftRules.publiclyVisible(it)}?.sortedByDescending{it.raw.optString("created_at")}.orEmpty()
    val name=ownerName?:profile?.optString("nickname","听众")
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("room-profile"),state=list,
            contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            if(name!=null)item("identity") {
                RoomPaper(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                        if(ownerName!=null)RoomAccountAvatar(ownerAvatar,Modifier.size(54.dp))
                        else RoomCommunityAvatar(name,Modifier.size(54.dp))
                        Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            Text(name,fontSize=22.sp,color=RoomColors.Ink,modifier=Modifier.testTag("account-nickname"))
                            val bio=profile?.optString("bio").orEmpty()
                            if(bio.isNotBlank())Text(bio,fontSize=13.sp,color=RoomColors.Muted)
                        }
                    }
                }
            }
            if(profile!=null)item("tabs"){RoomCommunityTabs(listOf("公开到访","复刻"),tab,{tab=it})}
            if(busy)item("busy"){LinearProgressIndicator(Modifier.fillMaxWidth(),color=RoomColors.Navy)}
            if(error!=null)item("error"){Column{Text(error,color=MaterialTheme.colorScheme.error,fontSize=13.sp);SwissTextButton(onRetry){Text("重试")}}}
            if(!busy&&error==null&&profile==null)item("missing"){Text("暂时无法读取公开记录。",color=RoomColors.Muted)}
            if(!busy&&error==null&&profile!=null&&rows.isEmpty())item("empty") {
                if(tab==0&&!profile.has("visits"))Column{Text("暂时无法读取公开到访记录，请稍后重试。",color=RoomColors.Muted,fontSize=13.sp);SwissTextButton(onRetry){Text("重试")}}
                else Text(if(tab==0)"还没有公开到访。"else "还没有公开复刻。",color=RoomColors.Muted,fontSize=14.sp)
            }
            items(rows,key={it.id}){card(it)}
        }
        ownerManagement?.invoke()
    }
}

@Composable internal fun RoomPersonalContentScreen(kind:String,vm:PersonalViewModel,session:SessionViewModel,onForm:(String,String,ContentItem?)->Unit,onProfile:(String)->Unit) {
    val owner by session.signedIn.collectAsState()
    val account=owner ?: return
    val load by vm.records.collectAsState()
    val current=load?.takeIf{it.owner==account&&it.kind==kind}
    val page=current?.page ?: vm.cachedRecords(account,kind)
    val rows=page?.rows.orEmpty()
    val revision by session.services.changed.collectAsState()
    val list=rememberLazyListState()
    LaunchedEffect(account,kind,revision){vm.refreshRecords(kind)}
    LazyColumn(Modifier.fillMaxSize().testTag("room-personal-records"),state=list,
        contentPadding=PaddingValues(31.dp,20.dp,31.dp,88.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item("status"){Column {
            ViewModelStatus(vm)
            if(current?.error!=null){
                Text("暂时无法更新记录。",fontSize=13.sp,color=RoomColors.Muted)
                SwissTextButton({vm.refreshRecords(kind,force=true)}){Text("重试")}
            }
            if(page==null&&current?.error==null)Text("正在读取记录…",fontSize=13.sp,color=RoomColors.Muted)
            if(page!=null&&rows.isEmpty())Text(if(kind=="message")"还没有留言。" else "还没有复刻记录。",
                fontSize=14.sp,lineHeight=23.sp,color=RoomColors.Muted)
        }}
        items(rows,key={it.id}){item->RoomContentCard(item,session,vm,{onForm(it.kind,if(it.kind=="recreation")it.sceneId else it.location,it)},onProfile)}
        if(page?.nextOffset!=null)item("more"){SwissTextButton({vm.refreshRecords(kind,more=true)},enabled=current?.loading!=true){Text("加载更多")}}
    }
}
