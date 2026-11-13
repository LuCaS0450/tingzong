-- P0 fixes from 2026-11-13 cloud media correctness audit:
-- 1) Replace 5 mismatched/compilation covers with official debut album or original single covers and release_year.
-- 2) Complete missing work_display_artworks pair ('tong-zhou-zhi-qing', 'jacky-cheung').
-- 3) Normalize Taiwan / Hong Kong location country fields and align Y.M.C.A. scene release_year to 1978.
begin;
set local lock_timeout = '5s';

insert into v2.release_artworks (
  id, release_title, release_year, release_type, source_url, image_url, thumbnail_url,
  image_credit, image_status, image_width, image_height, image_provenance
) values (
  'repaired-release-apple-714601581', 'Back To Bedlam', 2004, 'album', 'https://music.apple.com/us/album/back-to-bedlam/714601581?uo=4', 'https://bbzpffucjicfnpfzeoqq.supabase.co/storage/v1/object/public/reference-media/audit-fixes-20261113/display/fc0f33d0c0f828ee-repaired-release-apple-714601581.jpg', 'https://bbzpffucjicfnpfzeoqq.supabase.co/storage/v1/object/public/reference-media/audit-fixes-20261113/display/fc0f33d0c0f828ee-repaired-release-apple-714601581.jpg', 'Apple Music · James Blunt', 'approved', 1280, 1280, '{"sha256": "fc0f33d0c0f828ee7f07a360792ed74ac2047f59c823629ae43e4770477ea854", "byteSize": 383352, "width": 1280, "height": 1280, "originalSha256": "478d61c0c5657c37d815ccd8c46d304aa89419107f087215202d4b8259f0fecf", "originalDimensions": [1280, 1280], "transformation": "原图与展示图一致，仅重新编码为不超过400KiB的JPEG以符合云端展示约束；保留完整构图。", "importId": "audit-fixes-20261113-p0", "browsingKey": "work:you''re beautiful", "rightsHolder": "℗ 2013 This compilation 2013 Atlantic Recording Corporation for the United States and WEA International Inc. for the world outside of the United States. A Warner Music Group Company", "sourceVersion": "Back To Bedlam (2004-10-11)", "originalSourceUrl": "https://music.apple.com/us/album/back-to-bedlam/714601581?uo=4", "acquisitionUrl": "https://is1-ssl.mzstatic.com/image/thumb/Music124/v4/32/bf/13/32bf1302-fbfe-945a-44bc-c0aeaee12711/075678375224.jpg/1280x1280bb.jpg", "useBasis": "用户于2026-11-13云端素材P0审计中明确确认替换为官方初版专辑/单曲封面，用于非商业原作对照展示。", "restrictions": "保留完整封面与来源署名；非开放许可素材。", "userReview": {"decision": "approved", "date": "2026-11-13", "sha256": "fc0f33d0c0f828ee7f07a360792ed74ac2047f59c823629ae43e4770477ea854", "scope": "cloud-media-audit-p0-replacement"}, "originalStorage": {"url": "https://bbzpffucjicfnpfzeoqq.supabase.co/storage/v1/object/authenticated/reviewed-originals-private/audit-fixes-20261113/original/478d61c0c5657c37-repaired-release-apple-714601581.jpg", "bucket": "reviewed-originals-private", "object": "audit-fixes-20261113/original/478d61c0c5657c37-repaired-release-apple-714601581.jpg", "sha256": "478d61c0c5657c37d815ccd8c46d304aa89419107f087215202d4b8259f0fecf", "byteSize": 429711}}'::jsonb
)
on conflict (id) do update set
  release_title = excluded.release_title,
  release_year = excluded.release_year,
  release_type = excluded.release_type,
  source_url = excluded.source_url,
  image_url = excluded.image_url,
  thumbnail_url = excluded.thumbnail_url,
  image_credit = excluded.image_credit,
  image_status = excluded.image_status,
  image_width = excluded.image_width,
  image_height = excluded.image_height,
  image_provenance = excluded.image_provenance;
