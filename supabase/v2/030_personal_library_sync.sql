-- Private account library only. No visit, publication, auth or existing collection rows change.
begin;
set local lock_timeout='10s';
set local statement_timeout='60s';

create table v2.personal_library (
 user_id uuid not null references v2.profiles(id) on delete cascade,
 kind text not null check(kind in ('music_favorites','trips')),
 data jsonb not null check(jsonb_typeof(data)='object' and octet_length(data::text)<=400000),
 revision bigint not null default 0 check(revision>=0),client_id uuid,
 updated_at timestamptz not null default now(),primary key(user_id,kind)
);
alter table v2.personal_library enable row level security;
revoke all on v2.personal_library from public,anon,authenticated;

create function v2.validate_library_data(kind text,doc jsonb) returns void
language plpgsql immutable set search_path=pg_catalog as $$
declare item jsonb; stop jsonb; city jsonb; category text; first_day date; last_day date; day_count integer;
begin
 if jsonb_typeof(doc) is distinct from 'object' or octet_length(doc::text)>400000 then raise exception '同步数据无效或超过上限，本机数据已保留';end if;
 if kind='music_favorites' then
  if exists(select 1 from jsonb_object_keys(doc) k where k not in ('songs','releases')) then raise exception '音乐收藏字段无效';end if;
  foreach category in array array['songs','releases'] loop
   if jsonb_typeof(doc->category) is distinct from 'array' or jsonb_array_length(doc->category)>2000 then raise exception '音乐收藏超过同步上限';end if;
   if (select count(distinct value->>'id') from jsonb_array_elements(doc->category))<>jsonb_array_length(doc->category) then raise exception '音乐收藏重复';end if;
   for item in select value from jsonb_array_elements(doc->category) loop
    if jsonb_typeof(item) is distinct from 'object' or jsonb_typeof(item->'id') is distinct from 'string' or length(trim(item->>'id')) not between 1 and 512
     or jsonb_typeof(item->'added_at') is distinct from 'number' or (item->>'added_at') !~ '^[0-9]{1,15}$'
     or exists(select 1 from jsonb_object_keys(item) k where k not in ('id','added_at')) then raise exception '音乐收藏数据无效';end if;
   end loop;
  end loop;
 elsif kind='trips' then
  if exists(select 1 from jsonb_object_keys(doc) k where k<>'trips') or jsonb_typeof(doc->'trips') is distinct from 'array' or jsonb_array_length(doc->'trips')>100 then raise exception '行程超过同步上限';end if;
  if (select count(distinct value->>'id') from jsonb_array_elements(doc->'trips'))<>jsonb_array_length(doc->'trips') then raise exception '行程编号重复';end if;
  for item in select value from jsonb_array_elements(doc->'trips') loop
   if jsonb_typeof(item) is distinct from 'object' or exists(select 1 from jsonb_object_keys(item) k where k not in ('id','name','start','end','cities','stops'))
    or coalesce(item->>'id','') !~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
    or jsonb_typeof(item->'name') is distinct from 'string' or length(trim(item->>'name')) not between 1 and 60
    or coalesce(item->>'start','') !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' or coalesce(item->>'end','') !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' then raise exception '行程名称或日期无效';end if;
   first_day=(item->>'start')::date;last_day=(item->>'end')::date;day_count=last_day-first_day+1;
   if day_count not between 1 and 365 then raise exception '行程日期最长365天';end if;
   if jsonb_typeof(item->'cities') is distinct from 'array' or jsonb_array_length(item->'cities') not between 1 and 64 then raise exception '请选择有效的行程城市';end if;
   for city in select value from jsonb_array_elements(item->'cities') loop
    if jsonb_typeof(city) is distinct from 'object' or exists(select 1 from jsonb_object_keys(city) k where k not in ('name','country'))
     or jsonb_typeof(city->'name') is distinct from 'string' or length(trim(city->>'name')) not between 1 and 100
     or jsonb_typeof(city->'country') is distinct from 'string' or length(trim(city->>'country')) not between 1 and 100 then raise exception '行程城市无效';end if;
   end loop;
   if jsonb_typeof(item->'stops') is distinct from 'array' or jsonb_array_length(item->'stops')>2000 then raise exception '行程地点超过同步上限';end if;
   if (select count(distinct value->>'id') from jsonb_array_elements(item->'stops'))<>jsonb_array_length(item->'stops') then raise exception '行程地点编号重复';end if;
   for stop in select value from jsonb_array_elements(item->'stops') loop
    if jsonb_typeof(stop) is distinct from 'object' or exists(select 1 from jsonb_object_keys(stop) k where k not in ('id','place','name','city','country','day','note'))
     or coalesce(stop->>'id','') !~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
     or jsonb_typeof(stop->'name') is distinct from 'string' or length(trim(stop->>'name')) not between 1 and 100
     or jsonb_typeof(stop->'note') is distinct from 'string' or length(stop->>'note')>300
     or not exists(select 1 from jsonb_array_elements(item->'cities') c where c->>'name'=stop->>'city' and c->>'country'=stop->>'country')
     or not(stop ? 'place') or not(stop ? 'day') then raise exception '行程地点无效';end if;
    if jsonb_typeof(stop->'place')<>'null' and (jsonb_typeof(stop->'place')<>'string' or length(trim(stop->>'place')) not between 1 and 256) then raise exception '行程地点编号无效';end if;
    if jsonb_typeof(stop->'day')<>'null' and (jsonb_typeof(stop->'day')<>'number' or (stop->>'day') !~ '^[1-9][0-9]{0,2}$') then raise exception '行程天数无效';end if;
    if jsonb_typeof(stop->'day')<>'null' and (stop->>'day')::integer not between 1 and day_count then raise exception '行程天数无效';end if;
   end loop;
   if exists(select 1 from jsonb_array_elements(item->'stops') s where jsonb_typeof(s->'place')='string' and jsonb_typeof(s->'day')='number' group by s->>'place',s->>'day' having count(*)>1) then raise exception '同一天不能重复安排同一地点';end if;
  end loop;
 else raise exception '未知同步分类';end if;
