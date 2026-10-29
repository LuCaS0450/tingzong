-- Compensate Storage writes if readiness fails, including a concurrent account deletion.
-- Privileged housekeeping remains inaccessible to clients.
begin;
set local search_path=v2,extensions,public;
create or replace function public.pilgrimage_v2_cleanup(actor uuid,operation text,asset uuid default null) returns jsonb language plpgsql security definer set search_path=v2,extensions,public as $$
declare failed_asset media_assets;
begin
 if operation='abort_upload' then
  -- A failed upload can race deletion or another successful retry. Lock the
  -- reservation and never delete media already associated with business data.
  select * into failed_asset from media_assets where id=asset and user_id=actor for update;
  if not found then return '{"missing":true}'::jsonb;end if;
  if exists(select 1 from content where media_id=asset) or exists(select 1 from submissions where media_id=asset) then
   return '{"linked":true}'::jsonb;
  end if;
  update media_assets set state='deleting' where id=asset and user_id=actor;
  return jsonb_build_object('path',failed_asset.path);
 end if;
 if operation='pending' then return coalesce((select jsonb_agg(to_jsonb(m)) from media_assets m where user_id=actor and (state='deleting' or (created_at<now()-interval '24 hours' and not exists(select 1 from content where media_id=m.id) and not exists(select 1 from submissions where media_id=m.id)))),'[]');end if;
 if operation='removed' then
  delete from media_assets where id=asset and user_id=actor and not exists(select 1 from content where media_id=asset) and not exists(select 1 from submissions where media_id=asset);return '{}';
 end if;
 if operation='account_finalize' then
  if not exists(select 1 from profiles where id=actor and status='deleting') then raise exception '注销尚未开始';end if;
  update content set user_id=null where user_id=actor and kind='message';
  delete from content where user_id=actor;
  update submissions set media_id=null where user_id=actor;
  delete from submissions where user_id=actor and status<>'approved';
  delete from media_assets where user_id=actor;
  update submissions set user_id=null where user_id=actor;
  delete from reports where reporter_id=actor;
  delete from rate_limits where key like actor::text||':%';
  update audit_log set actor_id=null,detail=detail-'user_id'-'id',target_id=null where actor_id=actor or target_id=actor::text or detail->>'user_id'=actor::text;
  -- Keep the deleting profile until Auth deletion succeeds. A failed Auth call can retry.
  return '{}';
 end if;
 raise exception '未知清理操作';
end $$;
commit;
