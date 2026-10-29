import { createClient } from "npm:@supabase/supabase-js@2.49.8";
import { stripJpegMetadata, limitedText } from "./media.ts";
import { errorCode } from "./errors.ts";

const url = Deno.env.get("SUPABASE_URL")!;
const secret = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const admin = createClient(url, secret, { auth: { persistSession: false, autoRefreshToken: false } });
const cors = { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "authorization,apikey,content-type", "Access-Control-Allow-Methods": "GET,POST,OPTIONS" };
const guestActions = new Set(["catalog", "search", "config"]);
const allowedActions = new Set(["catalog","search","config","self","profile","content","nearby","submissions","admin",
 "profile_update","favorite","create_message","create_capsule","verify_visit","publish_recreation","update_content","delete_content","make_private",
 "like","report","submit","admin_status","admin_config","admin_hide","review_submission","resolve_report","revoke_badge",
 "upload","cancel_upload","delete_account"]);
class BusinessError extends Error { constructor(message: string, readonly status=400) { super(message); } }
const json = (body: unknown, status=200) => new Response(JSON.stringify(body), { status, headers: {...cors,"Content-Type":"application/json; charset=utf-8","Cache-Control":"no-store"} });
async function rpc(actor: string|null, action: string, payload: unknown={}) {
 const {data,error} = await admin.rpc("pilgrimage_v2",{actor,action,payload});
 if(error) {
  if(error.code==="PGRST202" || error.code==="42883") throw new BusinessError("V2 服务尚未部署，请稍后再试",503);
  if(error.code==="23505") throw new BusinessError("你已经发布过这条内容，请进入我的内容编辑",409);
  if(["23514","23503","22P02","22007","22003"].includes(error.code)) throw new BusinessError("提交的数据无效，请检查输入");
  if(error.code==="P0001") throw new BusinessError(error.message,error.message.includes('正在清理')?503:400);
  throw new BusinessError("服务暂时不可用，请稍后重试",503);
 }
 return data;
}
async function cleanup(actor: string) {
 const {data,error}=await admin.rpc("pilgrimage_v2_cleanup",{actor,operation:"pending"});
 if(error || !Array.isArray(data)) return;
 for(const asset of data) {
  const {error:removeError}=await admin.storage.from("user-media-v2").remove([asset.path]);
  if(!removeError) await admin.rpc("pilgrimage_v2_cleanup",{actor,operation:"removed",asset:asset.id});
 }
}
async function abortUpload(actor:string, asset:{id:string,path:string}) {
 // This privileged check prevents removing a photo that a parallel retry has
 // already associated. Missing reservations use only the server-issued path.
 const {data,error}=await admin.rpc("pilgrimage_v2_cleanup",{actor,operation:"abort_upload",asset:asset.id});
 if(error || !data || data.linked) return;
 const path=data.missing ? asset.path : data.path;
 if(typeof path!=="string" || path!==asset.path) return;
 const {error:removeError}=await admin.storage.from("user-media-v2").remove([path]);
 if(!removeError) await admin.rpc("pilgrimage_v2_cleanup",{actor,operation:"removed",asset:asset.id});
}
async function upload(actor: string, payload: Record<string,unknown>) {
 const value=payload.base64;
 if(typeof value!=="string" || value.length>550000 || !/^[A-Za-z0-9+/]*={0,2}$/.test(value)) throw new BusinessError("图片无效或超过400KiB");
 let bytes:Uint8Array;
 try { bytes=Uint8Array.from(atob(value),c=>c.charCodeAt(0)); } catch { throw new BusinessError("图片编码无效"); }
 try{bytes=stripJpegMetadata(bytes);}catch(error){throw new BusinessError((error as Error).message);}
 const sha256=Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256",bytes)),b=>b.toString(16).padStart(2,"0")).join("");
 await cleanup(actor);
 const asset=await rpc(actor,"media_reserve",{request_id:payload.request_id,size_bytes:bytes.length,sha256});
 if(asset.sha256!==sha256) throw new BusinessError("上传编号已经用于另一张图片，请重新选择");
 if(asset.state==="ready") return {id:asset.id};
 const {error}=await admin.storage.from("user-media-v2").upload(asset.path,bytes,{contentType:"image/jpeg",upsert:false});
 if(error && error.message!=="The resource already exists") {
  await abortUpload(actor,asset);
  throw new BusinessError("照片上传失败，草稿已保留，请重试",503);
 }
 try { await rpc(actor,"media_ready",{id:asset.id}); }
 catch(error) { await abortUpload(actor,asset); throw error; }
 return {id:asset.id};
}

