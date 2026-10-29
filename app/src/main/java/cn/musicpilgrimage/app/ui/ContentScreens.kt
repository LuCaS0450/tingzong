package cn.musicpilgrimage.app.ui

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import cn.musicpilgrimage.app.*
import cn.musicpilgrimage.app.data.*
import cn.musicpilgrimage.app.design.BauhausDisclosure
import coil.compose.AsyncImage
import org.json.JSONObject

@Composable internal fun ContentCard(item:ContentItem,session:SessionViewModel,vm:FeatureViewModel,onEdit:(ContentItem)->Unit,onProfile:(String)->Unit) {
 val owner by session.signedIn.collectAsState();val p by session.profile.collectAsState();val busy by vm.busy.collectAsState()
 var menu by remember{mutableStateOf(false)};var report by remember{mutableStateOf(false)};var remove by remember{mutableStateOf(false)}
 val active=p?.optString("id")==owner&&p?.optString("status")=="active"
 LaunchedEffect(owner){menu=false;report=false;remove=false}
 Column(Modifier.fillMaxWidth().padding(vertical=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
  Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
   Avatar(item.nickname,item.owner)
   Column(Modifier.weight(1f).clickable(enabled=item.owner.isNotBlank()){onProfile(item.owner)}){Text(item.nickname,fontWeight=FontWeight.Bold);Text(if(item.kind=="recreation")"作者曾到访 · ${item.date}" else item.date,color=Muted,style=MaterialTheme.typography.bodySmall)}
   Box {IconButton({menu=true}){Icon(Icons.Rounded.MoreHoriz,"内容操作")};DropdownMenu(menu,{menu=false}) {
    if(item.owner==owner){
     if(active && !item.hidden)DropdownMenuItem(text={Text("编辑")},onClick={menu=false;onEdit(item)})
     if(item.kind in listOf("visit","recreation") && item.visibility=="public")DropdownMenuItem(text={Text("设为私密")},onClick={menu=false;vm.mutation("make_private",JSONObject().put("id",item.id))})
     DropdownMenuItem(text={Text(if(item.kind=="visit")"删除感想与照片" else "删除")},onClick={menu=false;remove=true})
    }
    if(active && item.visibility=="public" && !item.hidden)DropdownMenuItem(text={Text("举报")},onClick={menu=false;report=true})
   }}
  }
  if(item.hidden)ErrorNotice("此条内容已被管理员隐藏，仅你能在这里管理。")
  item.mediaId?.let{PrivatePhoto(it,session.services,Modifier.fillMaxWidth().aspectRatio(4f/3f))}
  if(item.body.isNotBlank())Text(item.body)
  if(item.song.isNotBlank())Surface(color=Panel,shape=RoundedCornerShape(5.dp)){Row(Modifier.padding(12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){Icon(Icons.Rounded.MusicNote,null,tint=Acid);Text("${item.song} · ${item.artist}",style=MaterialTheme.typography.bodySmall)}}
  Row(verticalAlignment=Alignment.CenterVertically){Text(item.placeName,Modifier.weight(1f),color=Muted,style=MaterialTheme.typography.bodySmall)
   if(item.visibility=="private")Text("仅自己可见",color=Muted,style=MaterialTheme.typography.bodySmall)
   else {ToggleIcon(item.liked,if(item.liked)Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,"${if(item.liked)"取消点赞" else "点赞"}，${item.likes}次",enabled=active&&!busy&&!item.hidden){vm.mutation("like",JSONObject().put("id",item.id).put("enabled",!item.liked))};Text("${item.likes}",color=Muted,style=MaterialTheme.typography.bodySmall)}
  }
 };HorizontalDivider(color=Line)
 if(remove)AlertDialog(onDismissRequest={remove=false},title={Text("确认删除？")},text={Text(if(item.kind=="visit")"感想和照片会被删除，曾到访的认证仍然保留。" else "删除后无法恢复，已经获得的历史徽章仍会保留。")},confirmButton={TextButton({remove=false;vm.mutation("delete_content",JSONObject().put("id",item.id))}){Text("删除")}},dismissButton={TextButton({remove=false}){Text("取消")}})
 if(report)AlertDialog(onDismissRequest={report=false},title={Text("举报内容")},text={Column{listOf("垃圾内容","不友善内容","色情 / 违法","版权问题","信息错误","其他").forEach{reason->TextButton({report=false;vm.mutation("report",JSONObject().put("id",item.id).put("reason",reason)){vm.notice.value="举报已提交"}}){Text(reason)}}}},confirmButton={},dismissButton={TextButton({report=false}){Text("取消")}})
}

@Composable internal fun PlaceScreen(id:String,vm:PlaceViewModel,session:SessionViewModel,onBack:()->Unit,onLogin:()->Unit,onForm:(String,String,ContentItem?)->Unit,onProfile:(String)->Unit,showHeader:Boolean=true) {
 val catalog by session.services.catalog.collectAsState();val place=catalog.places.find{it.id==id}
 val owner by session.signedIn.collectAsState();val profile by session.profile.collectAsState()
 val revision by session.services.changed.collectAsState();val rows by vm.items.collectAsState();val ownRows by vm.ownItems.collectAsState()
 LaunchedEffect(id,owner,revision){vm.load(id)}
 val favorite=profile?.optString("id")==owner&&profile?.optJSONArray("favorites")?.strings()?.contains(id)==true
 val active=profile?.optString("id")==owner&&profile?.optString("status")=="active"
 val communityExpanded=rememberSaveable(id){mutableStateOf(false)}
 val recordsExpanded=rememberSaveable(id){mutableStateOf(false)}
 Column(Modifier.fillMaxSize()) {
  if(showHeader)BackBar("地点",onBack)
  if(place==null){Box(Modifier.padding(20.dp)){StatusCard("地点暂不可用","返回探索重试。")};return@Column}
  LazyColumn(contentPadding=PaddingValues(start=20.dp,end=20.dp,top=12.dp,bottom=32.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
   item {
    Text("${place.city} · ${place.country}",color=Muted,style=MaterialTheme.typography.bodySmall)
    Row(verticalAlignment=Alignment.CenterVertically){
     Text(place.name,style=MaterialTheme.typography.headlineLarge,modifier=Modifier.weight(1f))
     ToggleIcon(favorite,if(favorite)Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,if(favorite)"取消收藏" else "收藏地点"){if(owner==null)onLogin() else session.mutation("favorite",JSONObject().put("location_id",id).put("enabled",!favorite))}
    }
    ExternalLink("打开地图","geo:${place.latitude},${place.longitude}?q=${place.latitude},${place.longitude}(${Uri.encode(place.name)})")
    BauhausDisclosure("地点资料") {
     if(place.description.isNotBlank())Text(place.description)
     Text("${mapOf("landmark" to "地标","exact" to "精确地点","approximate" to "大致范围")[place.precision]?:"参考位置"} · 到访范围 ${place.radiusMeters} 米",style=MaterialTheme.typography.bodySmall,color=Muted)
     if(place.coordinateSource.startsWith("http"))ExternalLink("坐标来源",place.coordinateSource)
     else if(place.coordinateSource.isNotBlank())Text(place.coordinateSource,style=MaterialTheme.typography.bodySmall,color=Muted)
    }
   }
   items(place.scenes,key={it.id}){scene->
    var imageReady by remember(scene.id,scene.imageUrl){mutableStateOf(false)}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
     Text(formatWorkTitle(scene.work),style=MaterialTheme.typography.titleLarge)
     Text("${scene.artistName} · ${scene.kindLabel}",color=Muted,style=MaterialTheme.typography.bodySmall)
     ReferenceImage(scene.imageUrl.takeIf{scene.imageApproved},"${scene.work}原作",Modifier.fillMaxWidth().aspectRatio(scene.aspectRatio),onReady={imageReady=it})
     if(scene.arrivalAnchorNote.isNotBlank())Text(scene.arrivalAnchorNote,style=MaterialTheme.typography.bodySmall,color=Acid)
     if(scene.canRecreate)OutlinedButton({if(owner==null)onLogin() else onForm("recreation",scene.id,ownRows.find{it.sceneId==scene.id&&it.kind=="recreation"})},enabled=imageReady&&(owner==null||active)) {Text(if(imageReady)"复刻镜头" else "加载原图…")}
     BauhausDisclosure("作品资料与来源") {
      scene.releaseYear?.let{Text("$it",style=MaterialTheme.typography.bodySmall,color=Muted)}
      if(scene.associationExcerpt.isNotBlank())Text(scene.associationExcerpt)
      if(scene.note.isNotBlank())Text(scene.note)
      if(!scene.canRecreate)Text("尚无可复刻的具体机位。",style=MaterialTheme.typography.bodySmall,color=Muted)
      scene.timestampSeconds?.let{Text("${it/60}:${(it%60).toString().padStart(2,'0')}",style=MaterialTheme.typography.bodySmall)}
      scene.imageCredit?.takeIf{it.isNotBlank()}?.let{Text(it,style=MaterialTheme.typography.bodySmall,color=Muted)}
      if(scene.sourceUrl.isNotBlank())ExternalLink("作品来源",scene.sourceUrl)
      if(scene.canonicalWorkUrl.isNotBlank()&&scene.canonicalWorkUrl!=scene.sourceUrl)ExternalLink("原作版本",scene.canonicalWorkUrl)
     }
    }
   }
   item {
    BauhausDisclosure("社区",expandedState=communityExpanded) {
     ViewModelStatus(vm)
     if(owner==null)LoginGate(onLogin) else {
      SectionTitle("留言","写留言"){onForm("message",id,ownRows.find{it.kind=="message"})}
      if(rows.isEmpty())Text("暂无公开内容",color=Muted)
      rows.forEach{ContentCard(it,session,vm,{c->onForm(c.kind,if(c.kind=="recreation")c.sceneId else id,c)},onProfile)}
      if(rows.size>=30)TextButton({vm.load(id,true)}){Text("加载更多")}
     }
    }
   }
   item {
    BauhausDisclosure("我的记录",expandedState=recordsExpanded) {
     if(owner==null)LoginGate(onLogin) else {
      OutlinedButton({onForm("visit",id,ownRows.find{it.kind=="visit"})},enabled=active){Text("记录到访")}
      ownRows.forEach{ContentCard(it,session,vm,{c->onForm(c.kind,if(c.kind=="recreation")c.sceneId else id,c)},onProfile)}
      OutlinedButton({onForm("correction",id,null)},enabled=active){Text("修正地点资料")}
     }
    }
   }
  }
 }
}

@Composable internal fun PersonalContentScreen(kind:String,vm:PersonalViewModel,session:SessionViewModel,onBack:()->Unit,onForm:(String,String,ContentItem?)->Unit,onProfile:(String)->Unit) {
 val rows by vm.items.collectAsState();val revision by session.services.changed.collectAsState()
 val busy by vm.busy.collectAsState();val error by vm.error.collectAsState()
 LaunchedEffect(kind,revision){vm.load(kind)}
 Column {BackBar(mapOf("message" to "我的留言","visit" to "我的巡礼","recreation" to "我的复刻")[kind].orEmpty(),onBack)
  LazyColumn(contentPadding=PaddingValues(20.dp)){
   item{ViewModelStatus(vm);if(error!=null)OutlinedButton({vm.load(kind)}){Text("重试")};if(rows.isEmpty()&&!busy&&error==null)StatusCard("暂无记录")}
   items(rows,key={it.id}){ContentCard(it,session,vm,{c->onForm(c.kind,if(c.kind=="recreation")c.sceneId else c.location.ifBlank{"new"},c)},onProfile)}
   if(rows.size>=30)item{TextButton({vm.load(kind,true)}){Text("加载更多")}}
  }
 }
}

@Composable internal fun PublicProfileScreen(id:String,vm:PersonalViewModel,session:SessionViewModel,onBack:()->Unit,onForm:(String,String,ContentItem?)->Unit,onProfile:(String)->Unit) {
 val p by vm.publicProfile.collectAsState();val revision by session.services.changed.collectAsState()
 val busy by vm.busy.collectAsState();val error by vm.error.collectAsState()
 LaunchedEffect(id,revision){vm.profile(id)}
 Column {BackBar("公开主页",onBack);LazyColumn(contentPadding=PaddingValues(20.dp)){
  item{ViewModelStatus(vm);if(error!=null)OutlinedButton({vm.profile(id)}){Text("重试")};if(p==null&&!busy&&error==null)StatusCard("主页暂不可用");p?.let{profile->Avatar(profile.optString("nickname"),id,Modifier.size(80.dp));Spacer(Modifier.height(16.dp));Text(profile.optString("nickname"),style=MaterialTheme.typography.headlineLarge);if(profile.optString("bio").isNotBlank())Text(profile.optString("bio"),color=Muted);Text("${profile.optInt("visit_count")} 次到访 · ${profile.optInt("recreation_count")} 张公开复刻",Modifier.padding(vertical=16.dp),color=Muted);SectionTitle("公开复刻");if(profile.optJSONArray("recreations")?.length()==0)Text("暂无公开复刻",color=Muted)}}
  items(p?.optJSONArray("recreations")?.objects()?.map(::ContentItem).orEmpty(),key={it.id}){ContentCard(it,session,vm,{c->onForm(c.kind,c.sceneId,c)},onProfile)}
 }}
}
