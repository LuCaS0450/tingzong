-- Append the original MV's supported street-area reference; retain all history.
do $$ begin
  if to_regclass('v2.material_imports') is null or to_regclass('v2.release_artworks') is null then
    raise exception 'Existing incremental material schema is required';
  end if;
  if has_table_privilege('anon','v2.questions','SELECT') then
    raise exception 'Private challenge answers must not be publicly readable';
  end if;
end $$;