end $$;

create function v2.library_api(actor uuid,action text,p jsonb) returns jsonb
language plpgsql security definer set search_path=pg_catalog,v2,extensions,public as $$
declare me v2.profiles; stored v2.personal_library; category text; expected bigint; empty_doc jsonb; client uuid;
begin
 me=v2.require_member(actor,false);
 if action='library_state' then
  return jsonb_build_object(
   'music_favorites',coalesce((select jsonb_build_object('revision',d.revision,'data',d.data,'client_id',d.client_id) from v2.personal_library d where d.user_id=actor and d.kind='music_favorites'),jsonb_build_object('revision',0,'data','{"songs":[],"releases":[]}'::jsonb,'client_id',null)),
   'trips',coalesce((select jsonb_build_object('revision',d.revision,'data',d.data,'client_id',d.client_id) from v2.personal_library d where d.user_id=actor and d.kind='trips'),jsonb_build_object('revision',0,'data','{"trips":[]}'::jsonb,'client_id',null)));
 end if;
 if action<>'library_sync' then raise exception '未知同步操作';end if;
 if me.status<>'active' then raise exception '账号已停用，暂不能同步';end if;
 if exists(select 1 from v2.app_config where maintenance_mode and me.role<>'admin') then raise exception '服务维护中，请稍后同步';end if;
 perform v2.throttle(actor::text||':library_sync',120,60);
 category=p->>'kind';
 if category not in ('music_favorites','trips') or category is null or coalesce(p->>'expected_revision','') !~ '^[0-9]{1,18}$'
  or coalesce(p->>'client_id','') !~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
  or exists(select 1 from jsonb_object_keys(p) k where k not in ('kind','expected_revision','data','client_id')) then raise exception '同步请求无效';end if;
 perform v2.validate_library_data(category,p->'data');expected=(p->>'expected_revision')::bigint;client=(p->>'client_id')::uuid;
 empty_doc=case when category='trips' then '{"trips":[]}'::jsonb else '{"songs":[],"releases":[]}'::jsonb end;
 insert into v2.personal_library(user_id,kind,data) values(actor,category,empty_doc) on conflict do nothing;
 select * into stored from v2.personal_library d where d.user_id=actor and d.kind=category for update;
 if stored.revision<>expected then return jsonb_build_object('revision',stored.revision,'data',stored.data,'client_id',stored.client_id,'applied',false);end if;
 -- Every accepted request advances the revision, even if its data is unchanged. This
 -- fences an older request whose HTTP response was lost and which may still be running.
 update v2.personal_library d set data=p->'data',revision=d.revision+1,client_id=client,updated_at=now() where d.user_id=actor and d.kind=category returning * into stored;
 return jsonb_build_object('revision',stored.revision,'data',stored.data,'client_id',stored.client_id,'applied',true);
end $$;
revoke all on function v2.validate_library_data(text,jsonb),v2.library_api(uuid,text,jsonb) from public,anon,authenticated;
grant execute on function v2.library_api(uuid,text,jsonb) to service_role;

-- The public entry points derive the owner from the verified JWT; callers cannot
-- supply another account id. Existing Edge routes and privileged RPC grants stay intact.
create function v2.library_actor() returns uuid
language plpgsql security definer set search_path='' as $$
declare actor uuid=auth.uid();
begin
 if actor is null or not exists(select 1 from auth.users where id=actor and email_confirmed_at is not null) then raise exception '请先登录并验证邮箱';end if;
 insert into v2.profiles(id) values(actor) on conflict do nothing;
 return actor;
end $$;
create function public.personal_library_state() returns jsonb
language plpgsql security definer set search_path='' as $$
begin return v2.library_api(v2.library_actor(),'library_state','{}'::jsonb);end $$;
create function public.personal_library_sync(p_kind text,p_expected_revision bigint,p_data jsonb,p_client_id uuid) returns jsonb
language plpgsql security definer set search_path='' as $$
begin return v2.library_api(v2.library_actor(),'library_sync',jsonb_build_object('kind',p_kind,'expected_revision',p_expected_revision,'data',p_data,'client_id',p_client_id));end $$;
revoke all on function v2.library_actor() from public,anon,authenticated;
revoke all on function public.personal_library_state(),public.personal_library_sync(text,bigint,jsonb,uuid) from public,anon;
grant execute on function public.personal_library_state(),public.personal_library_sync(text,bigint,jsonb,uuid) to authenticated;
notify pgrst,'reload schema';
commit;
