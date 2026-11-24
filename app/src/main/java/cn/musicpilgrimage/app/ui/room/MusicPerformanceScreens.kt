package cn.musicpilgrimage.app.ui.room

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.musicpilgrimage.app.data.ReleaseArtwork
import cn.musicpilgrimage.app.performance.*
import cn.musicpilgrimage.app.ui.AppNavEasing
import cn.musicpilgrimage.app.ui.LocalAppHaptics
import cn.musicpilgrimage.app.ui.LocalPhysicalMotion
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable internal fun RoomPerformancePreparation(entry:PerformanceEntry,artwork:ReleaseArtwork?,onStart:()->Unit,onImport:(()->Unit)?=null,
    importing:Boolean=false,importProgress:Float=0f,importMessage:String?=null) {
    val song=entry.song
    LazyColumn(Modifier.fillMaxSize().testTag("performance-preparation"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(22.dp)) {
        item("song") {RoomPaper(Modifier.fillMaxWidth(),woodEdge=true) {
            Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                RoomCover(artwork,song.title,Modifier.size(112.dp))
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(song.title,fontSize=22.sp,fontWeight=FontWeight.SemiBold,color=RoomColors.Ink)
                    Text(song.artist,fontSize=15.sp,color=RoomColors.Muted)
                    Text("${musicTime(song.durationUs)}${if(entry.preview)" · 短样" else ""}",fontSize=14.sp,color=RoomColors.Muted)
                }
            }
        }}
        item("start") {
            Column(verticalArrangement=Arrangement.spacedBy(14.dp)) {
                SwissButton(onClick=onStart,enabled=entry.audioReady&&!importing,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp).testTag("performance-enter"),shape=androidx.compose.ui.graphics.RectangleShape,
                    colors=ButtonDefaults.buttonColors(containerColor=RoomColors.Navy,contentColor=RoomColors.Paper)) {Text("开始演奏",fontSize=17.sp)}
                Text("音符到线时轻点，长音按住。\n漏点影响成绩，音乐仍会继续。",fontSize=14.sp,color=RoomColors.Muted,lineHeight=22.sp)
                if(entry.preview)Text("30秒体验短样，不计入正式成绩。",fontSize=13.sp,color=RoomColors.Muted)
                if(entry.preview&&entry.audioDirectory==null) {
                    Text("请先导入已有的本地试听包。",color=RoomColors.Muted)
                    if(onImport!=null)SwissOutlinedButton(onImport,Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("导入试听包")}
                }
                if(song.recording?.local==true) {
                    if(!entry.audioReady)Text("导入一次歌曲音源，之后即可离线演奏。",fontSize=14.sp,color=RoomColors.Muted)
                    if(onImport!=null)SwissOutlinedButton(onImport,enabled=!importing,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("performance-import")) {
                        if(importing) {CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp,color=RoomColors.Navy);Spacer(Modifier.width(10.dp))}
                        Text(if(importing)"正在导入 ${(importProgress*100).toInt()}%" else if(entry.audioReady)"重新导入音源" else "导入歌曲音源")
                    }
                    if(importMessage!=null)Text(importMessage,fontSize=14.sp,color=RoomColors.Muted,modifier=Modifier.testTag("performance-import-message"))
                }
            }
        }
    }
}

