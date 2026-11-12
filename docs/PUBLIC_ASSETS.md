# 公开资源与缺失素材

本清单针对 2026-10-30 建库范围。公开资源通过 `.gitignore` 的具体例外纳入，新增媒体需先补充来源与许可依据。

| 公开资源 | 项目路径 | 来源、许可与处理 |
| --- | --- | --- |
| Source Han Sans CN | `app/src/main/res/font/instrument_sans.otf` | [Adobe Source Han Sans](https://github.com/adobe-fonts/source-han-sans/blob/master/LICENSE.txt)，SIL OFL 1.1；保留原始字体数据及 `design/fonts/LICENSE-sans.txt`。文件名不代表真实字体家族名。 |
| Source Han Serif CN | `app/src/main/res/font/print_serif.otf` | [Adobe Source Han Serif](https://github.com/adobe-fonts/source-han-serif/blob/release/LICENSE.txt)，SIL OFL 1.1；保留 `design/fonts/LICENSE-serif.txt`。 |
| Fusion Pixel | `app/src/main/res/font/fusion_pixel.otf` | [TakWolf Fusion Pixel](https://github.com/TakWolf/fusion-pixel-font)，SIL OFL 1.1；原始署名和上游许可位于 `design/fonts/`。 |
| 详情页 Source Han Sans Web 字体 | `app/src/main/assets/design/swiss-details/fonts/` | 保留该目录 `OFL.txt`；项目使用的 Web 字体形式，沿用原字体许可。 |
| 地点档案照片清单 | `app/src/main/assets/design/swiss-details/place-photos.json` | 逐张列出原始照片、作者、许可和可直接访问的图片。应用优先加载本机素材仓库的 WebP 副本，再尝试来源缩略图。照片经等比缩放和转码，沿用原始许可，署名显示在档案的「资料来源」。 |
| Inter 4.1 | `app/src/main/res/font/inter_variable.ttf`、`app/src/main/assets/design/swiss-details/fonts/inter-variable.woff2` | [Inter 4.1 官方发行包](https://github.com/rsms/inter/releases/tag/v4.1)，SIL OFL 1.1。原生、详情和世界共用同版拉丁字形，保留原始字体数据与 `design/fonts/LICENSE-inter.txt`。 |
| MapLibre GL JS 5.24.0 | `app/src/main/assets/design/swiss-world/vendor/` | [MapLibre GL JS](https://github.com/maplibre/maplibre-gl-js/tree/v5.24.0)，BSD 3-Clause；仅保留发行包 JS、CSS 和原始 `LICENSE.txt`。世界舞台源码可公开；Opus 设计原件、加工纹理、墙面与作品图片仍在本机素材仓库。 |
| Natural Earth 国家或地区轮廓 | `app/src/main/assets/design/swiss-world/countries.json` | 使用 Natural Earth 1:110m 轮廓，中国香港使用 [1:10m Map Units v5.1.2](https://github.com/nvkelso/natural-earth-vector/blob/v5.1.2/geojson/ne_10m_admin_0_map_units.geojson)。按 [Natural Earth 条款](https://www.naturalearthdata.com/about/terms-of-use/) 属于公有领域。用于世界页层级路径和地点档案的地图示意。 |
| OpenCC 简繁字词字典 | `app/src/main/assets/design/map-labels/` | [OpenCC ver.1.1.9](https://github.com/BYVoid/OpenCC/tree/ver.1.1.9/data/dictionary)，Apache 2.0；保留 LICENSE 和原来源说明。 |
| Salamander Grand Piano 采样 | `app/src/main/assets/performance/piano/` | [Alexander Holm，Salamander Grand Piano](https://github.com/sfzinstruments/SalamanderGrandPiano)，[CC BY 3.0](https://creativecommons.org/licenses/by/3.0/)；保留 LICENSE 与 `samples.json` 中的作者和来源。项目取一组力度层，转为单声道、裁剪起始静音与最长时长，并编码为 gzip 容器中的浮点 PCM。这不是商业歌曲录音。 |
| 听踪启动图标 | `app/src/main/design/launcher/tingzong-pin-note.svg`、`app/src/main/res/drawable-xxxhdpi/launcher_{background,foreground}.png` | 项目自有图标，按用户确认的音符与定位点方案矢量重绘。Android 的前景与背景均由该 SVG 渲染。使用 `resvg-py==0.5.0` 运行 `python app/src/main/design/launcher/render.py` 可重新生成两层。 |

字体 manifest 位于 `app/src/main/assets/design/fonts/manifest.json`。完整第三方许可证随对应目录保存，本说明不替换原文。

## 只留在本机的资料

- `content/` 内的历史设计、研究记录、原始素材和来源证据。
- 封面、MV 截图、照片及授权未明确的生成或加工美术，包括详情页 `media/`、音乐空间封面与场景图。
- 地点档案的街区示意图保存在详情页 `media/place-locators/`。从 [OpenFreeMap](https://openfreemap.org/) 获取少量 [OpenMapTiles](https://openmaptiles.org/about/) 矢量瓦片，取用 [OpenStreetMap](https://www.openstreetmap.org/copyright) 的街道、铁路和地物，叠加目录参考点绘制。底图数据许可见 ODbL，署名收在 App「关于」和档案「资料来源」。瓦片版本与图幅保存在本机资料，地图副本随本机预览打包。
- 歌曲包的 `song.json`、背景及完整或片段商业录音；谱面未因 JSON 格式而自动获得公开许可。
- `app/src/debug/assets/` 内的历史演示素材。
- 世界页内置地球加工纹理及经既有 MapTiler 服务取得的高清影像缓存；只公开渲染、调色、手势和衔接源码，不上传影像字节或服务配置。
- 不属于公开维护范围的历史文档、研究脚本与根目录资料。

这些文件保留原位置，并通过独立的本机素材仓库记录历史；不删除、不替换。服务配置、密钥、签名、个人记录和备份不进入任何素材历史。

源码保留已有资源引用、地点介绍和媒体索引，但不包含被排除的资源字节。新克隆可阅读和修改代码；完整画面、演奏曲包及旧预览需自行提供可使用素材，或由原工程所有者在本机恢复，当前不承诺完整功能复现。

## 自有源码

用户选择暂不添加开源许可证。公开可见性与源码授权是不同决定，参阅 [GitHub 仓库许可说明](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/licensing-a-repository)。第三方资源仍按各自许可分发。
