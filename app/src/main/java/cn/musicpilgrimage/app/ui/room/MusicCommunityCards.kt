package cn.musicpilgrimage.app.ui.room

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import cn.musicpilgrimage.app.FeatureViewModel
import cn.musicpilgrimage.app.SessionViewModel
import cn.musicpilgrimage.app.data.*
import cn.musicpilgrimage.app.ui.LocalPhysicalMotion
import cn.musicpilgrimage.app.ui.appMotionEasing
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import kotlin.math.roundToInt

@Composable internal fun RoomContentCard(item:ContentItem,session:SessionViewModel,vm:FeatureViewModel,onEdit:(ContentItem)->Unit,onProfile:(String)->Unit,showAuthor:Boolean=true) {
    val owner by session.signedIn.collectAsState()
    val profile by session.profile.collectAsState()
    val busy by vm.busy.collectAsState()
    val likes=vm.repositories.content.likes
    val reactionFlow=remember(likes,item.id){likes.states.map{it[item.id]}.distinctUntilChanged()}
    val reaction by reactionFlow.collectAsState(initial=likes.states.value[item.id])
    val catalog by session.services.catalog.collectAsState()
    val context=LocalContext.current
    val scene=catalog.places.flatMap{it.scenes}.firstOrNull{it.id==item.sceneId}
    val original=remember(scene?.id,scene?.imageUrl){scene?.let{BundledCovers.model(context,"reference-${it.id}",it.imageUrl)}}
    RoomContentEntry(item,owner,profile?.optString("id")==owner&&profile?.optString("status")=="active",busy,
        roomPrivatePhotoModel(item.mediaId,session.services),original,scene?.let{"${it.kindLabel} · 《${it.work}》"},showAuthor,
        {onEdit(item)},onProfile,{action,payload->vm.mutation(action,payload){if(action=="report")vm.notice.value="举报已提交"}},
        likeState=reaction?.takeIf{it.owner==owner},
        onLike={owner?.let{likes.toggle(it,item.id,item.liked)}})
}

