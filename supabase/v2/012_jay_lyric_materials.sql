-- Incremental Jay lyric append requires the already deployed association filter.
-- Data and its import receipt are supplied in one guarded importer transaction.
begin;
do $prerequisites$
begin
 if to_regclass('v2.public_scenes') is null
 or to_regclass('v2.public_locations') is null
 or to_regclass('v2.scene_material_reviews') is null then
  raise exception '011 association publication must be deployed first';
 end if;
 if has_table_privilege('anon','v2.questions','SELECT')
 or has_table_privilege('authenticated','v2.questions','SELECT') then
  raise exception 'Private question permissions differ';
 end if;
end $prerequisites$;
commit;
