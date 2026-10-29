-- Association-level publication gates preserve historical rows and user records.
-- Only a verified association with an approved original image contributes to
-- catalog/search/nearby. Shared locations survive while another scene qualifies.
begin;
set local search_path=v2,extensions,public;
alter table scenes drop constraint scenes_release_type_check;
alter table scenes add constraint scenes_release_type_check check(release_type in ('single','album','ep'));
create view public_scenes as select * from scenes where verification_status='verified' and image_status='approved';
create view public_locations as select l.* from locations l where l.verified
 and exists(select 1 from public_scenes s where s.location_id=l.id);
revoke all on public_scenes,public_locations from public,anon,authenticated;
grant select on public_scenes,public_locations to service_role;
create table scene_material_reviews (
 id text primary key, import_id text not null references material_imports(id),
 scene_id text not null references scenes(id), reviewed_at timestamptz not null default now(),
 previous_scene jsonb not null, result_scene jsonb not null,
 decision text not null check(decision in ('verified','pending','rejected')),
 reason text not null check(length(trim(reason))>0),
 evidence_sha256 text not null check(evidence_sha256 ~ '^[0-9a-f]{64}$'),
 authorization_sha256 text not null check(authorization_sha256 ~ '^[0-9a-f]{64}$')
);
alter table scene_material_reviews enable row level security;
revoke all on scene_material_reviews from public,anon,authenticated;
grant all on scene_material_reviews to service_role;
create or replace function catalog() returns jsonb language sql stable security definer set search_path=v2,extensions,public as $$
 select jsonb_build_object('schemaVersion',2,'artists',coalesce((select jsonb_agg(to_jsonb(a) order by a.id) from artists a),'[]'::jsonb),
 'places',coalesce((select jsonb_agg(jsonb_build_object(
 'id',l.id,'name',l.name,'city',l.city,'country',l.country,'address',l.address,'latitude',l.latitude,'longitude',l.longitude,
 'radiusMeters',l.checkin_radius_m,'precision',l.precision,'description',l.description,'coordinateSource',l.coordinate_source,'verified',l.verified,
 'scenes',coalesce((select jsonb_agg(jsonb_build_object('id',s.id,'artistId',s.artist_id,'artistName',a.name,'work',w.title,'kind',s.kind,
 'timestampSeconds',s.timestamp_seconds,'sourceUrl',s.source_url,'imageUrl',s.image_url,'imageCredit',s.image_credit,'imageStatus',s.image_status,'note',s.note,'referenceKind',s.reference_kind,'releaseYear',s.release_year,'releaseType',s.release_type,'associationPrecision',s.association_precision,'arrivalAnchorNote',s.arrival_anchor_note,'associationExcerpt',s.association_excerpt,'canonicalWorkUrl',s.canonical_work_url,'verificationStatus',s.verification_status,'overlayEligible',s.overlay_eligible,'imageWidth',s.image_width,'imageHeight',s.image_height,'thumbnailUrl',s.thumbnail_url,'aliases',s.aliases,'imageProvenance',s.image_provenance,
 'displayArtwork',case when ra.id is null then null else jsonb_build_object(
  'id',ra.id,'releaseTitle',ra.release_title,'releaseYear',ra.release_year,'releaseType',ra.release_type,
  'sourceUrl',ra.source_url,'imageUrl',ra.image_url,'thumbnailUrl',ra.thumbnail_url,'imageCredit',ra.image_credit,
  'imageStatus',ra.image_status,'imageWidth',ra.image_width,'imageHeight',ra.image_height,'imageProvenance',ra.image_provenance) end
 ) order by s.id) from public_scenes s join artists a on a.id=s.artist_id join works w on w.id=s.work_id
 left join work_display_artworks da on da.work_id=s.work_id and da.artist_id=s.artist_id
 left join release_artworks ra on ra.id=da.artwork_id where s.location_id=l.id),'[]'::jsonb)) order by l.id)
 from public_locations l),'[]'::jsonb))
$$;

create or replace function read_api(actor uuid,action text,p jsonb) returns jsonb language plpgsql security definer set search_path=v2,extensions,public as $$
declare r jsonb;me profiles;target uuid;today date=(now() at time zone 'Asia/Shanghai')::date;q text=left(trim(coalesce(p->>'query','')),80);
begin
 if action='config' then return (select to_jsonb(c)-'max_storage_bytes' from app_config c);end if;
 if action='catalog' then return catalog();end if;
 if action='search' then
  with terms as (
   select l.id,l.name term from public_locations l where verified union all select id,city from public_locations where verified
   union all select id,country from public_locations where verified
   union all select s.location_id,w.title from public_scenes s join works w on w.id=s.work_id
   union all select s.location_id,jsonb_array_elements_text(w.aliases) from public_scenes s join works w on w.id=s.work_id
   union all select s.location_id,jsonb_array_elements_text(s.aliases) from public_scenes s
   union all select s.location_id,s.association_excerpt from public_scenes s where s.association_excerpt<>''
   union all select s.location_id,a.name from public_scenes s join artists a on a.id=s.artist_id
   union all select s.location_id,jsonb_array_elements_text(a.aliases) from public_scenes s join artists a on a.id=s.artist_id
  ), ranks as (
   select id,term,case when lower(term)=lower(q) then 100.0 when lower(term) like lower(q)||'%' then 90.0
    when position(lower(q) in lower(term))>0 then 80.0
    when length(q) between 2 and 8 and abs(length(term)-length(q))<=1 and levenshtein_less_equal(lower(left(term,100)),lower(q),1)<=1 then 65.0
    else similarity(lower(term),lower(q))*60.0 end score from terms
  ), best as (select distinct on(id) id,term,score from ranks where q='' or score>=18 order by id,score desc)
  select coalesce(jsonb_agg(to_jsonb(t)),'[]') into r from (select b.id,b.term matched,b.score from best b join public_locations l on l.id=b.id where l.verified order by b.score desc,b.id limit 100) t;
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
  'locations',coalesce((select jsonb_agg(l.id) from public_locations l where st_dwithin(l.geo,st_setsrid(st_makepoint((p->>'longitude')::double precision,(p->>'latitude')::double precision),4326)::geography,10000)),'[]'),
  'capsules',coalesce((select jsonb_agg(content_json(c,actor)) from (select * from content c where kind='capsule' and visible(c,actor) and st_dwithin(c.geo,st_setsrid(st_makepoint((p->>'longitude')::double precision,(p->>'latitude')::double precision),4326)::geography,10000) order by created_at desc limit 100) c),'[]'));
 end if;
 if action='challenges' then
  if not (select challenge_enabled from app_config) then raise exception '挑战暂时关闭';end if;
  select coalesce(jsonb_agg(t.item order by t.d desc),'[]') into r from (
   select s.challenge_date d,jsonb_build_object('id',q.id,'date',s.challenge_date,'type',q.challenge_type,'question',q.question,'image_url',q.image_url,'options',q.options,'image_aspect_ratio',q.image_width::numeric/nullif(q.image_height,0),
    'attempt',a.result,'answered',a.user_id is not null) item
   from challenge_schedule s join questions q on q.id=s.question_id left join challenge_attempts a on a.question_id=q.id and a.user_id=actor
   where s.challenge_date<=today and q.published and q.image_url is not null and exists(select 1 from public_scenes sc join public_locations pl on pl.id=sc.location_id where sc.id=q.reference_scene_id) order by s.challenge_date desc limit 365
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

commit;
