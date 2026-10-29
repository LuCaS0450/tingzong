package cn.musicpilgrimage.app.ui.room

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.musicpilgrimage.app.*
import cn.musicpilgrimage.app.data.*
import cn.musicpilgrimage.app.ui.LocalPhysicalMotion
import cn.musicpilgrimage.app.ui.ViewModelStatus
import org.json.JSONObject

@Composable internal fun RoomPlaceCommunity(place:Place,vm:PlaceViewModel,session:SessionViewModel,onLogin:()->Unit,onForm:(String,String,ContentItem?)->Unit,onProfile:(String)->Unit,onMine:()->Unit={}) {
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
    RoomCommunityFeedContent(place,owner,rows,loaded,busy,error,notice,tab,{tab=it},
        {onForm("message",place.id,own.find{it.kind=="message"})},{onForm("visit",place.id,null)},onMine,onLogin,{vm.load(place.id,loaded&&error==null)}) {row->
        RoomContentCard(row,session,vm,{onForm(it.kind,if(it.kind=="recreation")it.sceneId else place.id,it)},onProfile)
    }
}

@Composable internal fun RoomCommunityFeedContent(
    place:Place,owner:String?,rows:List<ContentItem>,loaded:Boolean,busy:Boolean,error:String?,notice:String?,tab:Int,onTab:(Int)->Unit,
    onMessage:()->Unit,onVisit:()->Unit,onMine:()->Unit,onLogin:()->Unit,onMore:()->Unit,card:@Composable (ContentItem)->Unit
) {
    val list=rememberLazyListState()
    val motion=LocalPhysicalMotion.current
    val filtered=rows.filter{RecordDraftRules.publiclyVisible(it)&&(if(tab==0)it.kind=="message"else it.kind in listOf("visit","recreation"))}.sortedByDescending{it.raw.optString("created_at")}
    val heading=remember(place){place.heading()}
    LazyColumn(Modifier.fillMaxSize().testTag("room-community"),state=list,contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item("place"){RoomPaper(Modifier.fillMaxWidth(),woodEdge=true){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
            Text("${place.city} · ${place.country}",fontSize=12.sp,color=RoomColors.Muted)
            Text(heading.title,Modifier.clearAndSetSemantics{contentDescription=place.name},fontSize=22.sp,fontWeight=FontWeight.Medium,color=RoomColors.Ink)
            if(heading.qualifier.isNotBlank())Text(heading.qualifier,fontSize=14.sp,lineHeight=21.sp,color=RoomColors.Muted)
        }}}
        if(owner==null)item("login"){SwissTextButton(onLogin){Text("登录后查看社区")}}
        else {
            item("publish"){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                RoomPaper(Modifier.weight(1f)){SwissOutlinedButton(onMessage,Modifier.fillMaxWidth().heightIn(min=48.dp),shape=androidx.compose.ui.graphics.RectangleShape,colors=ButtonDefaults.outlinedButtonColors(contentColor=RoomColors.Navy)){Text("写留言")}}
                RoomPaper(Modifier.weight(1f)){SwissOutlinedButton(onVisit,Modifier.fillMaxWidth().heightIn(min=48.dp),shape=androidx.compose.ui.graphics.RectangleShape,colors=ButtonDefaults.outlinedButtonColors(contentColor=RoomColors.Navy)){Text("记录到访")}}
            }}
            item("tabs"){Row(verticalAlignment=Alignment.CenterVertically) {
                RoomCommunityTabs(listOf("留言","到访与复刻"),tab,onTab,Modifier.weight(1f))
                SwissTextButton(onMine){Text("我的记录",fontSize=12.sp)}
            }}
            item("status"){Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("最新发布在前",color=RoomColors.Muted,fontSize=12.sp)
                if(busy)LinearProgressIndicator(Modifier.fillMaxWidth(),color=RoomColors.Navy)
                if(error!=null){Text(error,color=MaterialTheme.colorScheme.error,fontSize=13.sp);SwissTextButton(onMore){Text("重试")}}
                if(notice!=null)Text(notice,color=RoomColors.Navy,fontSize=13.sp)
            }}
            if(loaded&&filtered.isEmpty())item("empty"){Text("这里还没有公开内容。",fontSize=14.sp,color=RoomColors.Muted)}
            items(filtered,key={it.id}){row->Box(Modifier.animateItem(fadeInSpec=tween(if(motion)140 else 0),fadeOutSpec=tween(if(motion)140 else 0),placementSpec=null)){card(row)}}
            if(rows.size>=30)item("more"){SwissTextButton(onMore){Text("加载更多")}}
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

