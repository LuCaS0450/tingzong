-- User-authorized permanent retirement. Migration ledger remains intact.
-- The executor backs up this schema and RPC and compares all unrelated rows.
begin;
drop function if exists public.pilgrimage_v4(uuid,text,jsonb);
drop schema if exists detective_v4 cascade;
commit;
