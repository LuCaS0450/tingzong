package cn.musicpilgrimage.app.ui.room

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.gestures.drag as trackDrag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.musicpilgrimage.app.data.MusicTrip
import cn.musicpilgrimage.app.data.MusicTripStop
import cn.musicpilgrimage.app.ui.LocalPhysicalMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal data class TripDragItem(val key:String,val top:Float,val height:Float)
internal data class TripDropTarget(val day:Int?,val index:Int,val duplicate:Boolean=false)

/** Preserve full card geometry when its top is scrolled out of the viewport. */
internal fun LayoutCoordinates.tripDragBounds()=Rect(positionInWindow(),Size(size.width.toFloat(),size.height.toFloat()))

/** Insertion indexes exclude the lifted card, including when moving down within one day. */
internal fun resolveTripDrop(trip:MusicTrip,source:MusicTripStop,y:Float,items:List<TripDragItem>):TripDropTarget? {
    val stops=trip.stops.associateBy{it.id}
    val item=items.filter{it.key.startsWith("day:")||it.key.startsWith("empty:")||it.key in stops}
        .minByOrNull{when{y<it.top->it.top-y;y>it.top+it.height->y-it.top-it.height;else->0f}} ?: return null
    val over=stops[item.key]
    val day=over?.day ?: if(over==null)item.key.substringAfter(':').toIntOrNull()else null
    val group=trip.stops.filter{it.day==day&&it.id!=source.id}
    val index=when {
        over==null->0
        over.id==source.id->trip.stops.filter{it.day==day}.indexOfFirst{it.id==source.id}.coerceIn(0,group.size)
        else->group.indexOfFirst{it.id==over.id}+(if(y>=item.top+item.height/2)1 else 0)
    }
    return TripDropTarget(day,index.coerceIn(0,group.size),day!=null&&source.placeId!=null&&group.any{it.placeId==source.placeId})
}

internal fun tripDragScrollSpeed(y:Float,height:Float,edge:Float,maxSpeed:Float):Float = when {
    height<=0||edge<=0->0f
    y<edge->-maxSpeed*((edge-y)/edge).coerceIn(0f,1f)
    y>height-edge->maxSpeed*((y-height+edge)/edge).coerceIn(0f,1f)
    else->0f
}

@Stable internal class MusicTripDragState(
    val list:LazyListState,private val scope:CoroutineScope,
    private val onDrop:(String,Int?,Int,()->Unit)->Unit
) {
    var trip:MusicTrip?=null
    var container by mutableStateOf(Rect.Zero)
    val cards=mutableMapOf<String,Rect>()
    val handles=mutableMapOf<String,Rect>()
    var source by mutableStateOf<MusicTripStop?>(null);private set
    var dragging by mutableStateOf(false);private set
    var position by mutableStateOf(Offset.Zero);private set
    var target by mutableStateOf<TripDropTarget?>(null);private set
    var notice by mutableStateOf<String?>(null);private set
    var width by mutableFloatStateOf(0f);private set
    private var pointer by mutableStateOf(Offset.Zero)
    private var grab=Offset.Zero
    private var origin=Offset.Zero
    private var generation=0
    val active get()=source!=null
    val pointerY get()=pointer.y

    fun handleAt(point:Offset):String? {
        val visible=list.layoutInfo.visibleItemsInfo.map{it.key.toString()}.toSet()
        return handles.entries.firstOrNull{it.key in visible&&it.value.contains(point+container.topLeft)}?.key
    }
    fun start(id:String,point:Offset) {
        if(active)return
        val stop=trip?.stops?.find{it.id==id} ?: return
        val bounds=cards[id] ?: return
        generation++;source=stop;dragging=true;notice=null;width=bounds.width
        origin=bounds.topLeft-container.topLeft;position=origin;pointer=point;grab=point-origin
        retarget()
    }
    fun move(point:Offset) {
        if(!dragging)return
        pointer=point
        position=Offset((point.x-grab.x).coerceIn(0f,(container.width-width).coerceAtLeast(0f)),point.y-grab.y)
        retarget()
    }
    fun retarget() {
        val stop=source ?: return
        val plan=trip ?: return
        val layout=list.layoutInfo
        // Lazy item offsets omit the before-content padding that is applied at placement.
        target=if(pointer.x in 0f..container.width&&pointer.y in 0f..container.height)
            resolveTripDrop(plan,stop,pointer.y,layout.visibleItemsInfo.map{TripDragItem(it.key.toString(),(it.offset-layout.viewportStartOffset).toFloat(),it.size.toFloat())}) else null
    }
    fun lineAt(key:String,after:Boolean):Boolean {
        val stop=source ?: return false
        val drop=target ?: return false
        val group=trip?.stops?.filter{it.day==drop.day&&it.id!=stop.id} ?: return false
        return when {
            drop.index<group.size->!after&&key==group[drop.index].id
            group.isNotEmpty()->after&&key==group.last().id
            else->after&&key=="day:${drop.day}"
        }
    }
    fun finish(commit:Boolean,motion:Boolean) {
        if(!dragging)return
        dragging=false
        val stop=source ?: return
        val drop=target
        if(commit&&drop?.duplicate==true)notice="这个地点已安排在第${drop.day}天，请选择另一天。"
        val currentIndex=trip?.stops?.filter{it.day==stop.day}?.indexOfFirst{it.id==stop.id}
        if(commit&&drop!=null&&!drop.duplicate&&(drop.day!=stop.day||drop.index!=currentIndex)) {
            onDrop(stop.id,drop.day,drop.index){settle(motion)}
        } else settle(motion)
    }
    private fun settle(motion:Boolean) {
        val token=generation
        scope.launch {
            // Save finishes before the list places its destination card. Never change list scroll here.
            repeat(2){withFrameNanos{}}
            if(token!=generation||source==null)return@launch
            val stop=source!!
            val visible=list.layoutInfo.visibleItemsInfo.any{it.key==stop.id}
            val destination=if(visible)cards[stop.id]?.topLeft?.minus(container.topLeft) ?: origin
                else Offset(position.x,if(position.y<container.height/2)-2000f else container.height+200f)
            Animatable(position,Offset.VectorConverter).animateTo(destination,tween(if(motion)180 else 0)) {position=value}
            if(token==generation){source=null;target=null}
        }
    }
    fun clear() {generation++;dragging=false;source=null;target=null}
}