@Composable internal fun RoomPublicProfileScreen(id:String,vm:PersonalViewModel,session:SessionViewModel,onForm:(String,String,ContentItem?)->Unit,onProfile:(String)->Unit) {
    val profile by vm.publicProfile.collectAsState()
    val revision by session.services.changed.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    LaunchedEffect(id,revision){vm.profile(id)}
    RoomPublicProfileContent(profile,busy,error,{vm.profile(id)}) {item->RoomContentCard(item,session,vm,{onForm(it.kind,if(it.kind=="recreation")it.sceneId else it.location,it)},onProfile,showAuthor=false)}
}

@Composable internal fun RoomPublicProfileContent(profile:JSONObject?,busy:Boolean,error:String?,onRetry:()->Unit,card:@Composable (ContentItem)->Unit) {
    var tab by rememberSaveable(profile?.optString("id").orEmpty()){mutableIntStateOf(1)}
    val list=rememberLazyListState()
    val rows=profile?.optJSONArray(if(tab==1)"recreations"else "visits")?.objects()?.map(::ContentItem)
        ?.filter{RecordDraftRules.publiclyVisible(it)}?.sortedByDescending{it.raw.optString("created_at")}.orEmpty()
    LazyColumn(Modifier.fillMaxSize().testTag("room-public-profile"),state=list,contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        profile?.let{p->
            item("identity"){RoomPaper(Modifier.fillMaxWidth()){Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                RoomCommunityAvatar(p.optString("nickname"),Modifier.size(54.dp))
                Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(p.optString("nickname","听众"),fontSize=22.sp,color=RoomColors.Ink)
                    Text("公开主页",fontSize=12.sp,color=RoomColors.Muted)
                    if(p.optString("bio").isNotBlank())Text(p.optString("bio"),fontSize=13.sp,color=RoomColors.Muted)
                }
            }}}
            item("tabs"){RoomCommunityTabs(listOf("公开到访","复刻"),tab,{tab=it})}
        }
        if(busy)item("busy"){LinearProgressIndicator(Modifier.fillMaxWidth(),color=RoomColors.Navy)}
        if(error!=null)item("error"){Column{Text(error,color=MaterialTheme.colorScheme.error,fontSize=13.sp);SwissTextButton(onRetry){Text("重试")}}}
        if(!busy&&error==null&&profile==null)item("missing"){Text("主页暂不可用。",color=RoomColors.Muted)}
        if(!busy&&error==null&&profile!=null&&rows.isEmpty())item("empty") {
            if(tab==0&&!profile.has("visits"))Column{Text("暂时无法读取公开到访记录，请稍后重试。",color=RoomColors.Muted,fontSize=13.sp);SwissTextButton(onRetry){Text("重试")}}
            else Text(if(tab==0)"还没有公开到访。"else "还没有公开复刻。",color=RoomColors.Muted,fontSize=14.sp)
        }
        items(rows,key={it.id}){card(it)}
    }
}

@Composable internal fun RoomPersonalContentScreen(kind:String,vm:PersonalViewModel,session:SessionViewModel,onForm:(String,String,ContentItem?)->Unit,onProfile:(String)->Unit) {
    val rows by vm.items.collectAsState()
    val revision by session.services.changed.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    val list=rememberLazyListState()
    LaunchedEffect(kind,revision){vm.load(kind)}
    LazyColumn(Modifier.fillMaxSize().testTag("room-personal-records"),state=list,contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item("status"){Column{ViewModelStatus(vm);if(error!=null)SwissTextButton({vm.load(kind)}){Text("重试")};if(rows.isEmpty()&&!busy&&error==null)Text("还没有记录。",color=RoomColors.Muted)}}
        items(rows,key={it.id}){item->RoomContentCard(item,session,vm,{onForm(it.kind,if(it.kind=="recreation")it.sceneId else it.location,it)},onProfile)}
        if(rows.size>=30)item("more"){SwissTextButton({vm.load(kind,true)}){Text("加载更多")}}
    }
}
