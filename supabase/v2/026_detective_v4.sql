-- V4 is additive. This migration does not publish a case or replace a V1/V2/V3 API.
begin;
create schema detective_v4;
grant usage on schema detective_v4 to anon,authenticated,service_role;
set local search_path = detective_v4, extensions, public;

create table v4_cases (
 id text primary key check(id ~ '^v4-case-[a-z0-9-]{1,60}$'),
 title text not null, status text not null default 'preview' check(status in ('preview','published','withdrawn')),
 current_version integer not null check(current_version > 0)
);
create table v4_case_access (
 case_id text not null references v4_cases(id) on delete cascade,
 user_id uuid not null references v2.profiles(id) on delete cascade,
 primary key(case_id,user_id)
);
create table v4_case_versions (
 case_id text not null references v4_cases(id) on delete restrict, version integer not null check(version > 0),
 schema_version integer not null default 1 check(schema_version = 1),
 content_hash text not null check(content_hash ~ '^[a-f0-9]{64}$'),
 definition jsonb not null check(jsonb_typeof(definition)='object'),
 status text not null default 'preview' check(status in ('preview','published','withdrawn')),
 created_at timestamptz not null default now(), primary key(case_id,version),
 check(definition->>'caseId'=case_id and (definition->>'caseVersion')::integer=version)
);
create function v4_immutable_version() returns trigger language plpgsql set search_path=pg_catalog as $$
begin
 if (new.case_id,new.version,new.schema_version,new.content_hash,new.definition) is distinct from
    (old.case_id,old.version,old.schema_version,old.content_hash,old.definition) then
  raise exception 'V4 version content is immutable';
 end if;
 return new;
end $$;
create trigger v4_version_immutable before update on v4_case_versions for each row execute function v4_immutable_version();

create table v4_progress (
 user_id uuid not null references v2.profiles(id) on delete cascade,
 case_id text not null, case_version integer not null,
 snapshot jsonb not null, snapshot_updated_at bigint not null check(snapshot_updated_at >= 0),
 revision bigint not null default 1, updated_at timestamptz not null default now(),
 primary key(user_id,case_id,case_version),
 foreign key(case_id,case_version) references v4_case_versions(case_id,version) on delete restrict
);
create table v4_events (
 user_id uuid not null references v2.profiles(id) on delete cascade, case_id text not null, case_version integer not null,
 event_id uuid not null, event_key text not null, created_at bigint not null check(created_at >= 0),
 primary key(user_id,case_id,case_version,event_key), unique(user_id,event_id),
 foreign key(case_id,case_version) references v4_case_versions(case_id,version) on delete restrict
);
create table v4_collections (
 user_id uuid not null references v2.profiles(id) on delete cascade, case_id text not null,
 first_case_version integer not null, collection_id uuid not null default gen_random_uuid() unique,
 result_id uuid not null default gen_random_uuid() unique, resolution_id text not null default 'resolution-v1',
 collected_on date not null default (now() at time zone 'Asia/Shanghai')::date,
 collected_at timestamptz not null default now(), case_snapshot jsonb not null, answer_snapshot jsonb not null,
 primary key(user_id,case_id),
 foreign key(case_id,first_case_version) references v4_case_versions(case_id,version) on delete restrict
);
create table v4_requests (
 user_id uuid not null references v2.profiles(id) on delete cascade, request_id uuid not null,
 action text not null check(action in ('answer','progress')), payload jsonb not null, response jsonb not null,
 created_at timestamptz not null default now(), primary key(user_id,request_id)
);

alter table v4_cases enable row level security;
alter table v4_case_access enable row level security;
alter table v4_case_versions enable row level security;
alter table v4_progress enable row level security;
alter table v4_events enable row level security;
alter table v4_collections enable row level security;
alter table v4_requests enable row level security;
create policy v4_access_self on v4_case_access for select using(user_id=auth.uid());
create policy v4_cases_read on v4_cases for select using(status='published' or
 (status='preview' and exists(select 1 from v4_case_access a where a.case_id=id and a.user_id=auth.uid())));
create policy v4_versions_read on v4_case_versions for select using(status<>'withdrawn' and exists(
 select 1 from v4_cases c where c.id=case_id and c.status<>'withdrawn' and
 ((c.status='published' and v4_case_versions.status='published') or exists(
 select 1 from v4_case_access a where a.case_id=c.id and a.user_id=auth.uid()))));