@Composable internal fun RoomTripDragSurface(
    trip:MusicTrip,owner:String,list:LazyListState,busy:Boolean,
    onDrop:(String,Int?,Int,()->Unit)->Unit,
    content:@Composable (MusicTripDragState)->Unit
) {
    val scope=rememberCoroutineScope()
    val currentDrop by rememberUpdatedState(onDrop)
    val drag=remember(trip.id,owner,list){MusicTripDragState(list,scope){id,day,index,done->currentDrop(id,day,index,done)}}
    SideEffect{drag.trip=trip}
    val motion=LocalPhysicalMotion.current
    val haptics=LocalHapticFeedback.current
    val density=LocalDensity.current
    val edge=with(density){64.dp.toPx()}
    val speed=with(density){520.dp.toPx()}
    val velocity=tripDragScrollSpeed(drag.pointerY,drag.container.height,edge,speed)
    LaunchedEffect(drag.dragging,velocity) {
        if(drag.dragging&&velocity!=0f) {
            var previous=withFrameNanos{it}
            while(isActive&&drag.dragging) {
                val now=withFrameNanos{it};val seconds=((now-previous)/1_000_000_000f).coerceAtMost(.032f);previous=now
                if(list.scrollBy(velocity*seconds)==0f)break
                drag.retarget()
            }
        }
    }
    LaunchedEffect(busy,trip.stops){if(drag.dragging&&(busy||trip.stops.none{it.id==drag.source?.id}))drag.finish(false,motion)}
    BackHandler(drag.active){if(drag.dragging)drag.finish(false,motion)else drag.clear()}
    Box(Modifier.fillMaxSize().clipToBounds().onGloballyPositioned{drag.container=it.boundsInWindow()}
        .pointerInput(drag,busy,motion) {
            if(!busy)awaitEachGesture {
                val down=awaitFirstDown(requireUnconsumed=false)
                val id=drag.handleAt(down.position) ?: return@awaitEachGesture
                val held=awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                drag.start(id,held.position)
                if(!drag.dragging)return@awaitEachGesture
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                // The detector belongs to this stable surface so recycling the source row cannot cancel a cross-day drag.
                try {
                    val completed=trackDrag(held.id){change->drag.move(change.position);change.consume()}
                    drag.finish(completed,motion)
                } finally {if(drag.dragging)drag.finish(false,motion)}
            }
        }) {
        content(drag)
        drag.source?.let{stop->
            Box(Modifier.offset{IntOffset(drag.position.x.roundToInt(),drag.position.y.roundToInt())}
                .width(with(density){drag.width.toDp()}).shadow(12.dp,androidx.compose.ui.graphics.RectangleShape).testTag("trip-drag-preview")) {
                RoomTripStopCard(stop,Modifier.clearAndSetSemantics{},false,false,false,{},{},{},{},{},{},Modifier)
            }
            if(drag.dragging)drag.target?.let{target->
                Text(if(target.duplicate)"第${target.day}天已有这个地点" else target.day?.let{"放入第${it}天 · 第${target.index+1}个地点"} ?: "放入待安排 · 第${target.index+1}个地点",
                    Modifier.align(Alignment.TopCenter).offset{IntOffset(0,(drag.position.y-38*density.density).coerceIn(6*density.density,(drag.container.height-38*density.density).coerceAtLeast(6*density.density)).roundToInt())}
                        .background(RoomColors.Paper,androidx.compose.ui.graphics.RectangleShape).padding(10.dp,6.dp).testTag("trip-drop-hint"),
                    color=if(target.duplicate)MaterialTheme.colorScheme.error else RoomColors.Navy,fontSize=13.sp)
            }
        }
    }
}

@Composable internal fun TripDropGuide(drag:MusicTripDragState,key:String,after:Boolean) {
    if(drag.lineAt(key,after))Box(Modifier.fillMaxWidth().height(2.dp).background(if(drag.target?.duplicate==true)MaterialTheme.colorScheme.error else RoomColors.Navy).testTag("trip-drop-line"))
}
