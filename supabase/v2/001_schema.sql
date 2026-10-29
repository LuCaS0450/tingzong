-- V2 uses a separate schema. Do not run against production until P0 and backup pass.
-- On an existing project this adds V2 without destroying the V1 public schema.
begin;
create schema if not exists extensions;
create extension if not exists postgis with schema extensions;
create extension if not exists pg_trgm with schema extensions;
create extension if not exists fuzzystrmatch with schema extensions;
create schema v2;
set local search_path = v2, extensions, public;

create table profiles (
 id uuid primary key references auth.users(id) on delete cascade, nickname text not null default '' check(length(nickname)<=32),
 bio text not null default '' check(length(bio)<=200), role text not null default 'member' check(role in ('member','admin')),
 status text not null default 'active' check(status in ('active','disabled','deleting')), created_at timestamptz not null default now()
);
create table artists(id text primary key,name text not null,aliases jsonb not null default '[]');
create table works(id text primary key,title text not null,kind text not null default 'song',aliases jsonb not null default '[]');
create table work_artists(work_id text references works,artist_id text references artists,primary key(work_id,artist_id));
create table locations (
 id text primary key,name text not null,city text not null,country text not null,address text not null default '',
 latitude double precision not null check(latitude between -90 and 90),longitude double precision not null check(longitude between -180 and 180),
 geo geography(Point,4326) generated always as (st_setsrid(st_makepoint(longitude,latitude),4326)::geography) stored,
 checkin_radius_m integer not null default 200 check(checkin_radius_m between 1 and 10000),
 precision text not null default 'landmark',description text not null default '',coordinate_source text not null default '',
 verified boolean not null default true,submitted_by uuid references profiles on delete set null
);
create index locations_geo on locations using gist(geo);
create table scenes (
 id text primary key,location_id text not null references locations,work_id text not null references works,
 artist_id text not null references artists,kind text not null default 'mv',timestamp_seconds integer,
 source_url text not null default '',image_url text,image_credit text,image_status text not null default 'missing',note text not null default ''
);
create table app_config (
 id boolean primary key default true check(id), registration_enabled boolean not null default false,
 image_upload_enabled boolean not null default true,capsule_creation_enabled boolean not null default true,
 submission_enabled boolean not null default true,challenge_enabled boolean not null default true,maintenance_mode boolean not null default false,
 max_images_per_user integer not null default 100 check(max_images_per_user between 1 and 100),
 max_storage_bytes bigint not null default 734003200 check(max_storage_bytes>0),schema_version integer not null default 2
);
insert into app_config default values;
create table favorites(user_id uuid references profiles on delete cascade,location_id text references locations,primary key(user_id,location_id));
create table media_assets (
 id uuid primary key default gen_random_uuid(),user_id uuid not null references profiles,
 path text not null unique,size_bytes integer not null check(size_bytes between 4 and 409600),
 sha256 text not null check(sha256 ~ '^[0-9a-f]{64}$'),state text not null default 'reserved' check(state in ('reserved','ready','deleting')),
 request_id uuid not null,created_at timestamptz not null default now(),unique(user_id,request_id)
);
create table content (
 id uuid primary key default gen_random_uuid(),kind text not null check(kind in ('message','capsule','visit','recreation')),
 user_id uuid references profiles,location_id text references locations,scene_id text references scenes,
 body text not null default '' check(length(body)<=1000),song text not null default '' check(length(song)<=100),
 artist text not null default '' check(length(artist)<=100),work_id text references works,
 place_name text not null default '' check(length(place_name)<=100),
 latitude double precision check(latitude between -90 and 90),longitude double precision check(longitude between -180 and 180),
 geo geography(Point,4326) generated always as (st_setsrid(st_makepoint(longitude,latitude),4326)::geography) stored,
 media_id uuid unique references media_assets,visibility text not null default 'private' check(visibility in ('private','public')),
 is_hidden boolean not null default false,version integer not null default 1,
 verified_at timestamptz,accuracy_m double precision,distance_m double precision,
 created_at timestamptz not null default now(),updated_at timestamptz not null default now(),
 check(user_id is not null or kind='message'),
 check((kind='capsule' and latitude is not null and longitude is not null and location_id is null) or (kind<>'capsule' and latitude is null and longitude is null and location_id is not null)),
 check(kind<>'recreation' or scene_id is not null),
 check(kind not in ('message','capsule') or (visibility='public' and media_id is null))
);
create unique index one_message on content(user_id,location_id) where kind='message';
create unique index one_visit on content(user_id,location_id) where kind='visit';
create unique index one_recreation on content(user_id,scene_id) where kind='recreation';
create index content_geo on content using gist(geo) where kind='capsule';
create index content_location on content(location_id,kind,created_at desc);
create table likes(user_id uuid references profiles on delete cascade,content_id uuid references content on delete cascade,primary key(user_id,content_id));
create table submissions (
 id uuid primary key default gen_random_uuid(),user_id uuid references profiles on delete set null,
 submission_type text not null check(submission_type in ('new_location','correction')),location_id text references locations,
 artist text not null,title text not null,place_name text not null,description text not null default '',
 source_url text not null default '',latitude double precision,longitude double precision,media_id uuid unique references media_assets,
 status text not null default 'pending' check(status in ('pending','approved','rejected')),review_note text not null default '',
 linked_location_id text references locations,created_at timestamptz not null default now()
);
create table reports (
 id uuid primary key default gen_random_uuid(),reporter_id uuid references profiles on delete set null,
 target_id uuid not null,target_version integer not null,target_kind text not null,
 reason text not null check(reason in ('垃圾内容','不友善内容','色情 / 违法','版权问题','信息错误','其他')),
 status text not null default 'pending' check(status in ('pending','resolved','dismissed')),
 snapshot jsonb not null,created_at timestamptz not null default now(),unique(reporter_id,target_id,target_version)
);
create table questions (
 id text primary key,challenge_type text not null check(challenge_type in ('multiple_choice','map_guess')),
 question text not null,image_url text,options jsonb not null default '[]',correct_option text,
 answer_location_id text references locations,answer_latitude double precision,answer_longitude double precision,
 correct_radius_m integer check(correct_radius_m>0),explanation text not null,source_url text not null default '',
 published boolean not null default false,
 check((challenge_type='multiple_choice' and jsonb_array_length(options)=4 and correct_option is not null) or (challenge_type='map_guess' and answer_latitude is not null and answer_longitude is not null and correct_radius_m is not null))
);
create table challenge_schedule(challenge_date date primary key,question_id text not null references questions);
create table challenge_attempts(user_id uuid references profiles on delete cascade,question_id text references questions,answer jsonb not null,result jsonb not null,correct boolean not null,created_at timestamptz not null default now(),primary key(user_id,question_id));
create table badges(id text primary key,name text not null,description text not null);
insert into badges values
 ('first_visit','初次抵达','完成第一次实地巡礼'),('first_recreation','重逢镜头','发布第一次场景复刻'),
 ('first_message','留下回声','发布第一次地点留言'),('first_capsule','封存此刻','创建第一次音乐胶囊'),
 ('ten_challenges','音乐探索者','正式完成十道不同挑战'),('ten_correct','场景知音','首次答对十道不同挑战'),
 ('first_submission','地点发现者','第一次地点投稿审核通过');