@Composable internal fun RoomContentEntry(
    item:ContentItem,owner:String?,active:Boolean,busy:Boolean,photo:Any?,original:Any?,originalLabel:String?,showAuthor:Boolean=true,
    onEdit:()->Unit,onProfile:(String)->Unit,onAction:(String,JSONObject)->Unit,
    likeState:ContentLikeState?=null,
    onLike:()->Unit={onAction("like",JSONObject().put("id",item.id).put("enabled",!item.liked))}
) {
    val motion=LocalPhysicalMotion.current
    var manage by rememberSaveable(item.id,owner){mutableStateOf(false)}
    var removing by rememberSaveable(item.id,owner){mutableStateOf(false)}
    var reporting by rememberSaveable(item.id,owner){mutableStateOf(false)}
    val own=item.owner.isNotBlank()&&item.owner==owner
    val date=if(item.kind=="visit")item.raw.stringOrNull("verified_at")?.take(10)?.let{"到访于 $it"}?:"到访记录"else item.date
    val work=item.raw.stringOrNull("scene_work")?:item.song
    val lift=remember(item.id){Animatable(0f)}
    val liked=likeState?.liked?:item.liked
    var previousLiked by remember(item.id,owner){mutableStateOf(liked)}
    LaunchedEffect(item.id,liked,motion) {
        val changed=previousLiked!=liked
        previousLiked=liked
        lift.snapTo(0f)
        if(changed&&motion) {
            lift.animateTo(if(liked)1f else -.45f,tween(if(liked)85 else 65))
            lift.animateTo(0f,tween(if(liked)145 else 105))
        }
    }
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
                if (item.kind != "message") {
                    Text(when(item.kind){"visit"->"到访";"recreation"->"复刻";else->"记录"},fontSize=12.sp,color=RoomColors.Muted)
                }
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
                    IconButton(onLike,enabled=active&&!busy&&!item.hidden) {
                        Icon(
                            if(liked)Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                            if(liked)"取消点赞"else "点赞",
                            Modifier.graphicsLayer{translationY=-3.dp.toPx()*lift.value;rotationZ=-9f*lift.value},
                            tint=RoomColors.Navy,
                        )
                    }
                    Text("${likeState?.count(item.liked,item.likes)?:item.likes}",Modifier.weight(1f),fontSize=12.sp,color=RoomColors.Muted)
                }
                if(own && item.kind == "message") {
                    MessageManagementMenu(item.id,busy,active&&!item.hidden,onEdit){removing=true}
                }
                else if(own)SwissTextButton({manage=!manage},enabled=!busy,modifier=Modifier.testTag("room-manage-${item.id}")){Icon(Icons.Outlined.Settings,null,Modifier.size(16.dp));Spacer(Modifier.width(5.dp));Text("管理记录",fontSize=12.sp)}
                else if(active&&!item.hidden&&item.visibility=="public")SwissTextButton({reporting=true},enabled=!busy){Icon(Icons.Outlined.Flag,null,Modifier.size(16.dp));Spacer(Modifier.width(5.dp));Text("举报",fontSize=12.sp)}
            }
            likeState?.failedTarget?.let { failedLike ->
                Text(
                    if(failedLike)"点赞未保存，请重试" else "取消点赞未保存，请重试",
                    Modifier.semantics { liveRegion=LiveRegionMode.Polite },
                    color=MaterialTheme.colorScheme.error,fontSize=12.sp,
                )
            }
            AnimatedVisibility(own&&item.kind!="message"&&manage,enter=fadeIn(tween(if(motion)160 else 0))+expandVertically(tween(if(motion)160 else 0)),exit=fadeOut(tween(if(motion)160 else 0))+shrinkVertically(tween(if(motion)160 else 0))) {
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

private val MessageMenuButtonPlate = 34.dp
private val MessageMenuSideMargin = 8.dp
private val MessageMenuVerticalPadding = 48.dp

private data class MessageMenuPlacement(val button: Rect, val menu: Rect)

@Composable private fun MessageManagementMenu(
    id:String,busy:Boolean,canEdit:Boolean,onEdit:()->Unit,onDelete:()->Unit
) {
    var expanded by remember(id){mutableStateOf(false)}
    var placement by remember(id){mutableStateOf<MessageMenuPlacement?>(null)}
    val interactions=remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val motion=LocalPhysicalMotion.current
    val transition=updateTransition(expanded,label="Message management")
    val progress by transition.animateFloat(transitionSpec={
        if(motion)tween(if(targetState)190 else 145,easing=appMotionEasing) else snap()
    },label="Menu expansion"){if(it)1f else 0f}
    val popupVisible=expanded||(motion&&(transition.currentState||transition.isRunning))

    Box {
        IconButton(
            onClick={expanded=!expanded},enabled=!busy,
            interactionSource=interactions,
            modifier=Modifier.testTag("room-manage-$id").semantics {
                stateDescription=if(expanded)"已展开" else "已收起"
            },
        ) {
            Box(Modifier.size(MessageMenuButtonPlate).graphicsLayer {
                alpha=if(motion&&popupVisible&&placement!=null)progress.coerceIn(0f,1f) else 1f
            }.then(if(pressed||expanded&&placement==null)Modifier.background(RoomColors.Paper,CircleShape) else Modifier),
                contentAlignment=Alignment.Center) {
                Icon(Icons.Outlined.Settings,"管理留言",Modifier.size(20.dp),tint=RoomColors.Ink)
            }
        }
        if(popupVisible) {
            val density=LocalDensity.current
            DisposableEffect(Unit){onDispose{placement=null}}
            val position=remember(density) {
                val margin=with(density){MessageMenuSideMargin.roundToPx()}
                val verticalPadding=with(density){MessageMenuVerticalPadding.roundToPx()}
                val radius=with(density){MessageMenuButtonPlate.toPx()/2}
                val overlap=with(density){4.dp.roundToPx()}
                object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds:IntRect,windowSize:IntSize,layoutDirection:LayoutDirection,popupContentSize:IntSize
                    ):IntOffset {
                        val width=(popupContentSize.width-2*margin).coerceAtLeast(1)
                        val height=(popupContentSize.height-2*verticalPadding).coerceAtLeast(1)
                        val x=(anchorBounds.right-popupContentSize.width).coerceIn(0,(windowSize.width-popupContentSize.width).coerceAtLeast(0))
                        val below=anchorBounds.bottom-overlap
                        val above=anchorBounds.top+overlap-height
                        val fitsBelow=below+height+margin<=windowSize.height
                        val moreSpaceBelow=windowSize.height-anchorBounds.bottom>=anchorBounds.top
                        val top=(if(fitsBelow||above<margin&&moreSpaceBelow)below else above)
                            .coerceIn(margin,(windowSize.height-height-margin).coerceAtLeast(margin))
                        val y=(top-verticalPadding).coerceIn(0,(windowSize.height-popupContentSize.height).coerceAtLeast(0))
                        val center=Offset((anchorBounds.center.x-x).toFloat(),(anchorBounds.center.y-y).toFloat())
                        val menu=Rect(Offset(margin.toFloat(),(top-y).toFloat()),Size(width.toFloat(),height.toFloat()))
                        placement=MessageMenuPlacement(Rect(center,radius),menu)
                        return IntOffset(x,y)
                    }
                }
            }
            Popup(popupPositionProvider=position,onDismissRequest={expanded=false},properties=PopupProperties(focusable=expanded)) {
                Box(Modifier.pointerInput(busy) {
                    detectTapGestures { touch ->
                        if(expanded)expanded=false
                        else if(!busy&&placement?.button?.inflate(7.dp.toPx())?.contains(touch)==true)expanded=true
                    }
                }) {
                    Box(Modifier.messageMenuPlate({placement},{if(motion)progress else 1f})) {
                        Column(Modifier.padding(horizontal=MessageMenuSideMargin,vertical=MessageMenuVerticalPadding)
                            .offset {
                                val top=placement?.menu?.top?.roundToInt() ?: MessageMenuVerticalPadding.roundToPx()
                                IntOffset(0,top-MessageMenuVerticalPadding.roundToPx())
                            }.width(IntrinsicSize.Max).testTag("room-message-menu-$id")) {
                            if(canEdit)MessageMenuAction("编辑",Icons.Outlined.Edit,RoomColors.Ink,expanded&&!busy) {
                                expanded=false
                                onEdit()
                            }
                            MessageMenuAction("删除",Icons.Outlined.DeleteOutline,RoomColors.Red,expanded&&!busy) {
                                expanded=false
                                onDelete()
                            }
                        }
                    }
                    if(motion) Icon(Icons.Outlined.Settings,null,Modifier.offset {
                        val center=placement?.button?.center ?: Offset.Zero
                        val half=10.dp.toPx()
                        IntOffset((center.x-half).roundToInt(),(center.y-half).roundToInt())
                    }.size(20.dp).graphicsLayer {
                        alpha=if(placement==null)0f else 1f-progress.coerceIn(0f,1f)
                    },tint=RoomColors.Ink)
                }
            }
        }
    }
}