create policy v4_progress_self on v4_progress for select using(user_id=auth.uid());
create policy v4_events_self on v4_events for select using(user_id=auth.uid());
create policy v4_collections_self on v4_collections for select using(user_id=auth.uid());
create policy v4_requests_self on v4_requests for select using(user_id=auth.uid());
grant select on v4_cases,v4_case_versions to anon,authenticated;
grant select on v4_case_access to anon;
grant select on v4_case_access,v4_progress,v4_events,v4_collections,v4_requests to authenticated;
grant all on v4_cases,v4_case_access,v4_case_versions,v4_progress,v4_events,v4_collections,v4_requests to service_role;

create function v4_set(a jsonb,b jsonb default '[]') returns jsonb language sql immutable set search_path=pg_catalog as $$
 select coalesce(jsonb_agg(x order by x),'[]') from (select distinct value x from
 jsonb_array_elements(a||b)) s
$$;
create function v4_snapshot_valid(d jsonb,s jsonb) returns boolean language plpgsql immutable set search_path=pg_catalog,detective_v4 as $$
declare c jsonb; r jsonb;
begin
 if jsonb_typeof(s)<>'object' or not (s ?& array['page','clueId','acquired','placed','reasons','assisted','candidate','solved','collected','collectedOn']) or octet_length(s::text)>12000 or
    not coalesce(s->>'page' in ('brief','board','clue','deduction','map','ticket','dossier'),false) or
    not exists(select 1 from jsonb_array_elements(d->'clues') x where x->>'id'=s->>'clueId') then return false; end if;
 foreach c in array array[s->'acquired',s->'placed',s->'reasons',s->'assisted'] loop
  if jsonb_typeof(c) is distinct from 'array' or jsonb_array_length(c)>8 or v4_set(c) is distinct from c then
   -- Input order is free, but duplicate entries are not.
   if jsonb_typeof(c) is distinct from 'array' then return false; end if;
   if jsonb_array_length(c)>8 or jsonb_array_length(v4_set(c))<>jsonb_array_length(c) then return false; end if;
  end if;
 end loop;
 if exists(select 1 from jsonb_array_elements_text(s->'acquired') x where not exists(
    select 1 from jsonb_array_elements(d->'clues') k where k->>'id'=x)) or
    exists(select 1 from jsonb_array_elements(s->'placed') x where x not in ('0'::jsonb,'1'::jsonb,'2'::jsonb,'3'::jsonb)) or
    not ((s->'acquired') @> (s->'assisted')) or jsonb_array_length(s->'reasons')>2 or
    exists(select 1 from jsonb_array_elements_text(s->'reasons') x where not exists(
    select 1 from jsonb_array_elements(d->'reasons') k where k->>'id'=x)) then return false; end if;
 if s->'candidate'<>'null'::jsonb and not exists(select 1 from jsonb_array_elements(d->'candidates') x where x->>'id'=s->>'candidate') then return false; end if;
 if jsonb_typeof(s->'solved') is distinct from 'boolean' or jsonb_typeof(s->'collected') is distinct from 'boolean' then return false; end if;
 if (s->>'solved')::boolean then
  if s->>'candidate' <> d->'deductionRule'->>'targetCandidateId' or
     jsonb_array_length(s->'acquired') < (d->'deductionRule'->>'minimumObtainedClues')::integer or
     not exists(select 1 from jsonb_array_elements(d->'deductionRule'->'acceptedReasonSets') x where v4_set(x)=v4_set(s->'reasons')) then return false; end if;
  for r in select value from jsonb_array_elements(d->'reasons') loop
   if (s->'reasons') ? (r->>'id') and not ((s->'acquired') @> (r->'clueRefs')) then return false; end if;
  end loop;
 elsif s->>'page' in ('map','ticket','dossier') or (s->>'collected')::boolean then return false;
 end if;
 if (s->>'collected')::boolean then
  if not coalesce(s->>'collectedOn' ~ '^\d{4}-\d{2}-\d{2}$',false) then return false; end if;
  perform (s->>'collectedOn')::date;
 end if;
 return true;
exception when others then return false;
end $$;

