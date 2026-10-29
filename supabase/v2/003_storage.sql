-- Only trusted Edge Functions write/read user media; clients have no object policies.
insert into storage.buckets(id,name,public,file_size_limit,allowed_mime_types)
values('user-media-v2','user-media-v2',false,409600,array['image/jpeg'])
on conflict(id) do update set public=false,file_size_limit=409600,allowed_mime_types=array['image/jpeg'];
insert into storage.buckets(id,name,public,file_size_limit,allowed_mime_types)
values('reference-media','reference-media',true,409600,array['image/jpeg','image/png','image/webp'])
on conflict(id) do nothing;