private fun Modifier.messageMenuPlate(
    placement:()->MessageMenuPlacement?, progress:()->Float,
):Modifier=drawWithCache {
    val clip=Path()
    val border=Stroke(1.dp.toPx())
    val buttonRadius=MessageMenuButtonPlate.toPx()/2
    val menuRadius=6.dp.toPx()
    val shadowSpread=5.dp.toPx()
    val shadowDrop=2.dp.toPx()
    onDrawWithContent {
        val geometry=placement() ?: return@onDrawWithContent
        val amount=progress().coerceIn(0f,1f)
        fun mix(start:Float,end:Float)=start+(end-start)*amount
        val start=geometry.button
        val end=geometry.menu
        val bounds=Rect(mix(start.left,end.left),mix(start.top,end.top),
            mix(start.right,end.right),mix(start.bottom,end.bottom))
        val radius=mix(buttonRadius,menuRadius)
        for(layer in 5 downTo 1) {
            val spread=shadowSpread*layer/5*amount
            drawRoundRect(RoomColors.Ink.copy(alpha=(6-layer)*.012f*amount),
                Offset(bounds.left-spread,bounds.top+shadowDrop*amount-spread),
                Size(bounds.width+2*spread,bounds.height+2*spread),CornerRadius(radius+spread))
        }
        drawRoundRect(RoomColors.Paper,bounds.topLeft,bounds.size,CornerRadius(radius))
        drawRoundRect(RoomColors.Line.copy(alpha=amount),bounds.topLeft,bounds.size,CornerRadius(radius),style=border)
        clip.rewind()
        clip.addRoundRect(RoundRect(bounds,CornerRadius(radius)))
        clipPath(clip) { this@onDrawWithContent.drawContent() }
    }
}

@Composable private fun MessageMenuAction(
    label:String,icon:ImageVector,color:Color,enabled:Boolean,onClick:()->Unit
) {
    val tint=if(enabled)color else color.copy(alpha=.38f)
    Row(
        Modifier.fillMaxWidth().heightIn(min=48.dp)
            .clickable(enabled=enabled,role=Role.Button,onClick=onClick)
            .padding(horizontal=10.dp),
        verticalAlignment=Alignment.CenterVertically,
        horizontalArrangement=Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon,null,Modifier.size(18.dp),tint=tint)
        Text(label,color=tint,fontSize=14.sp)
    }
}
