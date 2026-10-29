-- Test restrictions are private server configuration, never part of public config.
begin;
create table v2.registration_guard (
 id boolean primary key default true check(id), test_mode boolean not null default false
);
insert into v2.registration_guard default values;
create table v2.registration_allowlist (
 email text primary key check(email=lower(trim(email)) and position('@' in email)>1),
 expires_at timestamptz not null
);
alter table v2.registration_guard enable row level security;
alter table v2.registration_allowlist enable row level security;
revoke all on v2.registration_guard,v2.registration_allowlist from public,anon,authenticated;
grant all on v2.registration_guard,v2.registration_allowlist to service_role;
create or replace function public.pilgrimage_before_user_created(event jsonb)
returns jsonb language plpgsql security definer set search_path=v2,extensions,public as $$
begin
 if not coalesce((select registration_enabled and not maintenance_mode from app_config),false) then
  return jsonb_build_object('error',jsonb_build_object('http_code',403,'message','新用户注册暂时关闭'));
 end if;
 if coalesce((select test_mode from registration_guard),false) and not exists(
  select 1 from registration_allowlist where email=lower(trim(event->'user'->>'email')) and expires_at>now()
 ) then
  return jsonb_build_object('error',jsonb_build_object('http_code',403,'message','当前仅接受已登记的测试邮箱'));
 end if;
 return '{}'::jsonb;
end $$;
revoke all on function public.pilgrimage_before_user_created(jsonb) from public,anon,authenticated;
grant execute on function public.pilgrimage_before_user_created(jsonb) to supabase_auth_admin;
commit;
