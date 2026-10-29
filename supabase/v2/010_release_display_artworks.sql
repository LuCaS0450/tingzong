-- Display artwork identifies a release; it does not assert a filming location.
-- This migration contains no media, approval decisions, or question changes.
begin;
set local search_path=v2,extensions,public;

create table release_artworks (
 id text primary key,
 release_title text not null check(length(trim(release_title))>0),
 release_year integer not null check(release_year between 1900 and 2200),
 release_type text not null check(release_type in ('single','album','ep')),
 source_url text not null check(source_url like 'https://%'),
 image_url text, thumbnail_url text, image_credit text not null default '',
 image_status text not null default 'missing' check(image_status in ('missing','pending','approved','rejected')),
 image_width integer check(image_width between 1 and 1280),
 image_height integer check(image_height between 1 and 1280),
 image_provenance jsonb not null default '{}',
 constraint release_artwork_approval check(image_status<>'approved' or coalesce(
  (image_url like 'https://%' and thumbnail_url like 'https://%' and length(trim(image_credit))>0
   and image_width between 1 and 1280 and image_height between 1 and 1280
   and image_provenance->>'sha256' ~ '^[0-9a-f]{64}$'
   and (image_provenance->>'byteSize')::integer between 1 and 409600
   and image_provenance->'userReview'->>'decision'='approved'
   and length(image_provenance->>'originalSourceUrl')>0
   and length(image_provenance->>'acquisitionUrl')>0
   and length(image_provenance->>'rightsHolder')>0
   and length(image_provenance->>'useBasis')>0),false))
);
create table work_display_artworks (
 work_id text not null, artist_id text not null, artwork_id text not null references release_artworks,
 primary key(work_id,artist_id),
 foreign key(work_id,artist_id) references work_artists(work_id,artist_id)
);
alter table release_artworks enable row level security;
alter table work_display_artworks enable row level security;
revoke all on release_artworks,work_display_artworks from public,anon,authenticated;
grant all on release_artworks,work_display_artworks to service_role;

create or replace function catalog() returns jsonb language sql stable security definer set search_path=v2,extensions,public as $$
 select jsonb_build_object('schemaVersion',2,'artists',coalesce((select jsonb_agg(to_jsonb(a) order by a.id) from artists a),'[]'::jsonb),
 'places',coalesce((select jsonb_agg(jsonb_build_object(
 'id',l.id,'name',l.name,'city',l.city,'country',l.country,'address',l.address,'latitude',l.latitude,'longitude',l.longitude,
 'radiusMeters',l.checkin_radius_m,'precision',l.precision,'description',l.description,'coordinateSource',l.coordinate_source,'verified',l.verified,
 'scenes',coalesce((select jsonb_agg(jsonb_build_object('id',s.id,'artistId',s.artist_id,'artistName',a.name,'work',w.title,'kind',s.kind,
 'timestampSeconds',s.timestamp_seconds,'sourceUrl',s.source_url,'imageUrl',s.image_url,'imageCredit',s.image_credit,'imageStatus',s.image_status,'note',s.note,'referenceKind',s.reference_kind,'releaseYear',s.release_year,'releaseType',s.release_type,'associationPrecision',s.association_precision,'arrivalAnchorNote',s.arrival_anchor_note,'associationExcerpt',s.association_excerpt,'canonicalWorkUrl',s.canonical_work_url,'verificationStatus',s.verification_status,'overlayEligible',s.overlay_eligible,'imageWidth',s.image_width,'imageHeight',s.image_height,'thumbnailUrl',s.thumbnail_url,'aliases',s.aliases,'imageProvenance',s.image_provenance,
 'displayArtwork',case when ra.id is null then null else jsonb_build_object(
  'id',ra.id,'releaseTitle',ra.release_title,'releaseYear',ra.release_year,'releaseType',ra.release_type,
  'sourceUrl',ra.source_url,'imageUrl',ra.image_url,'thumbnailUrl',ra.thumbnail_url,'imageCredit',ra.image_credit,
  'imageStatus',ra.image_status,'imageWidth',ra.image_width,'imageHeight',ra.image_height,'imageProvenance',ra.image_provenance) end
 ) order by s.id) from scenes s join artists a on a.id=s.artist_id join works w on w.id=s.work_id
 left join work_display_artworks da on da.work_id=s.work_id and da.artist_id=s.artist_id
 left join release_artworks ra on ra.id=da.artwork_id where s.location_id=l.id),'[]'::jsonb)) order by l.id)
 from locations l where verified),'[]'::jsonb))
$$;
commit;
