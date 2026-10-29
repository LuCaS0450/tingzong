-- r6 appends verified original MV and formal geographic cover associations.
begin;
do $prerequisites$
begin
 if to_regclass('v2.public_scenes') is null
 or to_regclass('v2.public_locations') is null
 or to_regclass('v2.scene_material_reviews') is null then
  raise exception 'Association publication prerequisites missing';
 end if;
 if has_table_privilege('anon','v2.questions','SELECT')
 or has_table_privilege('authenticated','v2.questions','SELECT') then
  raise exception 'Private question permissions differ';
 end if;
end $prerequisites$;
commit;
