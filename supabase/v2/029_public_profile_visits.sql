-- Add public visits without changing certification, write operations or existing profile fields.
begin;
set local lock_timeout='10s';
set local statement_timeout='60s';
create or replace function v2.read_api(actor uuid,action text,p jsonb) returns jsonb
language plpgsql security definer set search_path=pg_catalog,v2,extensions,public as $$
declare result jsonb;
begin
 if action in ('challenges','scrapbook') then raise exception '旧版挑战已下线';end if;
 -- The existing reader retains its authentication and account-status checks.
 result=v2.read_api_before_tingzong(actor,action,p);
 if action='self' then return result-'challenge_count'-'correct_count';end if;
 if action='config' then return result-'challenge_enabled';end if;
 if action='admin' then return jsonb_set(result,'{config}',(result->'config')-'challenge_enabled');end if;
 if action='profile' then
  return result || jsonb_build_object('visits',coalesce((
   select jsonb_agg(v2.content_json(c,actor) order by c.created_at desc,c.id)
   from v2.content c join v2.profiles author on author.id=c.user_id
   where c.user_id=(p->>'id')::uuid and c.kind='visit'
    and c.visibility='public' and not c.is_hidden and author.status='active'
    and (length(trim(c.body))>0 or c.media_id is not null)
    and c.verified_at is not null
    and v2.visible(c,actor)
  ),'[]'::jsonb));
 end if;
 return result;
end $$;
revoke all on function v2.read_api(uuid,text,jsonb) from public,anon,authenticated;
grant execute on function v2.read_api(uuid,text,jsonb) to service_role;
commit;
