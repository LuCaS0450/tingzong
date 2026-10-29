package cn.musicpilgrimage.app.design

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import cn.musicpilgrimage.app.ui.LocalPhysicalMotion
import androidx.compose.animation.*
import androidx.compose.animation.core.tween

internal val BauhausMediaInsetShape=RoundedCornerShape(0.dp)

/** Compatibility surfaces follow the same quiet room controls as the native pages. */
@Composable internal fun BauhausPanel(modifier:Modifier=Modifier,material:String="paper",face:Color=AppArt.Paper,
    pressed:Boolean=false,selected:Boolean=false,content:@Composable BoxScope.()->Unit) {
    val shape=RoundedCornerShape(0.dp)
    Surface(modifier,
        shape=shape,color=if(selected)AppArt.PaleBlue else face,
        border=BorderStroke(.5.dp,if(selected)AppArt.Blue else AppArt.Ink.copy(alpha=.12f)),shadowElevation=0.dp) {
        Box(Modifier.fillMaxWidth(),content=content)
    }
}

@Composable internal fun PixelButton(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,
    primary:Boolean=true,content:@Composable RowScope.()->Unit) {
    val source=remember{MutableInteractionSource()}
    val pressed by source.collectIsPressedAsState()
    BauhausPanel(modifier.heightIn(min=48.dp).semantics{role=Role.Button}
        .clickable(enabled=enabled,interactionSource=source,indication=null,onClick=onClick),
        face=if(!enabled)AppArt.Sand else if(primary)AppArt.Blue else AppArt.Paper,pressed=pressed) {
        CompositionLocalProvider(LocalContentColor provides if(!enabled)AppArt.Muted else if(primary)Color.White else AppArt.Ink) {
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=12.dp),
                horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically,content=content)
        }
    }
}

@Composable internal fun PixelIconButton(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,content:@Composable ()->Unit) {
    androidx.compose.material3.IconButton(onClick,modifier.sizeIn(minWidth=48.dp,minHeight=48.dp),enabled=enabled,content=content)
}

@Composable internal fun BauhausButton(label:String,onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,primary:Boolean=false) {
    if(primary)Button(onClick,modifier,enabled=enabled,shape=RoundedCornerShape(0.dp)){Text(label)}
    else OutlinedButton(onClick,modifier,enabled=enabled,shape=RoundedCornerShape(0.dp)){Text(label)}
}
@Composable internal fun BauhausBack(onClick:()->Unit,modifier:Modifier=Modifier,description:String="返回上一步") {
    IconButton(onClick,modifier){Icon(Icons.AutoMirrored.Filled.ArrowBack,description)}
}
@Composable internal fun BauhausTag(label:String) {
    Surface(shape=RoundedCornerShape(0.dp),color=AppArt.Sand) {
        Text(label,color=AppArt.Ink,fontSize=12.sp,lineHeight=18.sp,modifier=Modifier.padding(horizontal=8.dp,vertical=4.dp))
    }
}
@Composable internal fun BauhausDisclosure(label:String,modifier:Modifier=Modifier,expandedState:MutableState<Boolean>?=null,content:@Composable ColumnScope.()->Unit) {
    val local=rememberSaveable{mutableStateOf(false)};var expanded by expandedState?:local
    Column(modifier,verticalArrangement=Arrangement.spacedBy(10.dp)) {
        BauhausPanel(Modifier.fillMaxWidth().heightIn(min=48.dp)
            .semantics{stateDescription=if(expanded)"已展开" else "已收起"}
            .clickable(role=Role.Button,onClickLabel=if(expanded)"收起$label" else "展开$label"){expanded=!expanded}) {
            Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(label,Modifier.weight(1f),color=AppArt.Ink)
                Icon(if(expanded)Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,null)
            }
        }
        val duration=if(LocalPhysicalMotion.current)90 else 0
        AnimatedVisibility(expanded,enter=fadeIn(tween(duration)),exit=fadeOut(tween(duration))) {ArtPaper(content=content)}
    }
}
@Composable internal fun BauhausSetting(label:String,checked:Boolean,onClick:()->Unit,modifier:Modifier=Modifier) {
    BauhausPanel(modifier.heightIn(min=48.dp).toggleable(checked,role=Role.Switch,onValueChange={onClick()}),selected=checked) {
        Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            BauhausIndicator(checked,false);Text(label,color=AppArt.Ink,fontSize=14.sp)
        }
    }
}
@Composable internal fun BauhausIndicator(selected:Boolean,checkbox:Boolean,enabled:Boolean=true) {
    if(checkbox)Checkbox(selected,null,Modifier.size(24.dp),enabled=enabled)
    else RadioButton(selected,null,Modifier.size(24.dp),enabled=enabled)
}
@Composable internal fun PixelSwitch(checked:Boolean,onCheckedChange:(Boolean)->Unit,modifier:Modifier=Modifier,enabled:Boolean=true) {
    Box(modifier.sizeIn(minWidth=52.dp,minHeight=48.dp)
        .toggleable(checked,enabled=enabled,role=Role.Switch,onValueChange=onCheckedChange),contentAlignment=Alignment.Center) {
        Canvas(Modifier.size(44.dp,24.dp)) {
            val u=size.height/12f
            drawRect(AppArt.Ink)
            drawRect(if(checked)AppArt.Green else AppArt.Sand,Offset(u,u),Size(size.width-2*u,size.height-2*u))
            drawRect(if(enabled)AppArt.Paper else AppArt.Edge,Offset(if(checked)size.width-10*u else 2*u,2*u),Size(8*u,8*u))
        }
    }
}
@Composable internal fun AluminumClip(modifier:Modifier=Modifier) {
    Canvas(modifier) {
        drawRect(AppArt.Ink)
        drawRect(AppArt.Yellow,Offset(2.dp.toPx(),0f),Size(size.width-4.dp.toPx(),size.height-2.dp.toPx()))
    }
}
@Composable internal fun BauhausMediaFrame(modifier:Modifier=Modifier,content:@Composable BoxScope.()->Unit) {
    BauhausPanel(modifier.fillMaxWidth(),face=AppArt.Sand) {Box(Modifier.padding(6.dp).clip(BauhausMediaInsetShape),content=content)}
}
@Composable internal fun PhotoWorkSurface(mounted:Boolean,content:@Composable ColumnScope.()->Unit) {
    if(mounted)BauhausMediaFrame{ArtPaper(layered=false,content=content)} else ArtPaper(content=content)
}
@Composable internal fun BauhausSlot(modifier:Modifier=Modifier,content:@Composable BoxScope.()->Unit) {
    Box(modifier.clip(PixelShape(2.dp)).background(AppArt.PaleBlue).border(2.dp,AppArt.Ink,PixelShape(2.dp)),contentAlignment=Alignment.Center,content=content)
}
@Composable internal fun BauhausDock(content:@Composable ColumnScope.()->Unit) {
    Column(Modifier.fillMaxWidth().background(AppArt.Base).padding(horizontal=20.dp,vertical=12.dp),
        verticalArrangement=Arrangement.spacedBy(8.dp),content=content)
}
