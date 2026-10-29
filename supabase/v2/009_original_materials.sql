-- Additive V2 original materials model. No seed or question answers in migrations.
begin;
set local search_path=v2,extensions,public;
alter table scenes
 add column reference_kind text not null default 'mv_frame' check(reference_kind in ('mv_frame','release_cover','album_photo')),
 add column release_year integer check(release_year between 1900 and 2200),
 add column release_type text check(release_type in ('single','album')),
 add column association_precision text not null default 'unverified' check(association_precision in ('camera','building','landmark','city','region','unverified')),
 add column arrival_anchor_note text not null default '',
 add column association_excerpt text not null default '' check(length(association_excerpt)<=200),
 add column canonical_work_url text not null default '',
 add column verification_status text not null default 'pending' check(verification_status in ('pending','verified','rejected')),
 add column overlay_eligible boolean not null default false,
 add column image_width integer check(image_width>0),add column image_height integer check(image_height>0),
 add column thumbnail_url text,add column aliases jsonb not null default '[]' check(jsonb_typeof(aliases)='array'),
 add column image_provenance jsonb not null default '{}';
alter table scenes add constraint scene_association_kind check(kind in ('mv','album','song_title','lyric'));
alter table scenes add constraint scene_no_fictional_time check(kind='mv' or timestamp_seconds is null);
alter table scenes add constraint scene_safe_overlay check(not overlay_eligible or
 (kind in ('mv','album') and association_precision in ('camera','building','landmark') and verification_status='verified'));
alter table scenes add constraint scene_approved_image check(image_status<>'approved' or coalesce(
 (length(trim(image_url))>0 and image_width between 1 and 1280 and image_height between 1 and 1280
 and length(trim(image_credit))>0 and image_provenance->>'sha256' ~ '^[0-9a-f]{64}$'
 and (image_provenance->>'byteSize')::integer between 1 and 409600
 and image_provenance->'userReview'->>'decision'='approved'
 and length(image_provenance->>'originalSourceUrl')>0 and length(image_provenance->>'acquisitionUrl')>0
 and length(image_provenance->>'rightsHolder')>0 and length(image_provenance->>'useBasis')>0),false));
alter table submissions add column association_type text check(association_type in ('mv','album','song_title','lyric')),
 add column association_excerpt text not null default '' check(length(association_excerpt)<=200),
 add column canonical_work_url text not null default '';
alter table questions add column reference_scene_id text references scenes,
 add column image_width integer check(image_width>0),add column image_height integer check(image_height>0);
alter table questions add constraint published_real_reference check(not published or coalesce(
 (length(trim(image_url))>0 and reference_scene_id is not null and image_width between 1 and 1280 and image_height between 1 and 1280),false));
create table material_imports(id text primary key,manifest_sha256 text not null,imported_at timestamptz not null default now(),
 start_date date not null,summary jsonb not null,rollback_snapshot jsonb not null default '{}',rolled_back_at timestamptz);
alter table material_imports enable row level security;
revoke all on material_imports from public,anon,authenticated;
grant all on material_imports to service_role;
create or replace function catalog() returns jsonb language sql stable security definer set search_path=v2,extensions,public as $$
 select jsonb_build_object('schemaVersion',2,'artists',coalesce((select jsonb_agg(to_jsonb(a) order by a.id) from artists a),'[]'::jsonb),
 'places',coalesce((select jsonb_agg(jsonb_build_object(
 'id',l.id,'name',l.name,'city',l.city,'country',l.country,'address',l.address,'latitude',l.latitude,'longitude',l.longitude,
 'radiusMeters',l.checkin_radius_m,'precision',l.precision,'description',l.description,'coordinateSource',l.coordinate_source,'verified',l.verified,
 'scenes',coalesce((select jsonb_agg(jsonb_build_object('id',s.id,'artistId',s.artist_id,'artistName',a.name,'work',w.title,'kind',s.kind,
 'timestampSeconds',s.timestamp_seconds,'sourceUrl',s.source_url,'imageUrl',s.image_url,'imageCredit',s.image_credit,'imageStatus',s.image_status,'note',s.note,'referenceKind',s.reference_kind,'releaseYear',s.release_year,'releaseType',s.release_type,'associationPrecision',s.association_precision,'arrivalAnchorNote',s.arrival_anchor_note,'associationExcerpt',s.association_excerpt,'canonicalWorkUrl',s.canonical_work_url,'verificationStatus',s.verification_status,'overlayEligible',s.overlay_eligible,'imageWidth',s.image_width,'imageHeight',s.image_height,'thumbnailUrl',s.thumbnail_url,'aliases',s.aliases,'imageProvenance',s.image_provenance) order by s.id) from scenes s join artists a on a.id=s.artist_id join works w on w.id=s.work_id where s.location_id=l.id),'[]'::jsonb)) order by l.id)
 from locations l where verified),'[]'::jsonb))
