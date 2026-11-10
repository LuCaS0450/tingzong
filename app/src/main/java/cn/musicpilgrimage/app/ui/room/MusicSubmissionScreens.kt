package cn.musicpilgrimage.app.ui.room

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.musicpilgrimage.app.FormViewModel
import cn.musicpilgrimage.app.PersonalViewModel
import cn.musicpilgrimage.app.data.ContentItem
import cn.musicpilgrimage.app.data.associationLabel
import cn.musicpilgrimage.app.data.stringOrNull
import cn.musicpilgrimage.app.ui.PrivatePhoto
import cn.musicpilgrimage.app.ui.ReferenceImage
import cn.musicpilgrimage.app.ui.ViewModelStatus
import org.json.JSONObject

@Composable internal fun RoomSubmissions(vm:PersonalViewModel,onAdmin:(()->Unit)?,onNew:()->Unit) {
    val submissions by vm.submissions.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    LaunchedEffect(vm){vm.submissions()}

    LazyColumn(Modifier.fillMaxSize().testTag("room-submissions"),
        contentPadding=PaddingValues(24.dp,14.dp,24.dp,28.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item {
            Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                SwissEyebrow("SUBMISSIONS")
                Text("发现新的音乐地点",fontSize=26.sp,lineHeight=34.sp,fontWeight=FontWeight.Medium,color=RoomColors.Ink)
                Text("提交地点与作品的关联，以及可核实的来源。",fontSize=14.sp,lineHeight=23.sp,color=RoomColors.Muted)
                SwissButton(onNew,Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("投稿新地点")}
                if(onAdmin!=null)SwissTextButton(onAdmin){Text("管理中心")}
            }
        }
        item {
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                SwissNumberedHeading("01","投稿记录")
                HorizontalDivider(color=RoomColors.Line)
                ViewModelStatus(vm)
                if(error!=null)SwissOutlinedButton(vm::submissions){Text("重试")}
                if(!busy&&error==null&&submissions.isEmpty()) {
                    Text("暂无投稿",fontSize=16.sp,fontWeight=FontWeight.Medium,color=RoomColors.Ink)
                    Text("提交后，可在这里查看审核进度。",fontSize=14.sp,lineHeight=23.sp,color=RoomColors.Muted)
                }
            }
        }
        items(submissions){submission->
            Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                val status=when(submission.optString("status")){
                    "pending"->"等待审核"
                    "approved"->"已采纳"
                    "rejected"->"未采纳"
                    else->"状态未更新"
                }
                SwissEyebrow(status)
                Text(submission.optString("place_name"),fontSize=20.sp,fontWeight=FontWeight.Medium,color=RoomColors.Ink)
                val description=submission.optString("description")
                if(description.isNotBlank())Text(description,fontSize=14.sp,lineHeight=23.sp,color=RoomColors.Ink)
                submission.stringOrNull("review_note")?.takeIf{it.isNotBlank()}?.let {
                    Text("审核说明",fontSize=12.sp,fontWeight=FontWeight.Medium,color=RoomColors.Muted)
                    Text(it,fontSize=14.sp,lineHeight=23.sp,color=RoomColors.Muted)
                }
                HorizontalDivider(Modifier.padding(top=6.dp),color=RoomColors.Line)
            }
        }
    }
}