insert into v2.work_display_artworks (work_id, artist_id, artwork_id)
values ('youre-beautiful', 'james-blunt', 'repaired-release-apple-714601581')
on conflict (work_id, artist_id) do update set artwork_id = excluded.artwork_id;

insert into v2.release_artworks (
  id, release_title, release_year, release_type, source_url, image_url, thumbnail_url,
  image_credit, image_status, image_width, image_height, image_provenance
) values (
  'repaired-release-apple-541856819', 'The Moment', 2003, 'album', 'https://music.apple.com/hk/album/the-moment/541856819?uo=4', 'https://bbzpffucjicfnpfzeoqq.supabase.co/storage/v1/object/public/reference-media/audit-fixes-20261113/display/6510cc22547aba99-repaired-release-apple-541856819.jpg', 'https://bbzpffucjicfnpfzeoqq.supabase.co/storage/v1/object/public/reference-media/audit-fixes-20261113/display/6510cc22547aba99-repaired-release-apple-541856819.jpg', 'Apple Music · 孙燕姿', 'approved', 1280, 1151, '{"sha256": "6510cc22547aba999c258bd613cdea2c9aa3c148676fafdf072cd135ea7dcab5", "byteSize": 233356, "width": 1280, "height": 1151, "originalSha256": "6510cc22547aba999c258bd613cdea2c9aa3c148676fafdf072cd135ea7dcab5", "originalDimensions": [1280, 1151], "transformation": "完整原画幅直接入库，无裁切、缩放或重压缩。", "importId": "audit-fixes-20261113-p0", "browsingKey": "work:遇见", "rightsHolder": "℗ 2003 WARNER MUSIC TAIWAN LIMITED", "sourceVersion": "The Moment (2003-08-19)", "originalSourceUrl": "https://music.apple.com/hk/album/the-moment/541856819?uo=4", "acquisitionUrl": "https://is1-ssl.mzstatic.com/image/thumb/Music124/v4/8a/91/d7/8a91d731-cdb1-01b5-17a9-c88cf66d6e01/5050466855725.jpg/1280x1280bb.jpg", "useBasis": "用户于2026-11-13云端素材P0审计中明确确认替换为官方初版专辑/单曲封面，用于非商业原作对照展示。", "restrictions": "保留完整封面与来源署名；非开放许可素材。", "userReview": {"decision": "approved", "date": "2026-11-13", "sha256": "6510cc22547aba999c258bd613cdea2c9aa3c148676fafdf072cd135ea7dcab5", "scope": "cloud-media-audit-p0-replacement"}, "originalStorage": {"url": "https://bbzpffucjicfnpfzeoqq.supabase.co/storage/v1/object/authenticated/reviewed-originals-private/audit-fixes-20261113/original/6510cc22547aba99-repaired-release-apple-541856819.jpg", "bucket": "reviewed-originals-private", "object": "audit-fixes-20261113/original/6510cc22547aba99-repaired-release-apple-541856819.jpg", "sha256": "6510cc22547aba999c258bd613cdea2c9aa3c148676fafdf072cd135ea7dcab5", "byteSize": 233356}}'::jsonb
)
on conflict (id) do update set
  release_title = excluded.release_title,
  release_year = excluded.release_year,
  release_type = excluded.release_type,
  source_url = excluded.source_url,
  image_url = excluded.image_url,
  thumbnail_url = excluded.thumbnail_url,
  image_credit = excluded.image_credit,
  image_status = excluded.image_status,
  image_width = excluded.image_width,
  image_height = excluded.image_height,
  image_provenance = excluded.image_provenance;
insert into v2.work_display_artworks (work_id, artist_id, artwork_id)
values ('meet-yu-jian', 'sun-yanzi', 'repaired-release-apple-541856819')
on conflict (work_id, artist_id) do update set artwork_id = excluded.artwork_id;

