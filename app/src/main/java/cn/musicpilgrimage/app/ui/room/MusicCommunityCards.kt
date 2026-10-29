package cn.musicpilgrimage.app.ui.room

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.musicpilgrimage.app.FeatureViewModel
import cn.musicpilgrimage.app.SessionViewModel
import cn.musicpilgrimage.app.data.*
import cn.musicpilgrimage.app.ui.LocalPhysicalMotion
import org.json.JSONObject

@Composable internal fun RoomContentCard(item:ContentItem,session:SessionViewModel,vm:FeatureViewModel,onEdit:(ContentItem)->Unit,onProfile:(String)->Unit,showAuthor:Boolean=true) {
    val owner by session.signedIn.collectAsState()
    val profile by session.profile.collectAsState()
    val busy by vm.busy.collectAsState()
    val catalog by session.services.catalog.collectAsState()
    val context=LocalContext.current
    val scene=catalog.places.flatMap{it.scenes}.firstOrNull{it.id==item.sceneId}
    val original=remember(scene?.id,scene?.imageUrl){scene?.let{BundledCovers.model(context,"reference-${it.id}",it.imageUrl)}}
    RoomContentEntry(item,owner,profile?.optString("id")==owner&&profile?.optString("status")=="active",busy,
        roomPrivatePhotoModel(item.mediaId,session.services),original,scene?.let{"${it.kindLabel} · 《${it.work}》"},showAuthor,
        {onEdit(item)},onProfile,{action,payload->vm.mutation(action,payload){if(action=="report")vm.notice.value="举报已提交"}})
}

