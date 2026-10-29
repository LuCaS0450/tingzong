-- All application writes pass through app-api. Direct REST clients receive SELECT only.
create table public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  username text not null unique check (username ~ '^[a-z][a-z0-9_]{2,31}$'),
  display_name text not null check (char_length(display_name) between 1 and 60),
  role text not null default 'member' check (role in ('member','admin')),
  active boolean not null default true,
  created_at timestamptz not null default now()
);
create table public.artists (id text primary key, name text not null, aliases jsonb not null default '[]', color text not null default '#B8CB86');
create table public.places (
  id text primary key, name text not null, city text not null, country text not null, address text not null default '',
  latitude double precision not null check (latitude between -90 and 90),
  longitude double precision not null check (longitude between -180 and 180),
  radius_meters integer not null default 200 check (radius_meters between 25 and 1000),
  precision text not null default 'landmark', description text not null default '',
  coordinate_source text not null default '', verified boolean not null default false,
  updated_at timestamptz not null default now()
);
create table public.scenes (
  id text primary key, place_id text not null references public.places(id) on delete cascade,
  artist_id text not null references public.artists(id), work text not null,
  kind text not null check (kind in ('mv','album')), timestamp_seconds integer check (timestamp_seconds >= 0),
  source_url text not null check (source_url ~ '^https://'), image_url text,
  image_credit text, image_status text not null default 'missing', note text not null default ''
);
create table public.checkins (
  id uuid primary key, user_id uuid not null references public.profiles(id),
  place_id text not null references public.places(id), note text not null default '' check (char_length(note)<=2000),
  visibility text not null default 'private' check (visibility in ('private','circle')),
  photo_path text, client_created_at timestamptz not null, created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
-- Precise user GPS NEVER appears in the circle-readable checkins table.
create table public.checkin_evidence (
  checkin_id uuid primary key references public.checkins(id) on delete cascade,
  user_id uuid not null references public.profiles(id), latitude double precision not null,
  longitude double precision not null, accuracy double precision not null,
  captured_at timestamptz not null, age_millis bigint not null,
  distance_meters double precision not null, validation text not null default 'client_location_checked'
);
create table public.favorites (user_id uuid references public.profiles(id), place_id text references public.places(id), primary key(user_id,place_id));
create table public.likes (user_id uuid references public.profiles(id), checkin_id uuid references public.checkins(id) on delete cascade, primary key(user_id,checkin_id));
create table public.comments (
  id uuid primary key default gen_random_uuid(), user_id uuid not null references public.profiles(id),
  checkin_id uuid not null references public.checkins(id) on delete cascade,
  body text not null check (char_length(body) between 1 and 500), created_at timestamptz not null default now()
);
create table public.submissions (
  id uuid primary key default gen_random_uuid(), user_id uuid not null references public.profiles(id),
  title text not null check (char_length(title) between 1 and 120), details text not null check (char_length(details) between 1 and 6000),
  status text not null default 'pending' check (status in ('pending','approved','rejected')),
  review_note text not null default '', publish_place_id text references public.places(id),
  created_at timestamptz not null default now(), reviewed_at timestamptz
);
create table public.media_assets (
  path text primary key, user_id uuid not null references public.profiles(id), checkin_id uuid unique not null,
  size_bytes integer not null check(size_bytes between 4 and 409600), sha256 text not null,
  created_at timestamptz not null default now()
);
create table public.api_rate_limits (key text primary key, window_start timestamptz not null, attempts integer not null);
create table public.reference_assets (path text primary key,size_bytes integer not null check(size_bytes between 4 and 409600),created_at timestamptz not null default now());
create table public.deleted_checkins (id uuid primary key,user_id uuid not null references public.profiles(id),deleted_by uuid not null references public.profiles(id),created_at timestamptz not null default now());
create index checkins_place_circle on public.checkins(place_id,client_created_at desc) where visibility='circle';
create index checkins_owner on public.checkins(user_id,client_created_at desc);
create index comments_checkin on public.comments(checkin_id,created_at);
create index scenes_artist on public.scenes(artist_id);

create function public.is_active() returns boolean language sql stable security definer set search_path=public,pg_temp as $$
 select exists(select 1 from public.profiles where id=auth.uid() and active);
$$;
create function public.is_admin() returns boolean language sql stable security definer set search_path=public,pg_temp as $$
 select exists(select 1 from public.profiles where id=auth.uid() and active and role='admin');
$$;
create function public.can_read_checkin(target uuid) returns boolean language sql stable security definer set search_path=public,pg_temp as $$
 select public.is_active() and exists(select 1 from public.checkins c join public.profiles p on p.id=c.user_id where c.id=target and p.active and (c.user_id=auth.uid() or c.visibility='circle'));
$$;

alter table public.profiles enable row level security;
alter table public.artists enable row level security;
alter table public.places enable row level security;
alter table public.scenes enable row level security;
alter table public.checkins enable row level security;
alter table public.checkin_evidence enable row level security;
alter table public.favorites enable row level security;
alter table public.likes enable row level security;
alter table public.comments enable row level security;
alter table public.submissions enable row level security;
alter table public.media_assets enable row level security;
alter table public.api_rate_limits enable row level security;
alter table public.reference_assets enable row level security;
alter table public.deleted_checkins enable row level security;
create policy profile_read on public.profiles for select to authenticated using(public.is_active() and (id=auth.uid() or public.is_admin()));
create policy artist_read on public.artists for select to authenticated using(public.is_active());
create policy place_read on public.places for select to authenticated using(public.is_active() and (verified or public.is_admin()));
create policy scene_read on public.scenes for select to authenticated using(public.is_active() and exists(select 1 from public.places p where p.id=place_id and (p.verified or public.is_admin())));
create policy checkin_read on public.checkins for select to authenticated using(public.can_read_checkin(id));
create policy evidence_owner_only on public.checkin_evidence for select to authenticated using(public.is_active() and user_id=auth.uid());
create policy favorite_owner_only on public.favorites for select to authenticated using(public.is_active() and user_id=auth.uid());
create policy like_read on public.likes for select to authenticated using(public.can_read_checkin(checkin_id));
create policy comment_read on public.comments for select to authenticated using(public.can_read_checkin(checkin_id));
create policy submission_read on public.submissions for select to authenticated using(public.is_active() and (user_id=auth.uid() or public.is_admin()));
-- No authenticated policies for media_assets, rate-limits, or any table mutation.
revoke all on public.profiles,public.artists,public.places,public.scenes,public.checkins,public.checkin_evidence,public.favorites,public.likes,public.comments,public.submissions,public.media_assets,public.api_rate_limits from anon,authenticated;
revoke all on public.reference_assets,public.deleted_checkins from anon,authenticated;
grant select on public.profiles,public.artists,public.places,public.scenes,public.checkins,public.checkin_evidence,public.favorites,public.likes,public.comments,public.submissions to authenticated;
grant all on public.profiles,public.artists,public.places,public.scenes,public.checkins,public.checkin_evidence,public.favorites,public.likes,public.comments,public.submissions,public.media_assets,public.api_rate_limits to service_role;
grant all on public.reference_assets,public.deleted_checkins to service_role;

create function public.consume_rate_limit(p_key text,p_limit integer,p_seconds integer) returns boolean
language plpgsql security definer set search_path=public,pg_temp as $$
declare n integer;
begin
 if p_key='login:global' then delete from public.api_rate_limits where window_start<now()-interval '2 days'; end if;
 insert into public.api_rate_limits(key,window_start,attempts) values(p_key,now(),1)
 on conflict(key) do update set attempts=case when api_rate_limits.window_start < now()-make_interval(secs=>p_seconds) then 1 else api_rate_limits.attempts+1 end,
 window_start=case when api_rate_limits.window_start < now()-make_interval(secs=>p_seconds) then now() else api_rate_limits.window_start end
 returning attempts into n;
 return n<=p_limit;
end $$;

create function public.reserve_photo(p_user uuid,p_checkin uuid,p_size integer,p_hash text) returns text
language plpgsql security definer set search_path=public,pg_temp as $$
declare existing public.media_assets; target text;
begin
 if not exists(select 1 from profiles where id=p_user and active) then raise exception '账号不可用'; end if;
 perform pg_advisory_xact_lock(828410);
 target:=p_user::text||'/'||p_checkin::text||'.jpg';
 if exists(select 1 from deleted_checkins where id=p_checkin) then raise exception '记录已删除，不能重新上传照片'; end if;
 select * into existing from media_assets where checkin_id=p_checkin;
 if found then
   if existing.user_id<>p_user or existing.sha256<>p_hash or existing.size_bytes<>p_size then raise exception '该照片编号已存在，不能替换原图'; end if;
   return existing.path;
 end if;
 if exists(select 1 from checkins where id=p_checkin) or exists(select 1 from deleted_checkins where id=p_checkin) then raise exception '打卡提交或删除后不能追加照片'; end if;
 if p_size not between 4 and 409600 then raise exception '照片超过400KB'; end if;
 if (select coalesce(sum(size_bytes),0) from media_assets)+(select coalesce(sum(size_bytes),0) from reference_assets)+p_size>734003200 then raise exception '圈子图片额度已满，请联系管理员'; end if;
 if (select coalesce(sum(size_bytes),0) from media_assets where user_id=p_user)+p_size>20971520 then raise exception '个人图片额度已满'; end if;
 insert into media_assets(path,user_id,checkin_id,size_bytes,sha256) values(target,p_user,p_checkin,p_size,p_hash);
 return target;
end $$;

create function public.create_checkin_checked(p_user uuid,p_data jsonb) returns uuid
language plpgsql security definer set search_path=public,pg_temp as $$
declare
 target_id uuid:=(p_data->>'id')::uuid; loc public.places; existing public.checkins; prior public.checkin_evidence;
 lat double precision:=(p_data#>>'{evidence,latitude}')::double precision;
 lon double precision:=(p_data#>>'{evidence,longitude}')::double precision;
 acc double precision:=(p_data#>>'{evidence,accuracy}')::double precision;
 captured timestamptz:=(p_data#>>'{evidence,capturedAt}')::timestamptz;
 age bigint:=(p_data#>>'{evidence,ageMillis}')::bigint;
 client_time timestamptz:=(p_data->>'clientCreatedAt')::timestamptz;
 distance double precision; photo text:=nullif(p_data->>'photoPath','');
begin
 if not exists(select 1 from profiles where id=p_user and active) then raise exception '账号不可用'; end if;
 perform pg_advisory_xact_lock(hashtextextended(target_id::text,0));
 if exists(select 1 from deleted_checkins where id=target_id) then raise exception '记录已删除，不能重新上传'; end if;
 select * into existing from checkins where id=target_id;
 if found then
   select * into prior from checkin_evidence where checkin_id=target_id;
   if existing.user_id<>p_user or existing.place_id is distinct from p_data->>'placeId' or existing.client_created_at is distinct from client_time
     or prior.latitude is distinct from lat or prior.longitude is distinct from lon or prior.accuracy is distinct from acc or prior.captured_at is distinct from captured or prior.age_millis is distinct from age
     or existing.photo_path is distinct from photo then raise exception '打卡编号冲突，原始到场证据不可修改'; end if;
   -- Retried creates may carry edited text while immutable arrival evidence stays identical.
   update checkins set note=coalesce(p_data->>'note',''),visibility=coalesce(p_data->>'visibility','private'),updated_at=now() where id=target_id;
   return target_id;
 end if;
 select * into loc from places where id=p_data->>'placeId' and verified;
 if not found then raise exception '地点不存在或尚未核实'; end if;
 if lat is null or lon is null or acc is null or captured is null or age is null or client_time is null then raise exception '到场证据不完整'; end if;
 if not(lat between -90 and 90) or not(lon between -180 and 180) or not(acc>0 and acc<100) then raise exception '定位坐标或精度无效'; end if;
 if age not between 0 and 120000 or client_time-captured<interval '0 seconds' or client_time-captured>interval '120 seconds' then raise exception '打卡时定位已过期，请重新在现场定位'; end if;
 if client_time>now()+interval '5 minutes' then raise exception '打卡时间在未来，请检查手机时钟'; end if;
 distance:=6371000*2*asin(sqrt(least(1.0,power(sin(radians(lat-loc.latitude)/2),2)+cos(radians(loc.latitude))*cos(radians(lat))*power(sin(radians(lon-loc.longitude)/2),2))));
 if distance>loc.radius_meters then raise exception '尚未进入地点的打卡范围'; end if;
 if photo is not null and not exists(select 1 from media_assets where path=photo and user_id=p_user and checkin_id=target_id) then raise exception '照片不属于本次打卡'; end if;
 insert into checkins(id,user_id,place_id,note,visibility,photo_path,client_created_at) values(target_id,p_user,loc.id,coalesce(p_data->>'note',''),coalesce(p_data->>'visibility','private'),photo,client_time);
 insert into checkin_evidence(checkin_id,user_id,latitude,longitude,accuracy,captured_at,age_millis,distance_meters) values(target_id,p_user,lat,lon,acc,captured,age,distance);
 return target_id;
end $$;

create function public.save_place(p_data jsonb) returns text language plpgsql security definer set search_path=public,pg_temp as $$
declare entry jsonb; target text:=p_data->>'id';
begin
 if jsonb_array_length(p_data->'scenes')=0 then raise exception '地点必须提供关联作品与来源'; end if;
 insert into places(id,name,city,country,address,latitude,longitude,radius_meters,precision,description,coordinate_source,verified)
 values(target,p_data->>'name',p_data->>'city',p_data->>'country',coalesce(p_data->>'address',''),(p_data->>'latitude')::double precision,(p_data->>'longitude')::double precision,(p_data->>'radiusMeters')::integer,coalesce(p_data->>'precision','landmark'),coalesce(p_data->>'description',''),coalesce(p_data->>'coordinateSource',''),coalesce((p_data->>'verified')::boolean,false))
 on conflict(id) do update set name=excluded.name,city=excluded.city,country=excluded.country,address=excluded.address,latitude=excluded.latitude,longitude=excluded.longitude,radius_meters=excluded.radius_meters,precision=excluded.precision,description=excluded.description,coordinate_source=excluded.coordinate_source,verified=excluded.verified,updated_at=now();
 delete from scenes where place_id=target;
 for entry in select value from jsonb_array_elements(p_data->'scenes') loop
   insert into scenes(id,place_id,artist_id,work,kind,timestamp_seconds,source_url,image_url,image_credit,image_status,note)
   values(entry->>'id',target,entry->>'artistId',entry->>'work',coalesce(entry->>'kind','mv'),(entry->>'timestampSeconds')::integer,entry->>'sourceUrl',nullif(entry->>'imageUrl',''),nullif(entry->>'imageCredit',''),coalesce(entry->>'imageStatus','missing'),coalesce(entry->>'note',''));
 end loop;
 return target;
end $$;

create function public.reserve_reference(p_path text,p_size integer) returns text language plpgsql security definer set search_path=public,pg_temp as $$
begin
 perform pg_advisory_xact_lock(828410);
 if p_size not between 4 and 409600 then raise exception '图片超过400KB'; end if;
 if (select coalesce(sum(size_bytes),0) from media_assets)+(select coalesce(sum(size_bytes),0) from reference_assets)+p_size>734003200 then raise exception '圈子图片额度已满'; end if;
 insert into reference_assets(path,size_bytes) values(p_path,p_size);
 return p_path;
end $$;

create function public.delete_checkin_checked(p_user uuid,p_id uuid,p_admin boolean) returns text language plpgsql security definer set search_path=public,pg_temp as $$
declare item public.checkins; tomb public.deleted_checkins; owner_id uuid;
begin
 if not exists(select 1 from profiles where id=p_user and active) then raise exception '账号不可用'; end if;
 if p_admin and not exists(select 1 from profiles where id=p_user and active and role='admin') then raise exception '仅管理员可以管理圈内分享'; end if;
 perform pg_advisory_xact_lock(hashtextextended(p_id::text,0));
 select * into item from checkins where id=p_id;
 if found then
   if p_admin then
     if item.visibility<>'circle' then raise exception '管理员不能删除他人私密记录'; end if;
   elsif item.user_id<>p_user then raise exception '只能删除自己的打卡'; end if;
   owner_id:=item.user_id;
   insert into deleted_checkins(id,user_id,deleted_by) values(p_id,owner_id,p_user) on conflict(id) do nothing;
   delete from checkins where id=p_id;
 else
   select * into tomb from deleted_checkins where id=p_id;
   if found then
     if tomb.user_id<>p_user and (not p_admin or tomb.deleted_by<>p_user) then raise exception '不能清理其他人的记录'; end if;
     owner_id:=tomb.user_id;
   else
     -- Failed uploads without a DB checkin may be deleted only by their own user.
     if exists(select 1 from media_assets where checkin_id=p_id and user_id<>p_user) then raise exception '不能清理其他人的照片'; end if;
     owner_id:=p_user;
     insert into deleted_checkins(id,user_id,deleted_by) values(p_id,p_user,p_user);
   end if;
 end if;
 return owner_id::text||'/'||p_id::text||'.jpg';
end $$;

-- Scalar JSON aggregate avoids PostgREST's default 1000-row ceiling and page drift
-- when Android reconciles a complete authoritative collection against local records.
create function public.my_checkins_snapshot(p_user uuid) returns jsonb language sql stable security definer set search_path=public,pg_temp as $$
 select coalesce(jsonb_agg(jsonb_build_object(
  'id',c.id,'placeId',c.place_id,'note',c.note,'visibility',c.visibility,'photoPath',c.photo_path,
  'clientCreatedAt',c.client_created_at,
  'evidence',jsonb_build_object('latitude',e.latitude,'longitude',e.longitude,'accuracy',e.accuracy,'capturedAt',e.captured_at,'ageMillis',e.age_millis)
 ) order by c.id),'[]'::jsonb)
 from checkins c join checkin_evidence e on e.checkin_id=c.id
 where c.user_id=p_user and exists(select 1 from profiles where id=p_user and active);
$$;

revoke all on function public.is_active(),public.is_admin(),public.can_read_checkin(uuid) from public,anon;
grant execute on function public.is_active(),public.is_admin(),public.can_read_checkin(uuid) to authenticated,service_role;
revoke all on function public.consume_rate_limit(text,integer,integer),public.reserve_photo(uuid,uuid,integer,text),public.create_checkin_checked(uuid,jsonb),public.save_place(jsonb) from public,anon,authenticated;
grant execute on function public.consume_rate_limit(text,integer,integer),public.reserve_photo(uuid,uuid,integer,text),public.create_checkin_checked(uuid,jsonb),public.save_place(jsonb) to service_role;
revoke all on function public.reserve_reference(text,integer),public.delete_checkin_checked(uuid,uuid,boolean) from public,anon,authenticated;
grant execute on function public.reserve_reference(text,integer),public.delete_checkin_checked(uuid,uuid,boolean) to service_role;
revoke all on function public.my_checkins_snapshot(uuid) from public,anon,authenticated;
grant execute on function public.my_checkins_snapshot(uuid) to service_role;

insert into storage.buckets(id,name,public,file_size_limit,allowed_mime_types)
 values('pilgrimage-media','pilgrimage-media',false,409600,array['image/jpeg'])
 on conflict(id) do update set public=false,file_size_limit=409600,allowed_mime_types=array['image/jpeg'];
insert into storage.buckets(id,name,public,file_size_limit,allowed_mime_types)
 values('reference-media','reference-media',true,409600,array['image/jpeg'])
 on conflict(id) do update set public=true,file_size_limit=409600,allowed_mime_types=array['image/jpeg'];
-- No storage.objects policy is created. Only authenticated app-api mediates image reads/writes.
-- Keep pilgrimage-media private. Only reference-media may be public; never add broad client write policies.