@Composable internal fun RoomSubmissionForm(kind:String,target:String,editing:ContentItem?,vm:FormViewModel,onBack:()->Unit) {
    val fields by vm.fields.collectAsState()
    val busy by vm.busy.collectAsState()
    val ready by vm.initialized.collectAsState()
    val photo=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()){uri->uri?.let{vm.photo(it)}}
    LaunchedEffect(kind,target){vm.initialize(kind,target,editing)}

    Column(Modifier.fillMaxSize().testTag("room-submission-form")) {
        RoomHeader(if(kind=="correction")"信息修正" else "新地点投稿",onBack,onSpace={})
        Column(Modifier.fillMaxWidth().weight(1f).imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal=24.dp,vertical=16.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                SwissEyebrow("SUBMISSION")
                Text("草稿自动保存在本机",fontSize=14.sp,lineHeight=23.sp,color=RoomColors.Muted)
                ViewModelStatus(vm)
            }
            if(!ready)return@Column
            Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                SwissNumberedHeading("01","地点与作品")
                HorizontalDivider(color=RoomColors.Line)
                SubmissionField("place_name","地点名称",fields,vm,!busy)
                SubmissionField("title","相关作品",fields,vm,!busy)
                SubmissionField("artist","歌手",fields,vm,!busy)
                Text("关联类型",fontSize=14.sp,color=RoomColors.Ink)
                listOf(listOf("mv","album"),listOf("song_title","lyric")).forEach { row ->
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        row.forEach { type ->
                            val selected=fields.optString("association_type")==type
                            FilterChip(selected,{vm.set("association_type",type)},
                                label={Text(associationLabel(type))},modifier=Modifier.weight(1f),enabled=!busy,shape=RectangleShape,
                                colors=FilterChipDefaults.filterChipColors(selectedContainerColor=RoomColors.Ink,
                                    selectedLabelColor=RoomColors.Paper,selectedLeadingIconColor=RoomColors.Paper),
                                leadingIcon=if(selected){{Icon(Icons.Outlined.Check,null,Modifier.size(16.dp))}}else null)
                        }
                    }
                }
            }
            Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                SwissNumberedHeading("02","关联依据")
                HorizontalDivider(color=RoomColors.Line)
                SubmissionField("source_url","可核实的来源链接",fields,vm,!busy)
                SubmissionField("association_excerpt",if(fields.optString("association_type")=="lyric")"提到地点的短歌词" else "歌名或地点关联说明",fields,vm,!busy)
                SubmissionField("canonical_work_url","原作或正式发行封面链接（可选）",fields,vm,!busy)
                OutlinedTextField(fields.optString("body"),{vm.set("body",it.take(1000))},Modifier.fillMaxWidth(),
                    label={Text("投稿说明与核实依据")},minLines=4,enabled=!busy,shape=RectangleShape,
                    supportingText={Text("${fields.optString("body").length}/1000")})
            }
            Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                SwissNumberedHeading("03","图片")
                HorizontalDivider(color=RoomColors.Line)
                val local=fields.stringOrNull("local_photo")
                if(local!=null)ReferenceImage(java.io.File(local).toURI().toString(),"草稿照片",Modifier.fillMaxWidth().aspectRatio(4f/3f))
                else if(!fields.optBoolean("clear_media"))editing?.mediaId?.let{PrivatePhoto(it,vm.services,Modifier.fillMaxWidth().aspectRatio(4f/3f))}
                SwissOutlinedButton({photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))},
                    Modifier.fillMaxWidth().heightIn(min=48.dp),enabled=!busy) {
                    Text(if(local!=null||editing?.mediaId!=null)"更换照片" else "选择照片")
                }
                if(local!=null)SwissTextButton(vm::removePhoto,enabled=!busy){Text("移除照片")}
            }
            SwissButton({vm.submit(onBack)},Modifier.fillMaxWidth().heightIn(min=52.dp),enabled=!busy) {
                Text(if(busy)"正在提交…" else "保存并提交")
            }
        }
    }
}

@Composable private fun SubmissionField(key:String,label:String,fields:JSONObject,vm:FormViewModel,enabled:Boolean) {
    val url=key=="source_url"||key=="canonical_work_url"
    OutlinedTextField(fields.optString(key),{vm.set(key,it.take(if(key=="source_url")1000 else 100))},
        Modifier.fillMaxWidth(),label={Text(label)},singleLine=true,enabled=enabled,shape=RectangleShape,
        keyboardOptions=KeyboardOptions(keyboardType=if(url)KeyboardType.Uri else KeyboardType.Text))
}