@Composable internal fun RoomContentEntry(
    item:ContentItem,owner:String?,active:Boolean,busy:Boolean,photo:Any?,original:Any?,originalLabel:String?,showAuthor:Boolean=true,
    onEdit:()->Unit,onProfile:(String)->Unit,onAction:(String,JSONObject)->Unit
) {
    val motion=LocalPhysicalMotion.current
    var manage by rememberSaveable(item.id,owner){mutableStateOf(false)}
    var removing by rememberSaveable(item.id,owner){mutableStateOf(false)}
    var reporting by rememberSaveable(item.id,owner){mutableStateOf(false)}
    val own=item.owner.isNotBlank()&&item.owner==owner
    val date=if(item.kind=="visit")item.raw.stringOrNull("verified_at")?.take(10)?.let{"到访于 $it"}?:"到访记录"else item.date
    val work=item.raw.optString("scene_work").ifBlank{item.song}
    val scale=remember(item.id){Animatable(1f)}
    var previousLiked by remember(item.id){mutableStateOf(item.liked)}
    LaunchedEffect(item.liked) {if(previousLiked!=item.liked&&motion){scale.animateTo(1.06f,tween(90));scale.animateTo(1f,tween(90))};previousLiked=item.liked}
    RoomPaper(Modifier.fillMaxWidth().testTag("room-content-${item.id}"),woodEdge=true) {
        Column(Modifier.padding(15.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                if(showAuthor) {
                    RoomCommunityAvatar(item.nickname,Modifier.clickable(enabled=item.owner.isNotBlank()){onProfile(item.owner)})
                    Column(Modifier.weight(1f).clickable(enabled=item.owner.isNotBlank()){onProfile(item.owner)}) {
                        Text(item.nickname,color=RoomColors.Ink,fontSize=14.sp,fontWeight=FontWeight.Medium)
                        Text(date,Modifier.padding(top=4.dp),color=RoomColors.Muted,fontSize=12.sp)
                    }
                } else Text(date,Modifier.weight(1f),fontSize=12.sp,color=RoomColors.Muted)
                Text(when(item.kind){"message"->"留言";"visit"->"到访";"recreation"->"复刻";else->"记录"},fontSize=12.sp,color=RoomColors.Muted)
            }
            if(item.hidden)Text("此条内容已被管理员隐藏，仅自己可管理。",color=MaterialTheme.colorScheme.error,fontSize=12.sp)
            if(item.kind!="message") {
                if(item.placeName.isNotBlank())Text(item.placeName,color=RoomColors.Ink,fontSize=16.sp,fontWeight=FontWeight.Medium)
                if(work.isNotBlank())Text(listOf(work,item.artist).filter{it.isNotBlank()}.joinToString(" · "),color=RoomColors.Muted,fontSize=13.sp)
            }
            if(item.body.isNotBlank())Text(item.body,color=RoomColors.Ink,fontSize=15.sp,lineHeight=25.sp)
            if(item.kind=="recreation") {
                Text(originalLabel?.let{"原作 · $it"}?:"原作参考图",fontSize=12.sp,color=RoomColors.Muted)
                if(original!=null)RoomCommunityPhoto(original,"原作参考图",tag="room-original-${item.id}")
                else Text("原作参考图暂未收录。",fontSize=13.sp,color=RoomColors.Muted)
                Text("用户复刻照片",fontSize=12.sp,color=RoomColors.Muted)
            }
            if(photo!=null||item.kind=="recreation")RoomCommunityPhoto(photo,if(item.kind=="recreation")"用户复刻照片"else "到访照片",tag="room-user-photo-${item.id}")
            if(item.kind=="message"&&work.isNotBlank())Text(listOf(work,item.artist).filter{it.isNotBlank()}.joinToString(" · "),fontSize=12.sp,color=RoomColors.Muted)
            HorizontalDivider(color=RoomColors.Ink.copy(alpha=.12f))
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                if(item.visibility=="private")Text("仅自己可见",Modifier.weight(1f),color=RoomColors.Muted,fontSize=12.sp)
                else {
                    IconButton({onAction("like",JSONObject().put("id",item.id).put("enabled",!item.liked))},Modifier.graphicsLayer{scaleX=scale.value;scaleY=scale.value},enabled=active&&!busy&&!item.hidden) {
                        Icon(if(item.liked)Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,if(item.liked)"取消点赞"else "点赞",tint=RoomColors.Navy)
                    }
                    Text("${item.likes}",Modifier.weight(1f),fontSize=12.sp,color=RoomColors.Muted)
                }
                if(own)SwissTextButton({manage=!manage},enabled=!busy,modifier=Modifier.testTag("room-manage-${item.id}")){Icon(Icons.Outlined.Settings,null,Modifier.size(16.dp));Spacer(Modifier.width(5.dp));Text("管理记录",fontSize=12.sp)}
                else if(active&&!item.hidden&&item.visibility=="public")SwissTextButton({reporting=true},enabled=!busy){Icon(Icons.Outlined.Flag,null,Modifier.size(16.dp));Spacer(Modifier.width(5.dp));Text("举报",fontSize=12.sp)}
            }
            AnimatedVisibility(own&&manage,enter=fadeIn(tween(if(motion)160 else 0))+expandVertically(tween(if(motion)160 else 0)),exit=fadeOut(tween(if(motion)160 else 0))+shrinkVertically(tween(if(motion)160 else 0))) {
                Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                    if(active&&!item.hidden)SwissTextButton(onEdit,enabled=!busy){Icon(Icons.Outlined.Edit,null,Modifier.size(17.dp));Spacer(Modifier.width(7.dp));Text("编辑")}
                    if(item.kind in listOf("visit","recreation")&&item.visibility=="public")SwissTextButton({onAction("make_private",JSONObject().put("id",item.id))},enabled=!busy){Icon(Icons.Outlined.Lock,null,Modifier.size(17.dp));Spacer(Modifier.width(7.dp));Text("转为私密")}
                    SwissTextButton({removing=true},enabled=!busy){Icon(Icons.Outlined.DeleteOutline,null,Modifier.size(17.dp));Spacer(Modifier.width(7.dp));Text(if(item.kind=="visit")"删除感想与照片"else "删除内容")}
                }
            }
        }
    }
    if(removing)AlertDialog(onDismissRequest={if(!busy)removing=false},containerColor=RoomColors.Paper,shape=androidx.compose.ui.graphics.RectangleShape,
        title={Text(if(item.kind=="visit")"删除感想与照片？"else "删除这条内容？")},
        text={Text(if(item.kind=="visit")"只移除感想和照片，到访日期与认证会保留。"else "删除后无法恢复。")},
        confirmButton={SwissTextButton({removing=false;onAction("delete_content",JSONObject().put("id",item.id))},enabled=!busy){Text("删除")}},
        dismissButton={SwissTextButton({removing=false},enabled=!busy){Text("取消")}})
    if(reporting)AlertDialog(onDismissRequest={reporting=false},containerColor=RoomColors.Paper,shape=androidx.compose.ui.graphics.RectangleShape,title={Text("举报内容")},
        text={Column{listOf("垃圾内容","不友善内容","色情 / 违法","版权问题","信息错误","其他").forEach{reason->SwissTextButton({reporting=false;onAction("report",JSONObject().put("id",item.id).put("reason",reason))},enabled=!busy){Text(reason)}}}},
        confirmButton={},dismissButton={SwissTextButton({reporting=false}){Text("取消")}})
}