insert into v2.release_artworks (
  id, release_title, release_year, release_type, source_url, image_url, thumbnail_url,
  image_credit, image_status, image_width, image_height, image_provenance
) values (
  'repaired-release-apple-1445126987', '同舟之情 ("家是香港"運動主題曲) - Single', 2013, 'single', 'https://music.apple.com/hk/album/%E5%90%8C%E8%88%9F%E4%B9%8B%E6%83%85-%E5%AE%B6%E6%98%AF%E9%A6%99%E6%B8%AF-%E9%81%8B%E5%8B%95%E4%B8%BB%E9%A1%8C%E6%9B%B2-single/1445126987?uo=4', 'https://bbzpffucjicfnpfzeoqq.supabase.co/storage/v1/object/public/reference-media/audit-fixes-20261113/display/48040859b6f7f996-repaired-release-apple-1445126987.jpg', 'https://bbzpffucjicfnpfzeoqq.supabase.co/storage/v1/object/public/reference-media/audit-fixes-20261113/display/48040859b6f7f996-repaired-release-apple-1445126987.jpg', 'Apple Music · 张学友 & 陈奕迅', 'approved', 1280, 1280, '{"sha256": "48040859b6f7f9968632d148386cf7d8c9fd395c128f91b4ba1331cefbc8048a", "byteSize": 305855, "width": 1280, "height": 1280, "originalSha256": "48040859b6f7f9968632d148386cf7d8c9fd395c128f91b4ba1331cefbc8048a", "originalDimensions": [1280, 1280], "transformation": "完整原画幅直接入库，无裁切、缩放或重压缩。", "importId": "audit-fixes-20261113-p0", "browsingKey": "work:同舟之情", "rightsHolder": "This Compilation ℗ 2013 Ioi Limited  / EAS Music Ltd", "sourceVersion": "同舟之情 (\"家是香港\"運動主題曲) - Single (2013-01-01)", "originalSourceUrl": "https://music.apple.com/hk/album/%E5%90%8C%E8%88%9F%E4%B9%8B%E6%83%85-%E5%AE%B6%E6%98%AF%E9%A6%99%E6%B8%AF-%E9%81%8B%E5%8B%95%E4%B8%BB%E9%A1%8C%E6%9B%B2-single/1445126987?uo=4", "acquisitionUrl": "https://is1-ssl.mzstatic.com/image/thumb/Music118/v4/75/70/8f/75708ff1-3c73-8e4e-624f-68155e4cf508/00602488962803.rgb.jpg/1280x1280bb.jpg", "useBasis": "用户于2026-11-13云端素材P0审计中明确确认替换为官方初版专辑/单曲封面，用于非商业原作对照展示。", "restrictions": "保留完整封面与来源署名；非开放许可素材。", "userReview": {"decision": "approved", "date": "2026-11-13", "sha256": "48040859b6f7f9968632d148386cf7d8c9fd395c128f91b4ba1331cefbc8048a", "scope": "cloud-media-audit-p0-replacement"}, "originalStorage": {"url": "https://bbzpffucjicfnpfzeoqq.supabase.co/storage/v1/object/authenticated/reviewed-originals-private/audit-fixes-20261113/original/48040859b6f7f996-repaired-release-apple-1445126987.jpg", "bucket": "reviewed-originals-private", "object": "audit-fixes-20261113/original/48040859b6f7f996-repaired-release-apple-1445126987.jpg", "sha256": "48040859b6f7f9968632d148386cf7d8c9fd395c128f91b4ba1331cefbc8048a", "byteSize": 305855}}'::jsonb
)
on conflict (id) do update set
  release_title = excluded.release_title,
  release_year = excluded.release_year,
  release_type = excluded.release_type,
  source_url = excluded.source_url,
  image_url = excluded.image_url,
  thumbnail_url = excluded.thumbnail_url,
  image_credit = excluded.image_credit,
  image_status = excluded.image_status,
  image_width = excluded.image_width,
  image_height = excluded.image_height,
  image_provenance = excluded.image_provenance;
insert into v2.work_display_artworks (work_id, artist_id, artwork_id)
values ('tong-zhou-zhi-qing', 'eason', 'repaired-release-apple-1445126987')
on conflict (work_id, artist_id) do update set artwork_id = excluded.artwork_id;
insert into v2.work_display_artworks (work_id, artist_id, artwork_id)
values ('tong-zhou-zhi-qing', 'jacky-cheung', 'repaired-release-apple-1445126987')
on conflict (work_id, artist_id) do update set artwork_id = excluded.artwork_id;