$$;
create or replace function read_api(actor uuid,action text,p jsonb) returns jsonb language plpgsql security definer set search_path=v2,extensions,public as $$
declare r jsonb;me profiles;target uuid;today date=(now() at time zone 'Asia/Shanghai')::date;q text=left(trim(coalesce(p->>'query','')),80);
begin
 if action='config' then return (select to_jsonb(c)-'max_storage_bytes' from app_config c);end if;
 if action='catalog' then return catalog();end if;
 if action='search' then
  with terms as (
   select l.id,l.name term from locations l where verified union all select id,city from locations where verified
   union all select id,country from locations where verified
   union all select s.location_id,w.title from scenes s join works w on w.id=s.work_id
   union all select s.location_id,jsonb_array_elements_text(w.aliases) from scenes s join works w on w.id=s.work_id
   union all select s.location_id,jsonb_array_elements_text(s.aliases) from scenes s
   union all select s.location_id,s.association_excerpt from scenes s where s.association_excerpt<>''
   union all select s.location_id,a.name from scenes s join artists a on a.id=s.artist_id
   union all select s.location_id,jsonb_array_elements_text(a.aliases) from scenes s join artists a on a.id=s.artist_id
  ), ranks as (
   select id,term,case when lower(term)=lower(q) then 100.0 when lower(term) like lower(q)||'%' then 90.0
    when position(lower(q) in lower(term))>0 then 80.0
    when length(q) between 2 and 8 and abs(length(term)-length(q))<=1 and levenshtein_less_equal(lower(left(term,100)),lower(q),1)<=1 then 65.0
    else similarity(lower(term),lower(q))*60.0 end score from terms
  ), best as (select distinct on(id) id,term,score from ranks where q='' or score>=18 order by id,score desc)
  select coalesce(jsonb_agg(to_jsonb(t)),'[]') into r from (select b.id,b.term matched,b.score from best b join locations l on l.id=b.id where l.verified order by b.score desc,b.id limit 100) t;
  return r;
 end if;
 me=require_member(actor);
 if action='self' then return to_jsonb(me)||jsonb_build_object(
  'favorites',coalesce((select jsonb_agg(location_id) from favorites where user_id=actor),'[]'),
  'visit_count',(select count(*) from content where user_id=actor and kind='visit'),
  'recreation_count',(select count(*) from content where user_id=actor and kind='recreation'),
  'challenge_count',(select count(*) from challenge_attempts where user_id=actor),
  'correct_count',(select count(*) from challenge_attempts where user_id=actor and correct),
  'image_count',(select count(*) from media_assets where user_id=actor),
  'badges',coalesce((select jsonb_agg(to_jsonb(b)||jsonb_build_object('earned_at',u.earned_at)) from badges b left join user_badges u on u.badge_id=b.id and u.user_id=actor),'[]'));
 end if;
 if action='profile' then
  target=(p->>'id')::uuid;
  select jsonb_build_object('id',id,'nickname',case when status='active' then nickname else '账号已停用' end,'bio',bio,
   'visit_count',(select count(*) from content where user_id=target and kind='visit'),
   'recreation_count',(select count(*) from content where user_id=target and kind='recreation' and visibility='public' and not is_hidden),
   'recreations',coalesce((select jsonb_agg(content_json(c,actor) order by c.created_at desc) from content c where c.user_id=target and c.kind='recreation' and c.visibility='public' and not c.is_hidden),'[]'))
  into r from profiles where id=target and status<>'deleting';
  if r is null then raise exception '该用户已注销';end if;return r;
 end if;
 if action='content' then
  select coalesce(jsonb_agg(content_json(c,actor) order by c.created_at desc),'[]') into r from (
   select * from content c where visible(c,actor) and
    (not coalesce((p->>'mine')::boolean,false) or c.user_id=actor) and
    (coalesce(p->>'kind','')='' or c.kind=p->>'kind') and
    (coalesce(p->>'location_id','')='' or c.location_id=p->>'location_id')
   order by created_at desc,id limit 30 offset least(greatest(coalesce((p->>'offset')::integer,0),0),10000)
  ) c;return r;
 end if;
 if action='nearby' then
  if not ((p->>'latitude')::double precision between -90 and 90 and (p->>'longitude')::double precision between -180 and 180) then raise exception '坐标无效';end if;
  return jsonb_build_object(
  'locations',coalesce((select jsonb_agg(l.id) from locations l where verified and st_dwithin(l.geo,st_setsrid(st_makepoint((p->>'longitude')::double precision,(p->>'latitude')::double precision),4326)::geography,10000)),'[]'),
  'capsules',coalesce((select jsonb_agg(content_json(c,actor)) from (select * from content c where kind='capsule' and visible(c,actor) and st_dwithin(c.geo,st_setsrid(st_makepoint((p->>'longitude')::double precision,(p->>'latitude')::double precision),4326)::geography,10000) order by created_at desc limit 100) c),'[]'));
 end if;
 if action='challenges' then
  if not (select challenge_enabled from app_config) then raise exception '挑战暂时关闭';end if;
  select coalesce(jsonb_agg(t.item order by t.d desc),'[]') into r from (
   select s.challenge_date d,jsonb_build_object('id',q.id,'date',s.challenge_date,'type',q.challenge_type,'question',q.question,'image_url',q.image_url,'options',q.options,'image_aspect_ratio',q.image_width::numeric/nullif(q.image_height,0),
    'attempt',a.result,'answered',a.user_id is not null) item
   from challenge_schedule s join questions q on q.id=s.question_id left join challenge_attempts a on a.question_id=q.id and a.user_id=actor
   where s.challenge_date<=today and q.published and q.image_url is not null and exists(select 1 from scenes sc where sc.id=q.reference_scene_id and sc.image_status='approved' and sc.verification_status='verified') order by s.challenge_date desc limit 365
  ) t;return r;
 end if;
 if action='submissions' then return coalesce((select jsonb_agg(to_jsonb(s) order by created_at desc) from submissions s where user_id=actor),'[]');end if;
 if action='media' then
  select jsonb_build_object('path',m.path) into r from media_assets m where id=(p->>'id')::uuid and state='ready' and
   (exists(select 1 from content c where c.media_id=m.id and (visible(c,actor) or (me.role='admin' and me.status='active' and c.visibility='public' and c.is_hidden))) or
   exists(select 1 from submissions s where s.media_id=m.id and (s.user_id=actor or (me.role='admin' and me.status='active'))));
  if r is null then raise exception '图片不存在或无权访问';end if;return r;
 end if;
 if action='admin' then
  if me.role<>'admin' or me.status<>'active' then raise exception '需要管理员权限';end if;
  return jsonb_build_object('users',coalesce((select jsonb_agg(to_jsonb(x)) from (select id,nickname,role,status from profiles order by created_at desc limit 100) x),'[]'),
   'reports',coalesce((select jsonb_agg(to_jsonb(x)) from (select * from reports order by created_at desc limit 100) x),'[]'),
   'submissions',coalesce((select jsonb_agg(to_jsonb(x)) from (select * from submissions order by created_at desc limit 100) x),'[]'),
   'hidden',coalesce((select jsonb_agg(content_json(c,actor)) from content c where is_hidden and visibility='public'),'[]'),
   'badges',coalesce((select jsonb_agg(to_jsonb(b)) from badges b),'[]'),
   'awards',coalesce((select jsonb_agg(to_jsonb(b)) from user_badges b),'[]'),
   'config',(select to_jsonb(c) from app_config c),'storage_bytes',(select coalesce(sum(size_bytes),0) from media_assets));
 end if;
 raise exception '未知读取操作';
