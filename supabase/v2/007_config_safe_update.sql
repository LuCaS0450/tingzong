-- Incremental repair: PostgREST enforces safe updates. Select the singleton row
-- explicitly; preserve the original business rules and function ACL.
begin;
set local search_path=v2,extensions,public;
create or replace function write_api(actor uuid,action text,p jsonb) returns jsonb language plpgsql security definer set search_path=v2,extensions,public as $$
declare me profiles;cfg app_config; c content; l locations; s scenes; asset uuid;old_asset uuid;fix geography;dist double precision;
 new_id uuid; q questions;a challenge_attempts;result jsonb;correct boolean;sub submissions;n bigint;cnt integer;
begin
 select * into me from profiles where profiles.id=actor;
 if action<>'account_begin' then me=require_member(actor); elsif me.id is null then raise exception '请先登录';end if;
 select * into cfg from app_config;
 if cfg.maintenance_mode and me.role<>'admin' and action not in ('account_begin','account_finalize') then raise exception '服务维护中，请稍后再试';end if;
 if action not in ('favorite','delete_content','make_private','account_begin','profile_update','admin_status','admin_config','admin_hide','review_submission','resolve_report','revoke_badge','media_reserve','media_ready','media_cancel') then perform require_member(actor,true);end if;
 perform throttle(actor::text||':'||action,60,60);
 if action='profile_update' then
  if me.status<>'active' then raise exception '账号已停用';end if;
  if length(trim(coalesce(p->>'nickname',''))) not between 2 and 32 or length(coalesce(p->>'bio',''))>200 then raise exception '昵称需2–32字，简介最多200字';end if;
  update profiles set nickname=trim(p->>'nickname'),bio=coalesce(p->>'bio','') where profiles.id=actor;return read_api(actor,'self','{}');
 end if;
 if action='favorite' then
  if coalesce((p->>'enabled')::boolean,false) then insert into favorites values(actor,p->>'location_id') on conflict do nothing;
  else delete from favorites where user_id=actor and location_id=p->>'location_id';end if;return '{}'::jsonb;
 end if;
 if action='create_message' or action='create_capsule' then
  new_id=(p->>'id')::uuid;
  if new_id is null then raise exception '缺少提交编号';end if;
  if exists(select 1 from content where content.id=new_id and user_id=actor) then return (select content_json(x,actor) from content x where x.id=new_id);end if;
  if length(trim(coalesce(p->>'body',''))) not between 1 and 1000 then raise exception '请填写1–1000字内容';end if;
  if action='create_capsule' then
   if not cfg.capsule_creation_enabled then raise exception '胶囊创建暂时关闭';end if;
   if length(trim(coalesce(p->>'song','')))=0 or length(trim(coalesce(p->>'artist','')))=0 or length(trim(coalesce(p->>'place_name','')))=0 then raise exception '请填写歌曲、歌手和地点名称';end if;
   fix=validate_fix(p->'fix');
   if not ((p->>'latitude')::double precision between -90 and 90 and (p->>'longitude')::double precision between -180 and 180) then raise exception '胶囊坐标无效';end if;
   perform pg_advisory_xact_lock(hashtextextended(actor::text,0));
   if st_distance(fix,st_setsrid(st_makepoint((p->>'longitude')::double precision,(p->>'latitude')::double precision),4326)::geography)>100 then raise exception '胶囊位置不能偏离当前位置100米以上';end if;
   if exists(select 1 from content where user_id=actor and kind='capsule' and st_distance(geo,st_setsrid(st_makepoint((p->>'longitude')::double precision,(p->>'latitude')::double precision),4326)::geography)<100) then raise exception '你已经在这里留下过音乐胶囊，请在我的胶囊中编辑';end if;
  end if;
  insert into content(id,kind,user_id,location_id,body,song,artist,place_name,latitude,longitude,visibility)
  values(new_id,case when action='create_message' then 'message' else 'capsule' end,actor,
  case when action='create_message' then p->>'location_id' end,trim(p->>'body'),coalesce(p->>'song',''),coalesce(p->>'artist',''),coalesce(p->>'place_name',''),
  case when action='create_capsule' then (p->>'latitude')::double precision end,case when action='create_capsule' then (p->>'longitude')::double precision end,'public') returning * into c;
  perform award(actor,case when action='create_message' then 'first_message' else 'first_capsule' end);return content_json(c,actor);
 end if;
 if action in ('verify_visit','publish_recreation') then
  asset=nullif(p->>'media_id','')::uuid;
  if action='publish_recreation' then
   select * into s from scenes where scenes.id=p->>'scene_id';
   if s.id is null or s.image_url is null then raise exception '此场景尚未补齐原作参考图';end if;
   select * into l from locations where locations.id=s.location_id and verified;
  else select * into l from locations where locations.id=p->>'location_id' and verified;end if;
  if l.id is null then raise exception '地点不存在';end if;
  perform pg_advisory_xact_lock(hashtextextended(actor::text||l.id,0));
  if action='publish_recreation' then
   select * into c from content where user_id=actor and kind='recreation' and scene_id=s.id for update;
   if c.is_hidden then raise exception '此内容已隐藏，请联系管理员';end if;
   if asset is null then raise exception '请选择一张复刻照片';end if;
  else
   select * into c from content where user_id=actor and kind='visit' and location_id=l.id for update;
   if c.is_hidden then raise exception '此内容已隐藏，请联系管理员';end if;
  end if;
  if asset is not null and c.media_id=asset then return content_json(c,actor);end if;
  perform check_media(actor,asset);
  if c.id is null then
   fix=validate_fix(p->'fix');dist=st_distance(fix,l.geo);
   if dist>l.checkin_radius_m then raise exception '还没有进入该地点的认证范围';end if;
   insert into content(kind,user_id,location_id,verified_at,accuracy_m,distance_m)
   values('visit',actor,l.id,now(),(p->'fix'->>'accuracy')::double precision,dist)
   on conflict(user_id,location_id) where kind='visit' do nothing;
   perform award(actor,'first_visit');
  end if;
  if action='verify_visit' then
   old_asset=c.media_id;
   update content set body=coalesce(p->>'body',''),media_id=coalesce(asset,media_id),visibility=coalesce(p->>'visibility','private'),updated_at=now()
   where user_id=actor and location_id=l.id and kind='visit' and not is_hidden returning * into c;
   if asset is not null and old_asset is distinct from asset then perform retire_media(old_asset);delete from likes where content_id=c.id;end if;
  elsif c.id is null then
   insert into content(kind,user_id,location_id,scene_id,media_id,body,visibility,verified_at,accuracy_m,distance_m)
   values('recreation',actor,l.id,s.id,asset,coalesce(p->>'body',''),coalesce(p->>'visibility','private'),now(),(p->'fix'->>'accuracy')::double precision,dist) returning * into c;
   perform award(actor,'first_recreation');
  else
   old_asset=c.media_id;
   delete from likes where content_id=c.id;
   update content set media_id=asset,body=coalesce(p->>'body',body),visibility=coalesce(p->>'visibility',visibility),version=version+1,updated_at=now() where content.id=c.id returning * into c;
   perform retire_media(old_asset);
  end if;
  return content_json(c,actor);
 end if;
 if action in ('update_content','delete_content','make_private','like','report') then
  select * into c from content where content.id=(p->>'id')::uuid for update;
  if c.id is null then if action='delete_content' then return '{}';end if;raise exception '内容不存在';end if;
  if action in ('update_content','delete_content','make_private') then
   if c.user_id is distinct from actor then raise exception '只能管理自己的内容';end if;
   if action='delete_content' then
    perform retire_media(c.media_id);
    if c.kind='visit' then update content set body='',media_id=null,visibility='private',version=version+1,updated_at=now() where content.id=c.id;delete from likes where content_id=c.id;
    else delete from content where content.id=c.id;end if;return '{}';
   end if;
   if action='make_private' then
    if c.kind not in ('visit','recreation') then raise exception '此内容不支持私密';end if;
    update content set visibility='private',updated_at=now() where content.id=c.id;return '{}';
   end if;
   if c.is_hidden then raise exception '此内容已隐藏，不能自行修改或恢复';end if;
   if c.kind in ('message','capsule') and length(trim(coalesce(p->>'body',c.body)))=0 then raise exception '内容不能为空';end if;
   if c.kind='capsule' and (length(trim(coalesce(p->>'song',c.song)))=0 or length(trim(coalesce(p->>'artist',c.artist)))=0 or length(trim(coalesce(p->>'place_name',c.place_name)))=0) then raise exception '胶囊歌曲、歌手和地点名称不能为空';end if;
   asset=nullif(p->>'media_id','')::uuid;
   if asset is not null and asset is distinct from c.media_id then
    if c.kind<>'visit' then raise exception '请使用复刻替换入口更新图片';end if;
    perform check_media(actor,asset);perform retire_media(c.media_id);delete from likes where content_id=c.id;
   end if;
   if length(coalesce(p->>'body',c.body))>1000 then raise exception '文字最多1000字';end if;
   if coalesce((p->>'clear_media')::boolean,false) and c.kind='visit' then
    perform retire_media(c.media_id);update content set media_id=null where content.id=c.id;delete from likes where content_id=c.id;
   end if;
   update content set body=coalesce(p->>'body',body),song=coalesce(p->>'song',song),artist=coalesce(p->>'artist',artist),
    place_name=coalesce(p->>'place_name',place_name),media_id=coalesce(asset,media_id),
    visibility=case when kind in ('message','capsule') then 'public' else coalesce(p->>'visibility',visibility) end,version=version+1,updated_at=now() where content.id=c.id returning * into c;
   return content_json(c,actor);
  end if;
  if not visible(c,actor) or c.is_hidden or c.visibility<>'public' then raise exception '此内容不能互动';end if;
  if action='like' then
   if coalesce((p->>'enabled')::boolean,false) then insert into likes values(actor,c.id) on conflict do nothing;else delete from likes where user_id=actor and content_id=c.id;end if;return '{}';
  end if;
  insert into reports(reporter_id,target_id,target_version,target_kind,reason,snapshot) values(actor,c.id,c.version,c.kind,p->>'reason',jsonb_build_object('body',c.body,'song',c.song,'kind',c.kind,'media_id',c.media_id)) on conflict do nothing;return '{}';
 end if;
 if action='answer' then
  if not cfg.challenge_enabled then raise exception '挑战暂时关闭';end if;
  select * into q from questions where questions.id=p->>'question_id' and published and exists(select 1 from challenge_schedule where question_id=questions.id and challenge_date<=(now() at time zone 'Asia/Shanghai')::date);
  if q.id is null then raise exception '题目尚未发布';end if;
  if q.challenge_type='multiple_choice' then
   if not exists(select 1 from jsonb_array_elements(q.options) o where o->>'id'=p->>'option') then raise exception '请选择一个选项';end if;
   correct=q.correct_option=p->>'option';
  else
   if not ((p->>'latitude')::double precision between -90 and 90 and (p->>'longitude')::double precision between -180 and 180) or p->>'latitude' is null or p->>'longitude' is null then raise exception '请在地图上选择位置';end if;
   dist=st_distance(st_setsrid(st_makepoint((p->>'longitude')::double precision,(p->>'latitude')::double precision),4326)::geography,st_setsrid(st_makepoint(q.answer_longitude,q.answer_latitude),4326)::geography);
   correct=dist<=q.correct_radius_m;
  end if;
  result=jsonb_build_object('correct',correct,'distance_m',dist,'correct_option',q.correct_option,'answer_latitude',q.answer_latitude,'answer_longitude',q.answer_longitude,'explanation',q.explanation,'source_url',q.source_url);
  insert into challenge_attempts values(actor,q.id,p-'fix',result,correct,now()) on conflict do nothing;
  get diagnostics cnt=row_count;
  if (select count(*) from challenge_attempts where user_id=actor)>=10 then perform award(actor,'ten_challenges');end if;
  if (select count(*) from challenge_attempts where user_id=actor and challenge_attempts.correct)>=10 then perform award(actor,'ten_correct');end if;
  return result||jsonb_build_object('practice',cnt=0);
 end if;
 if action='submit' then
  if not cfg.submission_enabled then raise exception '投稿暂时关闭';end if;
  if exists(select 1 from submissions where submissions.id=(p->>'id')::uuid and user_id=actor) then return '{}';end if;
  if length(trim(coalesce(p->>'artist','')))=0 or length(trim(coalesce(p->>'title','')))=0 or length(trim(coalesce(p->>'place_name','')))=0 then raise exception '请填写歌手、作品和地点';end if;
  asset=nullif(p->>'media_id','')::uuid;perform check_media(actor,asset);
  insert into submissions(id,user_id,submission_type,location_id,artist,title,place_name,description,source_url,latitude,longitude,media_id)
  values((p->>'id')::uuid,actor,p->>'submission_type',nullif(p->>'location_id',''),left(p->>'artist',100),left(p->>'title',100),left(p->>'place_name',100),left(coalesce(p->>'description',''),2000),left(coalesce(p->>'source_url',''),1000),nullif(p->>'latitude','')::double precision,nullif(p->>'longitude','')::double precision,asset)
  on conflict(id) do nothing;return '{}';
 end if;
 if action in ('admin_status','admin_config','admin_hide','review_submission','resolve_report','revoke_badge') then
  if me.role<>'admin' or me.status<>'active' then raise exception '需要管理员权限';end if;
  if action='admin_status' then
   if (p->>'id')::uuid=actor or exists(select 1 from profiles where profiles.id=(p->>'id')::uuid and role='admin') then raise exception '不能在App中停用管理员';end if;
   update profiles set status=case when (p->>'disabled')::boolean then 'disabled' else 'active' end where profiles.id=(p->>'id')::uuid;
  elsif action='admin_config' then
   update app_config set registration_enabled=coalesce((p->>'registration_enabled')::boolean,registration_enabled),image_upload_enabled=coalesce((p->>'image_upload_enabled')::boolean,image_upload_enabled),
    capsule_creation_enabled=coalesce((p->>'capsule_creation_enabled')::boolean,capsule_creation_enabled),submission_enabled=coalesce((p->>'submission_enabled')::boolean,submission_enabled),
    challenge_enabled=coalesce((p->>'challenge_enabled')::boolean,challenge_enabled),maintenance_mode=coalesce((p->>'maintenance_mode')::boolean,maintenance_mode) where app_config.id=true;
  elsif action='admin_hide' then update content set is_hidden=(p->>'hidden')::boolean where content.id=(p->>'id')::uuid and visibility='public';
  elsif action='review_submission' then
   select * into sub from submissions where submissions.id=(p->>'id')::uuid for update;
   if sub.status<>'pending' then raise exception '投稿已处理';end if;
   if p->>'status'='approved' and not exists(select 1 from locations where locations.id=p->>'linked_location_id' and verified) then raise exception '请先在Dashboard录入目录并提供有效地点编号';end if;
   if p->>'status' not in ('approved','rejected') then raise exception '审核状态无效';end if;
   update submissions set status=p->>'status',linked_location_id=nullif(p->>'linked_location_id',''),review_note=left(coalesce(p->>'review_note',''),500) where submissions.id=sub.id;
   if p->>'status'='approved' and sub.user_id is not null then perform award(sub.user_id,'first_submission');end if;
  elsif action='resolve_report' then update reports set status=p->>'status' where reports.id=(p->>'id')::uuid;
  else
   delete from user_badges where user_id=(p->>'user_id')::uuid and badge_id=p->>'badge_id';
   insert into badge_revocations(user_id,badge_id) values((p->>'user_id')::uuid,p->>'badge_id') on conflict do nothing;
  end if;
  insert into audit_log(actor_id,action,target_id,detail) values(actor,action,p->>'id',p);return '{}';
 end if;
 if action='media_reserve' then
  perform require_member(actor,true);
  select * into cfg from app_config for update;
  if not cfg.image_upload_enabled then raise exception '图片上传暂时关闭';end if;
  select to_jsonb(m) into result from media_assets m where user_id=actor and request_id=(p->>'request_id')::uuid;
  if result is not null then
   if result->>'state'='deleting' then raise exception '上一次图片正在清理，请稍后手动重试';end if;
   if result->>'sha256'<>p->>'sha256' or (result->>'size_bytes')::integer<>(p->>'size_bytes')::integer then raise exception '上传编号已经用于另一张图片，请重新选择';end if;
   return result;
  end if;
  if (select count(*) from media_assets where user_id=actor)>=cfg.max_images_per_user then raise exception '云端图片已达100张，请先删除不需要的图片';end if;
  if (select coalesce(sum(size_bytes),0) from media_assets)+(p->>'size_bytes')::integer>cfg.max_storage_bytes then raise exception '免费存储空间不足，已暂停新图片';end if;
  new_id=gen_random_uuid();
  insert into media_assets(id,user_id,path,size_bytes,sha256,request_id) values(new_id,actor,actor::text||'/'||new_id::text||'.jpg',(p->>'size_bytes')::integer,p->>'sha256',(p->>'request_id')::uuid) returning to_jsonb(media_assets.*) into result;return result;
 end if;
 if action='media_ready' then
  perform require_member(actor,true);
  if not cfg.image_upload_enabled then raise exception '图片上传暂时关闭';end if;
  update media_assets set state='ready' where media_assets.id=(p->>'id')::uuid and user_id=actor and state in ('reserved','ready');
  if not found then raise exception '图片预留已失效，请手动重试上传';end if;
  return '{}';
 end if;
 if action='media_cancel' then
  update media_assets set state='deleting' where media_assets.id=(p->>'id')::uuid and user_id=actor
   and not exists(select 1 from content where media_id=media_assets.id) and not exists(select 1 from submissions where media_id=media_assets.id);return '{}';
 end if;
 if action='account_begin' then
  update profiles set status='deleting' where profiles.id=actor;
  update media_assets set state='deleting' where user_id=actor;
  return jsonb_build_object('paths',coalesce((select jsonb_agg(path) from media_assets where user_id=actor),'[]'));
 end if;
 raise exception '未知写入操作';
end $$;

commit;