insert into v2.release_artworks (
  id, release_title, release_year, release_type, source_url, image_url, thumbnail_url,
  image_credit, image_status, image_width, image_height, image_provenance
) values (
  'repaired-release-apple-202117886', 'Last Christmas - Single', 1984, 'single', 'https://music.apple.com/gb/album/last-christmas-single/202117886?uo=4', 'https://bbzpffucjicfnpfzeoqq.supabase.co/storage/v1/object/public/reference-media/audit-fixes-20261113/display/4b5a66b58c7ffdd7-repaired-release-apple-202117886.jpg', 'https://bbzpffucjicfnpfzeoqq.supabase.co/storage/v1/object/public/reference-media/audit-fixes-20261113/display/4b5a66b58c7ffdd7-repaired-release-apple-202117886.jpg', 'Apple Music · Wham!', 'approved', 1280, 1280, '{"sha256": "4b5a66b58c7ffdd70d89b3b28c5f7619280af2763544b7c9edf2439fe233b524", "byteSize": 391040, "width": 1280, "height": 1280, "originalSha256": "1a6884911428d7e777101d7886d41d90197966320b80fd068b31feddfd2047b9", "originalDimensions": [1280, 1280], "transformation": "原图与展示图一致，仅重新编码为不超过400KiB的JPEG以符合云端展示约束；保留完整构图。", "importId": "audit-fixes-20261113-p0", "browsingKey": "work:last christmas", "rightsHolder": "℗ 1984 Sony Music Entertainment (UK) Ltd.", "sourceVersion": "Last Christmas - Single (1984-01-01)", "originalSourceUrl": "https://music.apple.com/gb/album/last-christmas-single/202117886?uo=4", "acquisitionUrl": "https://is1-ssl.mzstatic.com/image/thumb/Music114/v4/eb/4a/f9/eb4af984-fc68-56ab-7b89-1d070c04e1dd/5099765318524.jpg/1280x1280bb.jpg", "useBasis": "用户于2026-11-13云端素材P0审计中明确确认替换为官方初版专辑/单曲封面，用于非商业原作对照展示。", "restrictions": "保留完整封面与来源署名；非开放许可素材。", "userReview": {"decision": "approved", "date": "2026-11-13", "sha256": "4b5a66b58c7ffdd70d89b3b28c5f7619280af2763544b7c9edf2439fe233b524", "scope": "cloud-media-audit-p0-replacement"}, "originalStorage": {"url": "https://bbzpffucjicfnpfzeoqq.supabase.co/storage/v1/object/authenticated/reviewed-originals-private/audit-fixes-20261113/original/1a6884911428d7e7-repaired-release-apple-202117886.jpg", "bucket": "reviewed-originals-private", "object": "audit-fixes-20261113/original/1a6884911428d7e7-repaired-release-apple-202117886.jpg", "sha256": "1a6884911428d7e777101d7886d41d90197966320b80fd068b31feddfd2047b9", "byteSize": 503361}}'::jsonb
)
on conflict (id) do update set
  release_title = excluded.release_title,
  release_year = excluded.release_year,
  release_type = excluded.release_type,
  source_url = excluded.source_url,
  image_url = excluded.image_url,
  thumbnail_url = excluded.thumbnail_url,
  image_credit = excluded.image_credit,
  image_status = excluded.image_status,
  image_width = excluded.image_width,
  image_height = excluded.image_height,
  image_provenance = excluded.image_provenance;
insert into v2.work_display_artworks (work_id, artist_id, artwork_id)
values ('reviewed-work-8c17f50acd769b5d5b20', 'wham', 'repaired-release-apple-202117886')
on conflict (work_id, artist_id) do update set artwork_id = excluded.artwork_id;

