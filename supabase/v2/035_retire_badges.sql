-- Retire badges without changing the content writes that used to award them.
begin;

-- Earlier deployed write functions still call this compatibility function.
create or replace function v2.award(actor uuid,b text) returns void
language plpgsql security definer set search_path=pg_catalog,v2 as $$
begin
 return;
end $$;

create or replace function v2.read_api(actor uuid,action text,p jsonb) returns jsonb
language plpgsql security definer set search_path=pg_catalog,v2,extensions,public as $$
declare result jsonb;
begin
 if action='content' and p->>'kind'='capsule' then raise exception '音乐胶囊已下线，功能已关闭';end if;
 result=v2.read_api_before_capsules(actor,action,p);
 if action='config' then return result-'capsule_creation_enabled';end if;
 if action='self' then return result-'badges';end if;
 if action='admin' then
  return jsonb_set(result-'badges'-'awards','{config}',(result->'config')-'capsule_creation_enabled');
 end if;
 if action='nearby' then return jsonb_set(result,'{capsules}','[]'::jsonb);end if;
 return result;
end $$;

create or replace function v2.write_api(actor uuid,action text,p jsonb) returns jsonb
language plpgsql security definer set search_path=pg_catalog,v2,extensions,public as $$
begin
 if action='revoke_badge' then raise exception '徽章已下线，功能已关闭';end if;
 if action='create_capsule' or (action='admin_config' and p ? 'capsule_creation_enabled') then
  raise exception '音乐胶囊已下线，功能已关闭';
 end if;
 return v2.write_api_before_capsules(actor,action,p);
end $$;

delete from v2.user_badges;
delete from v2.badge_revocations;
delete from v2.badges;
delete from v2.audit_log where action='revoke_badge' or detail ? 'badge_id';
revoke all on v2.badges,v2.user_badges,v2.badge_revocations from public,anon,authenticated;

commit;