@Composable internal fun RoomPerformanceResult(
    song:SongPackage,
    ui:PerformanceUi,
    artwork:ReleaseArtwork?,
    preview:Boolean,
    onAgain:()->Unit,
    onExplore:(()->Unit)?,
    onRetrySave:()->Unit,
    attemptGeneration:Int=0,
) {
    val motion=LocalPhysicalMotion.current
    val haptics=LocalAppHaptics.current
    val density=LocalDensity.current
    val accuracy=remember(ui.judgments){computeAccuracyPercent(ui.judgments)}
    val metricsProgress=remember(attemptGeneration){Animatable(if(motion)0f else 1f)}
    val recordProgress=remember(attemptGeneration){Animatable(if(motion)0f else 1f)}
    LaunchedEffect(attemptGeneration,song.id,ui.newRecord,motion) {
        if(!motion) {
            metricsProgress.snapTo(1f)
            recordProgress.snapTo(1f)
            return@LaunchedEffect
        }
        metricsProgress.snapTo(0f)
        recordProgress.snapTo(0f)
        launch {
            delay(95)
            metricsProgress.animateTo(1f,tween(175,easing=AppNavEasing))
        }
        launch {
            delay(165)
            if(ui.newRecord&&!preview)haptics?.confirm("result-record-$attemptGeneration-${song.id}")
            recordProgress.animateTo(1f,tween(190,easing=AppNavEasing))
        }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("performance-result"),contentPadding=PaddingValues(26.dp,20.dp,26.dp,32.dp),verticalArrangement=Arrangement.spacedBy(22.dp)) {
        item("title") {Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                SwissEyebrow(if(ui.newRecord&&!preview)"PERFORMANCE / NEW RECORD" else "PERFORMANCE / WELL PLAYED")
                if(ui.newRecord&&!preview) {
                    Text(
                        "新纪录",
                        modifier=Modifier.graphicsLayer {
                            val t=recordProgress.value
                            alpha=t
                            val s=0.90f+0.10f*t
                            scaleX=s
                            scaleY=s
                        },
                        fontSize=11.sp,
                        fontWeight=FontWeight.SemiBold,
                        color=RoomColors.Red,
                    )
                }
            }
            Text(song.title,fontSize=29.sp,fontWeight=FontWeight.SemiBold,color=RoomColors.Ink,modifier=Modifier.testTag("performance-result-title"))
            Text(song.artist,fontSize=13.sp,color=RoomColors.Muted)
        }}
        item("score") {Column(Modifier.fillMaxWidth().padding(top=10.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(
                "%,d".format(ui.score),
                style=TextStyle(fontSize=66.sp,fontWeight=FontWeight.Normal,letterSpacing=(-3).sp,fontFeatureSettings="tnum"),
                color=RoomColors.Ink,
                modifier=Modifier.testTag("performance-result-score"),
            )
            SwissEyebrow("SCORE / 演奏成绩")
        }}
        item("metrics") {
            val slidePx=with(density){8.dp.toPx()}
            Column(Modifier.graphicsLayer {
                alpha=metricsProgress.value
                translationY=(1f-metricsProgress.value)*slidePx
            }) {
                HorizontalDivider(color=RoomColors.Line)
                Row(Modifier.fillMaxWidth().padding(vertical=20.dp)) {
                    if(accuracy!=null) {
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text("准确率",fontSize=12.sp,color=RoomColors.Muted)
                            Text(formatAccuracy(accuracy),style=TextStyle(fontSize=27.sp,fontFeatureSettings="tnum"),color=RoomColors.Ink,modifier=Modifier.testTag("performance-result-accuracy"))
                        }
                    }
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)){Text("最高连击",fontSize=12.sp,color=RoomColors.Muted);Text(ui.bestCombo.toString(),style=TextStyle(fontSize=27.sp,fontFeatureSettings="tnum"),color=RoomColors.Ink)}
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)){Text("历史最高",fontSize=12.sp,color=RoomColors.Muted);Text(ui.highestScore?.let{"%,d".format(it)}?:"—",style=TextStyle(fontSize=27.sp,fontFeatureSettings="tnum"),color=RoomColors.Ink)}
                }
                HorizontalDivider(color=RoomColors.Line)
            }
        }
        item("save") {
            if(preview)Text("体验短样，不计入正式成绩",fontSize=13.sp,color=RoomColors.Muted)
            else if(!ui.saved)Text(ui.message?:"正在保存成绩…",fontSize=13.sp,color=RoomColors.Muted)
            if(!ui.saved&&ui.message!=null)SwissTextButton(onRetrySave){Text("重新保存")}
        }
        item("actions") {Column(verticalArrangement=Arrangement.spacedBy(14.dp)) {
            SwissButton(onAgain,Modifier.fillMaxWidth().heightIn(min=50.dp).testTag("performance-again"),shape=androidx.compose.ui.graphics.RectangleShape,colors=ButtonDefaults.buttonColors(containerColor=RoomColors.Ink,contentColor=RoomColors.Paper)){Text("再演奏一次")}
            if(onExplore!=null)SwissOutlinedButton(onExplore,Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("performance-explore"),shape=androidx.compose.ui.graphics.RectangleShape,colors=ButtonDefaults.outlinedButtonColors(contentColor=RoomColors.Ink)){Text("走进音乐里的地点")}
        }}
    }
}
