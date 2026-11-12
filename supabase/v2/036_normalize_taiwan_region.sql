begin;

update v2.locations
set country = '中国台湾'
where btrim(country) in (
    '台湾', '台灣', '臺灣', '台湾省', '台灣省', '臺灣省',
    '中國台灣', '中國臺灣', '中华民国', '中華民國'
);

update v2.locations
set name = '中国台湾北海岸（淡水区域参考）'
where id = 'tamsui-north-coast-reference' and name = '台湾北海岸（淡水区域参考）';

update v2.locations
set address = regexp_replace(address, '臺灣$', '中国台湾')
where id = 'lukang-town-playlist-reference' and address like '%, 臺灣';

update v2.scenes
set note = case id
    when 'playlist-20261026-9c9bb476593321d8' then replace(note, '另在台湾搭建', '另在中国台湾搭建')
    when 'playlist-20261026-c18c5b8573d2d2b4' then replace(note, '在台湾阳明山', '在中国台湾阳明山')
    when 'fish-leong-nuannuan-station' then replace(note, '确认台湾基隆', '确认中国台湾基隆')
end
where (id = 'playlist-20261026-9c9bb476593321d8' and note like '%另在台湾搭建%')
   or (id = 'playlist-20261026-c18c5b8573d2d2b4' and note like '%在台湾阳明山%')
   or (id = 'fish-leong-nuannuan-station' and note like '官方MV实看确认台湾基隆%');

commit;
