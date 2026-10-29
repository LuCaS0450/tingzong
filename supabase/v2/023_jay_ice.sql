-- Original 2007 MV ice-rink reference; append to the existing shared venue.
do $$ begin
  if to_regclass('v2.material_imports') is null or to_regclass('v2.release_artworks') is null then
    raise exception 'Existing incremental material schema is required';
  end if;
  if has_table_privilege('anon','v2.questions','SELECT') then
    raise exception 'Private challenge answers must not be publicly readable';
  end if;
end $$;
