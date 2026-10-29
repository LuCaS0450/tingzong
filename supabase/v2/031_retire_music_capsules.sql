-- Permanent all-account retirement authorized by the user. Historical migrations stay intact.
begin;
set local lock_timeout='10s';
set local statement_timeout='90s';
set local search_path=pg_catalog,v2,extensions,public;

do $$ begin
 if exists(select 1 from v2.content where kind='capsule' and media_id is not null) then
  raise exception 'Unexpected capsule media: stop before deletion';
 end if;
end $$;

create temporary table retired_capsule_ids on commit drop as
 select id from v2.content where kind='capsule';
delete from v2.reports where target_kind='capsule' or snapshot->>'kind'='capsule'
 or target_id in(select id from retired_capsule_ids);
delete from v2.audit_log where target_id in(select id::text from retired_capsule_ids)
 or detail->>'id' in(select id::text from retired_capsule_ids)
 or detail->>'kind'='capsule' or detail->>'badge_id'='first_capsule';
-- likes have an ON DELETE CASCADE foreign key; no media is attached to this content kind.
delete from v2.content where kind='capsule';
delete from v2.user_badges where badge_id='first_capsule';
delete from v2.badge_revocations where badge_id='first_capsule';
delete from v2.badges where id='first_capsule';
delete from v2.rate_limits where key like '%:create_capsule';
update v2.app_config set capsule_creation_enabled=false;
alter table v2.app_config add constraint music_capsules_stay_disabled check(not capsule_creation_enabled);
alter table v2.content add constraint music_capsules_retired check(kind<>'capsule');
drop index if exists v2.content_geo;

alter function v2.read_api(uuid,text,jsonb) rename to read_api_before_capsules;
alter function v2.write_api(uuid,text,jsonb) rename to write_api_before_capsules;
revoke all on function v2.read_api_before_capsules(uuid,text,jsonb),v2.write_api_before_capsules(uuid,text,jsonb)
 from public,anon,authenticated,service_role;

create function v2.read_api(actor uuid,action text,p jsonb) returns jsonb
language plpgsql security definer set search_path=pg_catalog,v2,extensions,public as $$
declare result jsonb;
begin
 if action='content' and p->>'kind'='capsule' then raise exception '音乐胶囊已下线';end if;
 result=v2.read_api_before_capsules(actor,action,p);
 if action='config' then return result-'capsule_creation_enabled';end if;
 if action='admin' then return jsonb_set(result,'{config}',(result->'config')-'capsule_creation_enabled');end if;
 if action='nearby' then return jsonb_set(result,'{capsules}','[]'::jsonb);end if;
 return result;
end $$;
create function v2.write_api(actor uuid,action text,p jsonb) returns jsonb
language plpgsql security definer set search_path=pg_catalog,v2,extensions,public as $$
begin
 if action='create_capsule' or (action='admin_config' and p ? 'capsule_creation_enabled') then
  raise exception '音乐胶囊已下线';
 end if;
 return v2.write_api_before_capsules(actor,action,p);
end $$;
revoke all on function v2.read_api(uuid,text,jsonb),v2.write_api(uuid,text,jsonb) from public,anon,authenticated;
grant execute on function v2.read_api(uuid,text,jsonb),v2.write_api(uuid,text,jsonb) to service_role;

-- Rebind the dispatcher to the new wrappers while retaining the existing retired-game guards.
create or replace function public.pilgrimage_v2(actor uuid,action text,payload jsonb default '{}'::jsonb) returns jsonb
language plpgsql security definer set search_path=pg_catalog,v2,extensions,public as $$
begin
 if action in ('challenges','scrapbook','answer') then raise exception '旧版挑战已下线';end if;
 if actor is not null then
  insert into v2.profiles(id) select id from auth.users where id=actor and email_confirmed_at is not null on conflict do nothing;
 end if;
 if action in ('config','catalog','search','self','profile','content','nearby','submissions','media','admin') then
  return v2.read_api(actor,action,payload);
 end if;
 return v2.write_api(actor,action,payload);
end $$;
commit;
