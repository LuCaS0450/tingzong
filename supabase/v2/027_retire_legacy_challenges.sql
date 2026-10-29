-- One-time, all-account retirement. V4 and musical/place/business records are retained.
begin;
set local lock_timeout = '10s';
set local statement_timeout = '60s';

alter function v2.read_api(uuid,text,jsonb) rename to read_api_before_tingzong;
alter function v2.write_api(uuid,text,jsonb) rename to write_api_before_tingzong;
revoke all on function v2.read_api_before_tingzong(uuid,text,jsonb),v2.write_api_before_tingzong(uuid,text,jsonb) from public,anon,authenticated,service_role;

create function v2.read_api(actor uuid,action text,p jsonb) returns jsonb
language plpgsql security definer set search_path=pg_catalog,v2,extensions,public as $$
declare result jsonb;
begin
 if action in ('challenges','scrapbook') then raise exception '旧版挑战已下线'; end if;
 result=v2.read_api_before_tingzong(actor,action,p);
 if action='self' then return result-'challenge_count'-'correct_count'; end if;
 if action='config' then return result-'challenge_enabled'; end if;
 if action='admin' then return jsonb_set(result,'{config}',(result->'config')-'challenge_enabled'); end if;
 return result;
end $$;
create function v2.write_api(actor uuid,action text,p jsonb) returns jsonb
language plpgsql security definer set search_path=pg_catalog,v2,extensions,public as $$
begin
 if action='answer' or (action='admin_config' and p ? 'challenge_enabled') then
  raise exception '旧版挑战已下线';
 end if;
 return v2.write_api_before_tingzong(actor,action,p);
end $$;
revoke all on function v2.read_api(uuid,text,jsonb),v2.write_api(uuid,text,jsonb) from public,anon,authenticated;
grant execute on function v2.read_api(uuid,text,jsonb),v2.write_api(uuid,text,jsonb) to service_role;

create or replace function public.pilgrimage_v2(actor uuid,action text,payload jsonb default '{}'::jsonb) returns jsonb
language plpgsql security definer set search_path=pg_catalog,v2,extensions,public as $$
begin
 if action in ('challenges','scrapbook','answer') then raise exception '旧版挑战已下线'; end if;
 if actor is not null then
  insert into v2.profiles(id) select id from auth.users where id=actor and email_confirmed_at is not null on conflict do nothing;
 end if;
 if action in ('config','catalog','search','self','profile','content','nearby','submissions','media','admin') then
  return v2.read_api(actor,action,payload);
 end if;
 return v2.write_api(actor,action,payload);
end $$;

update v2.app_config set challenge_enabled=false;
alter table v2.app_config add constraint tingzong_legacy_stays_disabled check (not challenge_enabled);
delete from v2.challenge_attempts;
delete from v2.user_scrapbook_cases;
delete from v2.challenge_schedule;
delete from v2.questions;
delete from v2.user_badges where badge_id in ('ten_challenges','ten_correct');
delete from v2.badge_revocations where badge_id in ('ten_challenges','ten_correct');
delete from v2.badges where id in ('ten_challenges','ten_correct');

-- Keep empty relations for existing non-game API function dependencies, reject all new legacy writes.
create function v2.reject_retired_challenge() returns trigger language plpgsql set search_path=pg_catalog as $$
begin raise exception '旧版挑战已下线'; end $$;
create trigger tingzong_retired_questions before insert or update on v2.questions for each statement execute function v2.reject_retired_challenge();
create trigger tingzong_retired_schedule before insert or update on v2.challenge_schedule for each statement execute function v2.reject_retired_challenge();
create trigger tingzong_retired_attempts before insert or update on v2.challenge_attempts for each statement execute function v2.reject_retired_challenge();
create trigger tingzong_retired_scrapbook before insert or update on v2.user_scrapbook_cases for each statement execute function v2.reject_retired_challenge();
commit;
