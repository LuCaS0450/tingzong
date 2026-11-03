-- Reviewed audiovisual evidence may accompany lyrics and soundtrack associations.
-- Missing release years stay unknown; approval/provenance and overlay gates remain intact.
begin;
alter table v2.release_artworks alter column release_year drop not null;
alter table v2.scenes drop constraint scene_association_kind;
alter table v2.scenes add constraint scene_association_kind
 check(kind in ('mv','album','song_title','lyric','movie_soundtrack'));
alter table v2.scenes drop constraint scenes_reference_kind_check;
alter table v2.scenes add constraint scenes_reference_kind_check
 check(reference_kind in ('mv_frame','release_cover','album_photo','film_frame','lyric_mv_frame','soundtrack_mv_frame','film_preview'));
alter table v2.scenes drop constraint scene_no_fictional_time;
alter table v2.scenes add constraint scene_no_fictional_time check(timestamp_seconds is null or
 kind='mv' or (kind in ('lyric','movie_soundtrack') and reference_kind in ('mv_frame','film_frame','lyric_mv_frame','soundtrack_mv_frame')));
commit;