create function v4_merge_snapshot(d jsonb,a jsonb,b jsonb,a_time bigint,b_time bigint) returns jsonb
language plpgsql immutable set search_path=pg_catalog,detective_v4 as $$
declare s jsonb;
begin
 s=case when b_time>=a_time then b else a end;
 if (a->>'solved')::boolean and not (s->>'solved')::boolean then s=a; end if;
 if (b->>'solved')::boolean and not (s->>'solved')::boolean then s=b; end if;
 s=s||jsonb_build_object('acquired',v4_set(a->'acquired',b->'acquired'),'assisted',v4_set(a->'assisted',b->'assisted'));
 if s->'acquired' ? 'clue-a' then s=s||'{"placed":[0,1,2,3]}'::jsonb; end if;
 if not v4_snapshot_valid(d,s) then raise exception 'Invalid merged V4 snapshot'; end if;
 return s;
end $$;

create function public.pilgrimage_v4(actor uuid,action text,payload jsonb default '{}') returns jsonb
language plpgsql security definer set search_path=pg_catalog,detective_v4,v2,extensions,public as $$
declare
 p jsonb=payload; case_key text; ver integer; req uuid; d jsonb; version_row v4_case_versions;
 previous v4_requests; progress_row v4_progress; collection_row v4_collections;
 response jsonb; code text; reasons jsonb; e jsonb; s jsonb; t bigint; already boolean;
