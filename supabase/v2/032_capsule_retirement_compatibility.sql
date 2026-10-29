-- Preserve the deployed Edge's existing feature_disabled mapping. No Edge/JWT configuration change.
begin;
create or replace function v2.read_api(actor uuid,action text,p jsonb) returns jsonb
language plpgsql security definer set search_path=pg_catalog,v2,extensions,public as $$
declare result jsonb;
begin
 if action='content' and p->>'kind'='capsule' then raise exception '音乐胶囊已下线，功能已关闭';end if;
 result=v2.read_api_before_capsules(actor,action,p);
 if action='config' then return result-'capsule_creation_enabled';end if;
 if action='admin' then return jsonb_set(result,'{config}',(result->'config')-'capsule_creation_enabled');end if;
 if action='nearby' then return jsonb_set(result,'{capsules}','[]'::jsonb);end if;
 return result;
end $$;
create or replace function v2.write_api(actor uuid,action text,p jsonb) returns jsonb
language plpgsql security definer set search_path=pg_catalog,v2,extensions,public as $$
begin
 if action='create_capsule' or (action='admin_config' and p ? 'capsule_creation_enabled') then
  raise exception '音乐胶囊已下线，功能已关闭';
 end if;
 return v2.write_api_before_capsules(actor,action,p);
end $$;
commit;