Deno.serve(async (req:Request) => {
 if(req.method==="OPTIONS") return new Response(null,{headers:cors});
 try {
  const u=new URL(req.url);
  let action:string;let payload:Record<string,unknown>;
  if(req.method==="GET" && u.searchParams.has("media")) { action="media";payload={id:u.searchParams.get("media")}; }
  else if(req.method==="POST") {
   let body:string;
   try{body=await limitedText(req);}catch{throw new BusinessError("请求过大",413);}
   if(body.length>570000) throw new BusinessError("请求过大",413);
   let parsed;
   try {parsed=JSON.parse(body);} catch {throw new BusinessError("请求格式无效");}
   action=parsed.action;payload=parsed.payload??{};
   if(["challenges","scrapbook","answer"].includes(action)) throw new BusinessError("旧版挑战已下线",410);
   if(!allowedActions.has(action) || !payload || Array.isArray(payload) || typeof payload!=="object") throw new BusinessError("未知操作");
  } else throw new BusinessError("不支持的请求",405);
  let actor:string|null=null;
  const bearer=req.headers.get("Authorization")?.replace(/^Bearer\s+/i,"");
  if(bearer) {
   const {data,error}=await admin.auth.getUser(bearer);
   if(error || !data.user?.email_confirmed_at) throw new BusinessError("登录已过期，请重新登录并验证邮箱",401);
   actor=data.user.id;
  }
  if(!guestActions.has(action) && !actor) throw new BusinessError("请先登录并验证邮箱",401);
  if(action==="media") {
   const media=await rpc(actor,"media",payload);
   const {data,error}=await admin.storage.from("user-media-v2").download(media.path);
   if(error || !data) throw new BusinessError("图片已删除或暂时无法读取",404);
   return new Response(data,{headers:{...cors,"Content-Type":"image/jpeg","Cache-Control":"private, no-store","X-Content-Type-Options":"nosniff"}});
  }
  if(action==="upload") return json({data:await upload(actor!,payload)});
  if(action==="cancel_upload") {await rpc(actor,"media_cancel",payload);await cleanup(actor!);return json({data:{}});}
  if(action==="delete_account") {
   if(payload.confirmation!=="删除账号") throw new BusinessError("请输入“删除账号”确认");
   const {paths}=await rpc(actor,"account_begin");
   if(paths.length) {
    const {error}=await admin.storage.from("user-media-v2").remove(paths);
    if(error) throw new BusinessError("媒体清理未完成，账号已暂停，请重试注销",503);
   }
   const {error}=await admin.rpc("pilgrimage_v2_cleanup",{actor,operation:"account_finalize"});
   if(error) throw new BusinessError("数据清理未完成，请重试注销",503);
   const {error:authError}=await admin.auth.admin.deleteUser(actor!);
   if(authError) throw new BusinessError("账号最后清理步骤未完成，请重试注销",503);
   return json({data:{deleted:true}});
  }
  const data=await rpc(actor,action,payload);
  if(actor && ["delete_content","publish_recreation","update_content","self"].includes(action)) await cleanup(actor);
  return json({data});
 } catch(error) {
  if(error instanceof BusinessError) return json({error:error.message,code:errorCode(error.message,error.status)},error.status);
  return json({error:"服务暂时不可用，请稍后重试",code:"service_unavailable"},500);
 }
});