create table user_badges(user_id uuid references profiles on delete cascade,badge_id text references badges,earned_at timestamptz not null default now(),primary key(user_id,badge_id));
create table badge_revocations(user_id uuid references profiles on delete cascade,badge_id text references badges,revoked_at timestamptz not null default now(),primary key(user_id,badge_id));
create table audit_log(id bigint generated always as identity primary key,actor_id uuid references profiles on delete set null,action text not null,target_id text,detail jsonb not null default '{}',created_at timestamptz not null default now());
create table rate_limits(key text primary key,window_start timestamptz not null,hits integer not null);

-- Row-level access is enforced even if the schema is accidentally exposed later.
create function is_admin() returns boolean language sql stable security definer set search_path=v2,public,extensions as $$
 select exists(select 1 from profiles where id=auth.uid() and role='admin' and status='active')
$$;
create function can_read_content(c content) returns boolean language sql stable security definer set search_path=v2,public,extensions as $$
 select (c.user_id=auth.uid() and not c.is_hidden) or
 (c.visibility='public' and not c.is_hidden and exists(select 1 from profiles where id=auth.uid() and status<>'deleting')) or
 (c.visibility='public' and c.is_hidden and is_admin())
$$;
do $$
declare t text;
begin
 foreach t in array array['profiles','artists','works','work_artists','locations','scenes','app_config','favorites','media_assets','content','likes','submissions','reports','questions','challenge_schedule','challenge_attempts','badges','user_badges','badge_revocations','audit_log','rate_limits'] loop
  execute format('alter table v2.%I enable row level security',t);
 end loop;
end $$;
grant usage on schema v2 to anon,authenticated,service_role;
grant select on artists,works,work_artists,locations,scenes to anon,authenticated;
create policy catalog_artists on artists for select using(true);
create policy catalog_works on works for select using(true);
create policy catalog_wa on work_artists for select using(true);
create policy catalog_locations on locations for select using(verified);
create policy catalog_scenes on scenes for select using(exists(select 1 from locations where id=location_id and verified));
grant select on profiles,favorites,content,media_assets,likes,submissions,reports,challenge_attempts,user_badges,badges to authenticated;
create policy profile_self on profiles for select using(id=auth.uid());
create policy favorites_self on favorites for select using(user_id=auth.uid());
create policy content_read on content for select using(can_read_content(content));
create policy media_read on media_assets for select using(
 state='ready' and ((user_id=auth.uid() and not exists(select 1 from content c where c.media_id=media_assets.id and c.is_hidden)) or exists(select 1 from content c where c.media_id=media_assets.id and can_read_content(c))));
create policy likes_read on likes for select using(user_id=auth.uid());
create policy submission_read on submissions for select using(user_id=auth.uid() or is_admin());
create policy reports_self on reports for select using(reporter_id=auth.uid() or is_admin());
create policy attempts_self on challenge_attempts for select using(user_id=auth.uid());
create policy badges_read on badges for select using(true);
create policy user_badges_self on user_badges for select using(user_id=auth.uid());
-- No direct client writes or reads of answer/config/admin internals.
grant all on all tables in schema v2 to service_role;
grant usage,select on all sequences in schema v2 to service_role;
revoke all on all functions in schema v2 from public,anon,authenticated;
grant execute on function is_admin(),can_read_content(content) to authenticated;
commit;
