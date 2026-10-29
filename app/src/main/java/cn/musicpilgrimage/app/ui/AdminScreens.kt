package cn.musicpilgrimage.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.unit.dp
import cn.musicpilgrimage.app.*
import cn.musicpilgrimage.app.data.*
import org.json.JSONObject

@Composable internal fun SubmissionsScreen(vm:PersonalViewModel,onBack:()->Unit,onNew:()->Unit) {
 val list by vm.submissions.collectAsState()
 val busy by vm.busy.collectAsState();val error by vm.error.collectAsState()
 LaunchedEffect(Unit){vm.submissions()}
 Column{LazyColumn(contentPadding=PaddingValues(20.dp)){
  item{ViewModelStatus(vm);if(error!=null)OutlinedButton(vm::submissions){Text("重试")};Button(onNew){Text("投稿新地点")};if(!busy&&error==null&&list.isEmpty())StatusCard("暂无投稿")}
  items(list){s->Column(Modifier.padding(vertical=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(s.optString("place_name"),style=MaterialTheme.typography.titleLarge);Text(s.optString("description"));Text(mapOf("pending" to "等待审核","approved" to "已采纳","rejected" to "未采纳")[s.optString("status")].orEmpty(),color=Acid);Text(s.optString("review_note"),color=Muted);HorizontalDivider(color=Line)}}
 }}
}

@Composable internal fun AdminScreen(vm:AdminViewModel,onBack:()->Unit) {
 val data by vm.data.collectAsState();val busy by vm.busy.collectAsState();var selected by remember{mutableStateOf<JSONObject?>(null)}
 var location by remember{mutableStateOf("")};var note by remember{mutableStateOf("")}
 var revoke by remember{mutableStateOf<JSONObject?>(null)}
 LaunchedEffect(Unit){vm.load()}
 Column{LazyColumn(contentPadding=PaddingValues(20.dp)){
  item{ViewModelStatus(vm);TextButton(vm::load){Text("刷新")};SectionTitle("服务开关")}
  val config=data?.optJSONObject("config")
  items(listOf("registration_enabled" to "新用户注册","image_upload_enabled" to "图片上传","submission_enabled" to "地点投稿","maintenance_mode" to "维护模式")){(key,title)->Row(Modifier.fillMaxWidth().padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically){Text(title,Modifier.weight(1f));Switch(config?.optBoolean(key)==true,{vm.act("admin_config",JSONObject().put(key,it))},enabled=!busy&&config!=null)}}
  item{Text("已记录图片 ${(data?.optLong("storage_bytes")?:0)/1024/1024} MiB",color=Muted);SectionTitle("待审投稿")}
  items(data?.optJSONArray("submissions")?.objects()?.filter{it.optString("status")=="pending"}.orEmpty()){s->Column(Modifier.padding(vertical=12.dp)){Text(s.optString("place_name"),style=MaterialTheme.typography.titleLarge);Text(associationLabel(s.optString("association_type")),color=Acid,style=MaterialTheme.typography.bodySmall);s.stringOrNull("association_excerpt")?.let{Text(it)};Text(s.optString("description"));ExternalLink("投稿来源",s.optString("source_url"));s.stringOrNull("canonical_work_url")?.let{ExternalLink("原作版本",it)};TextButton({selected=s;location="";note=""}){Text("关联目录并审核")};HorizontalDivider(color=Line)}}
  item{SectionTitle("举报处理")}
  items(data?.optJSONArray("reports")?.objects()?.filter{it.optString("status")=="pending"}.orEmpty()){r->Column(Modifier.padding(vertical=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
   Text("${r.optString("reason")} · 版本 ${r.optInt("target_version")}",style=MaterialTheme.typography.titleLarge)
   Text(r.optJSONObject("snapshot")?.optString("body").orEmpty());Text("内容编号 ${r.optString("target_id")}",style=MaterialTheme.typography.bodySmall,color=Muted)
   TextButton({vm.act("admin_hide",JSONObject().put("id",r.optString("target_id")).put("hidden",true))},enabled=!busy){Text("隐藏当前公开内容")}
   Row{TextButton({vm.act("resolve_report",JSONObject().put("id",r.optString("id")).put("status","resolved"))},enabled=!busy){Text("标记已处理")};TextButton({vm.act("resolve_report",JSONObject().put("id",r.optString("id")).put("status","dismissed"))},enabled=!busy){Text("驳回举报")}}
   HorizontalDivider(color=Line)
  }}
  item{SectionTitle("已隐藏的公开内容")}
  items(data?.optJSONArray("hidden")?.objects().orEmpty()){c->Column{Text(c.optString("body"));TextButton({vm.act("admin_hide",JSONObject().put("id",c.optString("id")).put("hidden",false))},enabled=!busy){Text("恢复公开展示")};HorizontalDivider(color=Line)}}
  item{SectionTitle("用户状态")}
  items(data?.optJSONArray("users")?.objects().orEmpty()){u->Row(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically){Text(u.optString("nickname").ifBlank{"未设置昵称"},Modifier.weight(1f));if(u.optString("role")=="admin")Text("管理员",color=Muted) else TextButton({vm.act("admin_status",JSONObject().put("id",u.optString("id")).put("disabled",u.optString("status")!="disabled"))},enabled=!busy){Text(if(u.optString("status")=="disabled")"恢复账号" else "停用账号")}}}
  item{SectionTitle("徽章管理");Text("仅撤销违规取得的成就；撤销后不会自动重复发放。",color=Muted)}
  items(data?.optJSONArray("awards")?.objects().orEmpty()){award->
   val user=data?.optJSONArray("users")?.objects()?.find{it.optString("id")==award.optString("user_id")}
   val badge=data?.optJSONArray("badges")?.objects()?.find{it.optString("id")==award.optString("badge_id")}
   ActionRow(Icons.Rounded.Verified,"${user?.optString("nickname").orEmpty()} · ${badge?.optString("name").orEmpty()}","撤销这枚徽章"){if(!busy)revoke=award}
  }
 }}
 revoke?.let{award->AlertDialog(onDismissRequest={revoke=null},title={Text("撤销违规徽章？")},text={Text("系统会记录这次管理操作。普通内容删除不会触发此操作。")},confirmButton={TextButton({vm.act("revoke_badge",JSONObject().put("user_id",award.optString("user_id")).put("badge_id",award.optString("badge_id")));revoke=null}){Text("确认撤销")}},dismissButton={TextButton({revoke=null}){Text("取消")}})}
 selected?.let{s->AlertDialog(onDismissRequest={selected=null},title={Text("审核投稿")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){Text("填写已核实的目录地点编号。");OutlinedTextField(location,{location=it},label={Text("地点编号")});OutlinedTextField(note,{note=it.take(500)},label={Text("审核说明")})}},confirmButton={TextButton({vm.act("review_submission",JSONObject().put("id",s.getString("id")).put("status","approved").put("linked_location_id",location).put("review_note",note));selected=null},enabled=location.isNotBlank()&&!busy){Text("审核通过")}},dismissButton={TextButton({vm.act("review_submission",JSONObject().put("id",s.getString("id")).put("status","rejected").put("review_note",note));selected=null},enabled=!busy){Text("不予采纳")}})}
}