begin
 -- Only the authenticated Edge may supply actor. No client can execute this RPC.
 if actor is null or not exists(select 1 from v2.profiles where profiles.id=actor and status='active') then
  return jsonb_build_object('status','rejected','code','unauthorized'); end if;
 if jsonb_typeof(p) is distinct from 'object' or octet_length(p::text)>32000 or action not in ('answer','progress','state','collections') then
  return jsonb_build_object('status','rejected','code','invalid_request'); end if;
 if action='collections' then return jsonb_build_object('collections',coalesce((
  select jsonb_agg(to_jsonb(c) - 'user_id' order by collected_at) from v4_collections c where user_id=actor),'[]')); end if;
 case_key=p->>'case_id';
 begin ver=(p->>'case_version')::integer;
 exception when others then return jsonb_build_object('status','rejected','code','invalid_request'); end;
 if case_key is null or ver is null or ver<=0 then return jsonb_build_object('status','rejected','code','invalid_request'); end if;
 if action in ('answer','progress') then
  begin req=(p->>'request_id')::uuid;
  exception when others then return jsonb_build_object('status','rejected','code','invalid_request'); end;
  if req is null then return jsonb_build_object('status','rejected','code','invalid_request'); end if;
  perform pg_advisory_xact_lock(hashtextextended('v4-request:'||actor||':'||req,0));
  select * into previous from v4_requests where user_id=actor and request_id=req;
  if found then
   if previous.action<>action or previous.payload<>p then return jsonb_build_object('status','rejected','code','request_conflict'); end if;
   return previous.response;
  end if;
 end if;
 perform pg_advisory_xact_lock(hashtextextended('v4-case:'||actor||':'||case_key,0));
 if not exists(select 1 from v4_cases c where c.id=case_key and c.status<>'withdrawn' and
 (c.status='published' or exists(select 1 from v4_case_access a where a.case_id=case_key and a.user_id=actor))) then
  return jsonb_build_object('status','rejected','code','case_unavailable'); end if;
 select * into version_row from v4_case_versions where case_id=case_key and version=ver;
 if not found then return jsonb_build_object('status','rejected','code','unsupported_version'); end if;
 if version_row.status='withdrawn' or (version_row.status='preview' and not exists(
 select 1 from v4_case_access a where a.case_id=case_key and a.user_id=actor)) then
  return jsonb_build_object('status','rejected','code','case_unavailable'); end if;
 if p->>'content_hash' is distinct from version_row.content_hash then return jsonb_build_object('status','rejected','code','unsupported_version'); end if;
 d=version_row.definition;
 select * into collection_row from v4_collections where user_id=actor and case_id=case_key;
 select * into progress_row from v4_progress where user_id=actor and case_id=case_key and case_version=ver;
 if action='state' then return jsonb_build_object('progress',case when progress_row.user_id is null then null else to_jsonb(progress_row)-'user_id' end,
 'events',coalesce((select jsonb_agg(to_jsonb(x)-'user_id' order by event_key) from v4_events x where user_id=actor and case_id=case_key and case_version=ver),'[]'),
 'collection',case when collection_row.user_id is null then null else to_jsonb(collection_row)-'user_id' end); end if;
 if action='answer' then
  if jsonb_typeof(p->'reason_ids') is distinct from 'array' then code='invalid_request';
  else
   reasons=v4_set(p->'reason_ids');
   if not exists(select 1 from jsonb_array_elements(d->'candidates') x where x->>'id'=p->>'candidate_id') or
    jsonb_array_length(reasons)<>2 or jsonb_array_length(p->'reason_ids')<>2 or exists(
    select 1 from jsonb_array_elements(reasons) x where jsonb_typeof(x)<>'string' or not exists(
    select 1 from jsonb_array_elements(d->'reasons') k where k->'id'=x)) then code='invalid_request';
   elsif p->>'candidate_id' <> d->'deductionRule'->>'targetCandidateId' then code='answer_conflict';
   elsif not exists(select 1 from jsonb_array_elements(d->'deductionRule'->'acceptedReasonSets') x where v4_set(x)=reasons) then code='reason_conflict'; end if;
  end if;
  if code is null then
   already=collection_row.user_id is not null;
   if not already then
    insert into v4_collections(user_id,case_id,first_case_version,case_snapshot,answer_snapshot)
    values(actor,case_key,ver,d,jsonb_build_object('candidate_id',p->>'candidate_id','reason_ids',reasons)) returning * into collection_row;
   end if;
   response=(to_jsonb(collection_row)-'user_id')||jsonb_build_object('status','accepted','case_version',ver,'already_collected',already);
  end if;
 else
  s=p->'snapshot';
  if not v4_snapshot_valid(d,s) or jsonb_typeof(p->'events') is distinct from 'array' or jsonb_array_length(p->'events')>16 then code='invalid_request';
  else
   begin
    t=(p->>'snapshot_updated_at')::bigint;
    if t is null or t<0 or t > (extract(epoch from now())*1000)::bigint+300000 then code='invalid_request'; end if;
    for e in select value from jsonb_array_elements(p->'events') loop
     perform (e->>'event_id')::uuid; perform (e->>'created_at')::bigint;
     if e->>'event_id' is null or e->>'created_at' is null or (e->>'created_at')::bigint<0 or
      not coalesce(e->>'event_key' in ('started','solved','collected') or exists(
      select 1 from jsonb_array_elements(d->'clues') k where e->>'event_key' in ('evidence:'||(k->>'id'),'assist:'||(k->>'id'))),false) or
      exists(select 1 from v4_events x where user_id=actor and event_id=(e->>'event_id')::uuid and
      (case_id<>case_key or case_version<>ver or event_key<>e->>'event_key')) then code='invalid_request'; end if;
    end loop;
   exception when others then code='invalid_request'; end;
  end if;
  if code is null then
   if progress_row.user_id is not null then s=v4_merge_snapshot(d,progress_row.snapshot,s,progress_row.snapshot_updated_at,t); end if;
   insert into v4_progress(user_id,case_id,case_version,snapshot,snapshot_updated_at)
   values(actor,case_key,ver,s,t) on conflict(user_id,case_id,case_version) do update set
   snapshot=excluded.snapshot,snapshot_updated_at=greatest(v4_progress.snapshot_updated_at,excluded.snapshot_updated_at),
   revision=v4_progress.revision+1,updated_at=now() returning * into progress_row;
   for e in select value from jsonb_array_elements(p->'events') loop
    insert into v4_events(user_id,case_id,case_version,event_id,event_key,created_at)
    values(actor,case_key,ver,(e->>'event_id')::uuid,e->>'event_key',(e->>'created_at')::bigint)
    on conflict(user_id,case_id,case_version,event_key) do nothing;
   end loop;
   response=jsonb_build_object('status','accepted','progress',to_jsonb(progress_row)-'user_id',
    'events',coalesce((select jsonb_agg(to_jsonb(x)-'user_id' order by event_key) from v4_events x where user_id=actor and case_id=case_key and case_version=ver),'[]'));
  end if;
 end if;
 if code is not null then response=jsonb_build_object('status','rejected','code',code); end if;
 insert into v4_requests(user_id,request_id,action,payload,response) values(actor,req,action,p,response);
 return response;
end $$;
revoke all on function public.pilgrimage_v4(uuid,text,jsonb) from public,anon,authenticated;
grant execute on function public.pilgrimage_v4(uuid,text,jsonb) to service_role;
revoke all on function v4_immutable_version(),v4_set(jsonb,jsonb),v4_snapshot_valid(jsonb,jsonb),v4_merge_snapshot(jsonb,jsonb,jsonb,bigint,bigint) from public,anon,authenticated;
commit;