insert into v2.release_artworks (
  id, release_title, release_year, release_type, source_url, image_url, thumbnail_url,
  image_credit, image_status, image_width, image_height, image_provenance
) values (
  'repaired-release-apple-1443926621', 'Cruisin''', 1978, 'album', 'https://music.apple.com/us/album/cruisin/1443926621?uo=4', 'https://bbzpffucjicfnpfzeoqq.supabase.co/storage/v1/object/public/reference-media/audit-fixes-20261113/display/de50c13ae4ce7168-repaired-release-apple-1443926621.jpg', 'https://bbzpffucjicfnpfzeoqq.supabase.co/storage/v1/object/public/reference-media/audit-fixes-20261113/display/de50c13ae4ce7168-repaired-release-apple-1443926621.jpg', 'Apple Music · Village People', 'approved', 1280, 1280, '{"sha256": "de50c13ae4ce7168a3810b8c390e9f3e2d09dd43ef19a82cee485a556b33c2aa", "byteSize": 374320, "width": 1280, "height": 1280, "originalSha256": "ff936a78b6d3a4f57b6f80e3e8ea1a804d0e3fabc4f3688b4bc519c5bca4a7c5", "originalDimensions": [1280, 1280], "transformation": "原图与展示图一致，仅重新编码为不超过400KiB的JPEG以符合云端展示约束；保留完整构图。", "importId": "audit-fixes-20261113-p0", "browsingKey": "playlist-art-576bca17cf7486da", "rightsHolder": "℗ 2014 The Island Def Jam Music Group", "sourceVersion": "Cruisin'' (1978-09-25)", "originalSourceUrl": "https://music.apple.com/us/album/cruisin/1443926621?uo=4", "acquisitionUrl": "https://is1-ssl.mzstatic.com/image/thumb/Music118/v4/a7/70/df/a770dff5-0b2c-b260-0c10-eea7997d36eb/00731453217126.rgb.jpg/1280x1280bb.jpg", "useBasis": "用户于2026-11-13云端素材P0审计中明确确认替换为官方初版专辑/单曲封面，用于非商业原作对照展示。", "restrictions": "保留完整封面与来源署名；非开放许可素材。", "userReview": {"decision": "approved", "date": "2026-11-13", "sha256": "de50c13ae4ce7168a3810b8c390e9f3e2d09dd43ef19a82cee485a556b33c2aa", "scope": "cloud-media-audit-p0-replacement"}, "originalStorage": {"url": "https://bbzpffucjicfnpfzeoqq.supabase.co/storage/v1/object/authenticated/reviewed-originals-private/audit-fixes-20261113/original/ff936a78b6d3a4f5-repaired-release-apple-1443926621.jpg", "bucket": "reviewed-originals-private", "object": "audit-fixes-20261113/original/ff936a78b6d3a4f5-repaired-release-apple-1443926621.jpg", "sha256": "ff936a78b6d3a4f57b6f80e3e8ea1a804d0e3fabc4f3688b4bc519c5bca4a7c5", "byteSize": 568245}}'::jsonb
)
on conflict (id) do update set
  release_title = excluded.release_title,
  release_year = excluded.release_year,
  release_type = excluded.release_type,
  source_url = excluded.source_url,
  image_url = excluded.image_url,
  thumbnail_url = excluded.thumbnail_url,
  image_credit = excluded.image_credit,
  image_status = excluded.image_status,
  image_width = excluded.image_width,
  image_height = excluded.image_height,
  image_provenance = excluded.image_provenance;
insert into v2.work_display_artworks (work_id, artist_id, artwork_id)
values ('work-11f884dc9254fb0c', 'village-people', 'repaired-release-apple-1443926621')
on conflict (work_id, artist_id) do update set artwork_id = excluded.artwork_id;

delete from v2.release_artworks where id in ('repaired-release-qq-003lVUEk1ZwGpd', 'repaired-release-apple-578524971', 'repaired-release-qq-0011X6CP4Q1gQV', 'repaired-release-apple-1485908119', 'playlist-art-576bca17cf7486da');

update v2.scenes set release_year = 1978 where id in ('playlist-20261105-3f454d6f15271968', 'playlist-20261105-40b877b04ea141b2', 'playlist-20261105-765b82a0bd73aa60');

update v2.locations set country = '中国台湾'
where id in ('sanzhi-district-unconditional-mv-reference', 'taoyuan-martyrs-shrine-playlist-reference');

update v2.locations set country = '中国香港'
where id = 'lion-rock-hong-kong';

commit;