end $$;
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
  if coalesce((p->>'enabled')::boolean,false) then
  if not exists(select 1 from locations where id=p->>'location_id' and verified) then raise exception '地点尚待核实或不存在';end if;
  insert into favorites values(actor,p->>'location_id') on conflict do nothing;
  else delete from favorites where user_id=actor and location_id=p->>'location_id';end if;return '{}'::jsonb;
 end if;
 if action='create_message' or action='create_capsule' then
  if action='create_message' and not exists(select 1 from locations where id=p->>'location_id' and verified) then raise exception '地点尚待核实或不存在';end if;
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
   if s.id is null or s.image_url is null or s.image_status<>'approved' or s.verification_status<>'verified' or not s.overlay_eligible then raise exception '此场景暂不支持原机位复刻';end if;
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
  if q.id is null or q.image_url is null or not exists(select 1 from scenes sc where sc.id=q.reference_scene_id and sc.image_status='approved' and sc.verification_status='verified') then raise exception '题目尚未发布或原图不可用';end if;
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
  if nullif(p->>'association_type','') is not null and p->>'association_type' not in ('mv','album','song_title','lyric') then raise exception '关联类型无效';end if;
  if length(coalesce(p->>'association_excerpt',''))>200 or (p->>'association_type' in ('song_title','lyric') and length(trim(coalesce(p->>'association_excerpt','')))=0) then raise exception '请填写200字以内的歌名或歌词依据';end if;
  asset=nullif(p->>'media_id','')::uuid;perform check_media(actor,asset);
  insert into submissions(id,user_id,submission_type,location_id,artist,title,place_name,description,source_url,latitude,longitude,media_id,association_type,association_excerpt,canonical_work_url)
  values((p->>'id')::uuid,actor,p->>'submission_type',nullif(p->>'location_id',''),left(p->>'artist',100),left(p->>'title',100),left(p->>'place_name',100),left(coalesce(p->>'description',''),2000),left(coalesce(p->>'source_url',''),1000),nullif(p->>'latitude','')::double precision,nullif(p->>'longitude','')::double precision,asset,nullif(p->>'association_type',''),coalesce(p->>'association_excerpt',''),left(coalesce(p->>'canonical_work_url',''),1000))
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
create or replace function guard_material_question() returns trigger language plpgsql set search_path=v2,extensions,public as $$
declare s scenes;
begin
 if new.published then
  select * into s from scenes where id=new.reference_scene_id;
  if s.id is null or s.image_status<>'approved' or s.verification_status<>'verified' then raise exception '正式题目必须引用已审核的真实原图';end if;
  if s.image_url=new.image_url then raise exception '题图须使用独立随机路径，避免连接公开地点图片泄露答案';end if;
  if new.challenge_type='map_guess' and (s.association_precision in ('city','region','unverified') or new.correct_radius_m<>1000) then raise exception '地图题需要具体目标及1km容差';end if;
 end if;
 return new;
end $$;
create trigger material_question_guard before insert or update on questions for each row execute function guard_material_question();
revoke all on function guard_material_question() from public,anon,authenticated;

commit;
