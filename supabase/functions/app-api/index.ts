import { ApiError, text, username, uuid, identifier, visibility, password, jpeg, base64, hash, numeric, evidence } from "./domain.ts";

// Service credentials exist only in the Edge runtime. Clients receive an ordinary user session.
type Row = Record<string, any>;
const URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const ANON = Deno.env.get("SUPABASE_ANON_KEY")!;
const MEDIA = "pilgrimage-media";
const REFERENCE = "reference-media";
const cors = { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "authorization,apikey,content-type,x-client-info", "Access-Control-Allow-Methods": "POST,OPTIONS" };
const query = (parameters: Record<string,string>) => new URLSearchParams(parameters).toString();
const internalEmail = (name: string) => `${name}@accounts.musicpilgrimage.invalid`;
function response(data: unknown, status = 200) { return new Response(JSON.stringify(data), { status, headers: { ...cors, "Content-Type": "application/json", "Cache-Control": "no-store" } }); }

async function service(path: string, method = "GET", body?: unknown, prefer?: string): Promise<any> {
  const headers: Record<string,string> = { apikey: SERVICE, Authorization: `Bearer ${SERVICE}` };
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (prefer) headers.Prefer = prefer;
  const result = await fetch(`${URL}${path}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const value = await result.text();
  if (!result.ok) {
    let message = "云端操作没有完成，请稍后重试";
    try { const parsed = JSON.parse(value); if (parsed.code === "P0001") message = parsed.message; if (parsed.code === "23505") message = "该编号或用户名已经存在"; } catch { /* do not return internal headers or credentials */ }
    throw new ApiError(result.status === 429 ? 429 : 400, message);
  }
  return value ? JSON.parse(value) : null;
}
async function rows(table: string, parameters: Record<string,string> = {}): Promise<Row[]> {
  return await service(`/rest/v1/${table}?${query({ select: "*", ...parameters })}`) as Row[];
}
async function allRows(table: string, parameters: Record<string,string> = {}): Promise<Row[]> {
  const result: Row[] = [];
  for (let offset = 0;; offset += 500) {
    const page = await rows(table, { ...parameters, limit: "500", offset: String(offset) });
    result.push(...page); if (page.length < 500) return result;
  }
}
async function write(table: string, method: string, parameters: Record<string,string>, body?: unknown, prefer = "return=representation"): Promise<Row[]> {
  return await service(`/rest/v1/${table}?${query(parameters)}`,method,body,prefer) as Row[];
}
async function rpc(name: string, parameters: Row): Promise<any> { return await service(`/rest/v1/rpc/${name}`,"POST",parameters); }
async function rate(key: string, limit: number, seconds: number): Promise<void> {
  if (!await rpc("consume_rate_limit", { p_key:key, p_limit:limit, p_seconds:seconds })) throw new ApiError(429,"操作太频繁，请稍后再试");
}
async function currentUser(request: Request): Promise<Row> {
  const token = request.headers.get("Authorization")?.replace(/^Bearer\s+/i,"");
  if (!token || token === SERVICE || token === ANON) throw new ApiError(401,"请登录受邀账号");
  const result = await fetch(`${URL}/auth/v1/user`, { headers: { apikey: ANON, Authorization: `Bearer ${token}` } });
  if (!result.ok) throw new ApiError(401,"登录已过期，请重新登录");
  const auth = await result.json();
  const profile = (await rows("profiles", {id:`eq.${uuid(auth.id)}`,limit:"1"}))[0];
  if (!profile?.active) throw new ApiError(403,"账号尚未受邀或已停用");
  return profile;
}
function admin(user: Row) { if (user.role !== "admin") throw new ApiError(403,"仅管理员可进行此操作"); }
async function login(payload: Row): Promise<Row> {
  const name = username(payload.username);
  if (typeof payload.password !== "string" || payload.password.length > 128) throw new ApiError(401,"用户名或密码不正确");
  // Account and global counters also apply when an attacker changes IP addresses.
  await rate("login:global",60,60);
  await rate(`login:${await hash(name)}`,10,300);
  const signed = await fetch(`${URL}/auth/v1/token?grant_type=password`, { method:"POST",headers:{apikey:ANON,"Content-Type":"application/json"},body:JSON.stringify({email:internalEmail(name),password:payload.password}) });
  if (!signed.ok) throw new ApiError(signed.status===429?429:401,"用户名或密码不正确，或登录暂时受限");
  const session = await signed.json();
  const profile = (await rows("profiles", {id:`eq.${uuid(session.user.id)}`,limit:"1"}))[0];
  if (!profile?.active) throw new ApiError(403,"账号尚未受邀或已停用");
  return {userId:profile.id,username:profile.username,displayName:profile.display_name,role:profile.role,accessToken:session.access_token,refreshToken:session.refresh_token,expiresAt:Date.now()+session.expires_in*1000};
}
function mapPlace(p: Row, scenes: Row[], artists: Row[]): Row {
  return {id:p.id,name:p.name,city:p.city,country:p.country,address:p.address,latitude:p.latitude,longitude:p.longitude,radiusMeters:p.radius_meters,precision:p.precision,description:p.description,coordinateSource:p.coordinate_source,verified:p.verified,
    scenes:scenes.filter(s=>s.place_id===p.id).map(s=>({id:s.id,artistId:s.artist_id,artistName:artists.find(a=>a.id===s.artist_id)?.name||"",work:s.work,kind:s.kind,timestampSeconds:s.timestamp_seconds,sourceUrl:s.source_url,imageUrl:s.image_url,imageCredit:s.image_credit,imageStatus:s.image_status,note:s.note}))};
}
async function accessibleCheckin(id: string, user: Row, circleOnly = false): Promise<Row> {
  const item = (await rows("checkins",{id:`eq.${id}`,limit:"1"}))[0];
  if (!item || (circleOnly ? item.visibility!=="circle" : item.user_id!==user.id && item.visibility!=="circle")) throw new ApiError(404,"这条分享不可访问");
  const owner = (await rows("profiles", {id:`eq.${item.user_id}`,active:"eq.true",limit:"1"}))[0];
  if (!owner) throw new ApiError(404,"这条分享不可访问");
  return item;
}
async function storageUpload(bucket: string, path: string, bytes: Uint8Array): Promise<void> {
  const result = await fetch(`${URL}/storage/v1/object/${bucket}/${path}`, {method:"POST",headers:{apikey:SERVICE,Authorization:`Bearer ${SERVICE}`,"Content-Type":"image/jpeg","x-upsert":"true"},body:bytes as BodyInit});
  if (!result.ok) throw new ApiError(result.status===429?429:503,"照片上传未完成，请稍后重试");
}
async function deleteCheckin(id: string, user: Row, byAdmin: boolean): Promise<Row> {
  if (byAdmin) admin(user);
  const path=await rpc("delete_checkin_checked",{p_user:user.id,p_id:id,p_admin:byAdmin});
  const owner=path.split("/")[0];
  // DB transaction stores the authorized owner in a private tombstone before hiding the post.
  await service(`/storage/v1/object/${MEDIA}`,"DELETE",{prefixes:[path]});
  await write("media_assets","DELETE",{path:`eq.${path}`,user_id:`eq.${owner}`});
  return {deleted:true};
}
function validatePlace(p: Row): void {
  identifier(p.id); text(p.name,"地点名称",150); text(p.city,"城市",100); text(p.country,"国家地区",100);
  numeric(p.latitude,"纬度",-90,90);numeric(p.longitude,"经度",-180,180);numeric(p.radiusMeters,"打卡范围",25,1000);
  if (!Number.isInteger(p.radiusMeters)) throw new ApiError(400,"打卡范围必须为整数");
  if (!Array.isArray(p.scenes) || p.scenes.length<1 || p.scenes.length>20) throw new ApiError(400,"需关联1–20个作品");
  for (const s of p.scenes) {
    identifier(s.id);identifier(s.artistId);text(s.work,"作品名称",150);
    if (!['mv','album'].includes(s.kind)||!text(s.sourceUrl,"来源链接",2000).startsWith("https://")) throw new ApiError(400,"作品类型或来源无效");
    if (s.imageUrl && (!text(s.imageUrl,"图片链接",2000).startsWith("https://") || !s.imageCredit?.trim())) throw new ApiError(400,"参考图片须填写HTTPS链接及来源授权说明");
  }
}

async function dispatch(action: string, p: Row, user: Row): Promise<unknown> {
  await rate(`api:${user.id}`,180,60);
  switch(action) {
    case "catalog": {
      const [artists,places,scenes] = await Promise.all([allRows("artists",{order:"name.asc"}),allRows("places",{verified:"eq.true",order:"id.asc"}),allRows("scenes",{order:"id.asc"})]);
      return {artists,places:places.map(x=>mapPlace(x,scenes,artists))};
    }
    case "favorites": return (await allRows("favorites",{user_id:`eq.${user.id}`,order:"place_id.asc"})).map(x=>({placeId:x.place_id}));
    case "favorite": {
      const placeId=identifier(p.placeId); if(typeof p.enabled!=="boolean") throw new ApiError(400,"收藏状态无效");
      if(p.enabled) await write("favorites","POST",{on_conflict:"user_id,place_id"},{user_id:user.id,place_id:placeId},"resolution=merge-duplicates,return=representation");
      else await write("favorites","DELETE",{user_id:`eq.${user.id}`,place_id:`eq.${placeId}`}); return {saved:true};
    }
    case "upload_photo": {
      const id=uuid(p.checkinId),bytes=jpeg(p.base64);await rate(`photo:${user.id}`,15,60);
      const path=await rpc("reserve_photo",{p_user:user.id,p_checkin:id,p_size:bytes.length,p_hash:await hash(bytes)});
      await storageUpload(MEDIA,path,bytes);return {path};
    }
    case "download_photo": {
      const path=text(p.path,"照片路径",100);
      if(!/^[0-9a-f-]{36}\/[0-9a-f-]{36}\.jpg$/.test(path)) throw new ApiError(400,"照片路径无效");
      const found=(await rows("checkins",{photo_path:`eq.${path}`,limit:"1"}))[0];
      if(!found) throw new ApiError(404,"照片不可访问"); await accessibleCheckin(found.id,user);
      const result=await fetch(`${URL}/storage/v1/object/${MEDIA}/${path}`,{headers:{apikey:SERVICE,Authorization:`Bearer ${SERVICE}`}});
      if(!result.ok) throw new ApiError(404,"照片暂时不可访问");
      const bytes=new Uint8Array(await result.arrayBuffer());if(bytes.length>409600) throw new ApiError(400,"图片超出限制");return {base64:base64(bytes)};
    }
    case "create_checkin": {
      uuid(p.id);identifier(p.placeId);text(p.note??"","感想",2000,false);visibility(p.visibility);evidence(p);
      const id=await rpc("create_checkin_checked",{p_user:user.id,p_data:p});return {id};
    }
    case "update_checkin": {
      const id=uuid(p.id);const result=await write("checkins","PATCH",{id:`eq.${id}`,user_id:`eq.${user.id}`},{note:text(p.note,"感想",2000,false),visibility:visibility(p.visibility),updated_at:new Date().toISOString()});
      if(!result?.length) throw new ApiError(404,"记录不存在");return {id};
    }
    case "delete_checkin": return await deleteCheckin(uuid(p.id),user,false);
    case "admin_delete_checkin": return await deleteCheckin(uuid(p.id??p.checkinId),user,true);
    case "my_checkins": {
      return await rpc("my_checkins_snapshot",{p_user:user.id});
    }
    case "feed": {
      const placeId=identifier(p.placeId);const checks=await rows("checkins",{place_id:`eq.${placeId}`,visibility:"eq.circle",order:"client_created_at.desc",limit:"50"});
      if(!checks.length)return [];
      const ids=checks.map(c=>c.id).join(",");const [profiles,likes,comments]=await Promise.all([allRows("profiles",{active:"eq.true",order:"id.asc"}),allRows("likes",{checkin_id:`in.(${ids})`,order:"checkin_id.asc,user_id.asc"}),allRows("comments",{checkin_id:`in.(${ids})`,order:"created_at.asc,id.asc"})]);
      return checks.filter(c=>profiles.some(x=>x.id===c.user_id)).map(c=>({id:c.id,userId:c.user_id,displayName:profiles.find(x=>x.id===c.user_id)?.display_name||"同行者",placeId:c.place_id,note:c.note,photoPath:c.photo_path,createdAt:c.client_created_at,likeCount:likes.filter(x=>x.checkin_id===c.id).length,liked:likes.some(x=>x.checkin_id===c.id&&x.user_id===user.id),comments:comments.filter(x=>x.checkin_id===c.id&&profiles.some(a=>a.id===x.user_id)).slice(-100).map(x=>({id:x.id,userId:x.user_id,displayName:profiles.find(a=>a.id===x.user_id)?.display_name||"同行者",body:x.body,createdAt:x.created_at}))}));
    }
    case "like": {
      const id=uuid(p.checkinId);await accessibleCheckin(id,user,true);
      const previous=await rows("likes",{user_id:`eq.${user.id}`,checkin_id:`eq.${id}`,limit:"1"});
      if(previous.length)await write("likes","DELETE",{user_id:`eq.${user.id}`,checkin_id:`eq.${id}`});
      else await write("likes","POST",{on_conflict:"user_id,checkin_id"},{user_id:user.id,checkin_id:id},"resolution=ignore-duplicates,return=representation");return {liked:!previous.length};
    }
    case "comment": {
      const id=uuid(p.checkinId);await accessibleCheckin(id,user,true);await rate(`comment:${user.id}`,12,60);
      return (await write("comments","POST",{},{user_id:user.id,checkin_id:id,body:text(p.body,"评论",500)}))[0];
    }
    case "delete_comment": {
      const params:Record<string,string>={id:`eq.${uuid(p.id)}`};if(user.role!=="admin")params.user_id=`eq.${user.id}`;
      await write("comments","DELETE",params);return {deleted:true};
    }
    case "submit_place": {
      await rate(`submit:${user.id}`,10,86400);
      return (await write("submissions","POST",{},{user_id:user.id,title:text(p.title,"投稿标题",120),details:text(p.details,"投稿内容",6000)}))[0];
    }
    case "submissions": {
      const params:Record<string,string>={order:"created_at.desc,id.asc"};if(user.role!=="admin")params.user_id=`eq.${user.id}`;
      return (await allRows("submissions",params)).map(s=>({id:s.id,userId:s.user_id,title:s.title,details:s.details,status:s.status,reviewNote:s.review_note,publishPlaceId:s.publish_place_id}));
    }
    case "review_submission": {
      admin(user);if(typeof p.approve!=="boolean")throw new ApiError(400,"审核结果无效");
      const changes:Row={status:p.approve?"approved":"rejected",review_note:text(p.reviewNote??"","审核备注",2000,false),reviewed_at:new Date().toISOString()};
      if(p.publishPlaceId)changes.publish_place_id=identifier(p.publishPlaceId);
      await write("submissions","PATCH",{id:`eq.${uuid(p.id)}`},changes);return {reviewed:true,published:false};
    }
    case "admin_create_user": {
      admin(user);const name=username(p.username),pass=password(p.password),display=text(p.displayName,"昵称",60);
      if((await rows("profiles",{username:`eq.${name}`,limit:"1"})).length)throw new ApiError(400,"用户名已存在");
      const created=await service("/auth/v1/admin/users","POST",{email:internalEmail(name),password:pass,email_confirm:true,user_metadata:{display_name:display}});
      try {await write("profiles","POST",{},{id:created.id,username:name,display_name:display,role:"member",active:true});}
      catch(error){await service(`/auth/v1/admin/users/${created.id}`,"DELETE");throw error;}
      return {userId:created.id,username:name};
    }
    case "admin_reset_password": {
      admin(user);const name=username(p.username),pass=password(p.password);const profile=(await rows("profiles",{username:`eq.${name}`,limit:"1"}))[0];
      if(!profile)throw new ApiError(404,"账号不存在");await service(`/auth/v1/admin/users/${profile.id}`,"PUT",{password:pass});return {reset:true};
    }
    case "admin_save_place": {admin(user);validatePlace(p);return {id:await rpc("save_place",{p_data:p})};}
    case "admin_upload_reference": {
      admin(user);const sceneId=identifier(p.sceneId),bytes=jpeg(p.base64);await rate(`reference:${user.id}`,15,60);
      const path=`${sceneId}/${crypto.randomUUID()}.jpg`;
      await rpc("reserve_reference",{p_path:path,p_size:bytes.length});
      await storageUpload(REFERENCE,path,bytes);
      return {url:`${URL}/storage/v1/object/public/${REFERENCE}/${path}`};
    }
    default: throw new ApiError(400,"不支持的操作");
  }
}

Deno.serve(async(request:Request)=>{
  if(request.method==="OPTIONS")return new Response(null,{status:204,headers:cors});
  if(request.method!=="POST")return response({error:"仅支持POST"},405);
  try {
    if(!URL||!SERVICE||!ANON)throw new ApiError(503,"云服务尚未完成配置");
    const announced=Number(request.headers.get("Content-Length")||0);if(announced>570000)throw new ApiError(413,"请求过大");
    // Limit the stream too; an omitted Content-Length must not bypass the bound.
    const reader=request.body?.getReader();if(!reader)throw new ApiError(400,"请求内容为空");
    const chunks:Uint8Array[]=[];let size=0;
    while(true){const {done,value}=await reader.read();if(done)break;size+=value.byteLength;if(size>570000){await reader.cancel();throw new ApiError(413,"请求过大");}chunks.push(value);}
    const body=new Uint8Array(size);let offset=0;for(const chunk of chunks){body.set(chunk,offset);offset+=chunk.length;}
    let decoded:Row;try{decoded=JSON.parse(new TextDecoder().decode(body));}catch{throw new ApiError(400,"请求格式无效");}
    const action=text(decoded.action,"操作",60),payload=decoded.payload;
    if(!payload||typeof payload!=="object"||Array.isArray(payload))throw new ApiError(400,"参数格式无效");
    const data=action==="login"?await login(payload):await dispatch(action,payload,await currentUser(request));
    return response({data});
  } catch(error) {
    if(error instanceof ApiError)return response({error:error.message},error.status);
    console.error("app-api unexpected failure",error instanceof Error?error.name:"unknown");
    return response({error:"服务暂时不可用，请稍后重试"},500);
  }
});
