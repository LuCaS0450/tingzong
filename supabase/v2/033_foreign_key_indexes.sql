-- Optimize foreign key and query performance to eliminate full table scans.
begin;
set local lock_timeout='10s';
set local statement_timeout='60s';

-- Scenes table core relational lookups and RLS policy joins
create index if not exists idx_scenes_location_id on v2.scenes (location_id);
create index if not exists idx_scenes_work_id on v2.scenes (work_id);
create index if not exists idx_scenes_artist_id on v2.scenes (artist_id);

-- Work relations
create index if not exists idx_work_artists_artist_id on v2.work_artists (artist_id);
create index if not exists idx_work_display_artworks_artwork_id on v2.work_display_artworks (artwork_id);

-- Content lookups by scene and work
create index if not exists idx_content_scene_id on v2.content (scene_id) where scene_id is not null;
create index if not exists idx_content_work_id on v2.content (work_id) where work_id is not null;

-- Likes & Favorites foreign keys
create index if not exists idx_likes_content_id on v2.likes (content_id);
create index if not exists idx_favorites_location_id on v2.favorites (location_id);

-- Submissions
create index if not exists idx_submissions_location_id on v2.submissions (location_id) where location_id is not null;
create index if not exists idx_submissions_user_id on v2.submissions (user_id);

-- Scene reviews
create index if not exists idx_scene_material_reviews_scene_id on v2.scene_material_reviews (scene_id);
create index if not exists idx_scene_material_reviews_import_id on v2.scene_material_reviews (import_id);

-- Questions & Cases
create index if not exists idx_questions_reference_scene_id on v2.questions (reference_scene_id) where reference_scene_id is not null;
create index if not exists idx_questions_answer_location_id on v2.questions (answer_location_id) where answer_location_id is not null;

-- Locations submitted_by
create index if not exists idx_locations_submitted_by on v2.locations (submitted_by) where submitted_by is not null;

commit;
