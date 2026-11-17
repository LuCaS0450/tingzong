# 听踪开发入口

| 任务 | 从这里开始 |
| --- | --- |
| 页面与导航 | [主界面和路由](../app/src/main/java/cn/musicpilgrimage/app/ui/PilgrimageApp.kt)。从路由追到当前页面和它使用的控件。 |
| 世界地图 | [Android 世界页](../app/src/main/java/cn/musicpilgrimage/app/ui/room/SwissWorldWall.kt)和[地图网页](../app/src/main/assets/design/swiss-world/index.html)。地点选择、摘要和地图手势从这两个入口核对。 |
| 档案与行程 | [音乐档案](../app/src/main/java/cn/musicpilgrimage/app/ui/room/MusicRoomScreens.kt)、[地点档案](../app/src/main/java/cn/musicpilgrimage/app/ui/room/MusicPlaceScreens.kt)和[行程](../app/src/main/java/cn/musicpilgrimage/app/ui/room/MusicTripScreens.kt)。 |
| 界面共用控件 | [应用主题](../app/src/main/java/cn/musicpilgrimage/app/ui/Design.kt)和[瑞士风格按钮](../app/src/main/java/cn/musicpilgrimage/app/ui/room/SwissButtons.kt)。新增面板或确认框时，先看现行页面所用的控件。 |
| 数据与规则 | [数据仓库](../app/src/main/java/cn/musicpilgrimage/app/data/Repositories.kt)、[作品搜索](../app/src/main/java/cn/musicpilgrimage/app/data/MusicCatalogue.kt)和[到访规则](../app/src/main/java/cn/musicpilgrimage/app/data/CatalogSearch.kt)。 |
| 构建与本机预览 | [构建说明](../README.md#本地配置与构建)、[独立预览入口](../scripts/start-standalone-preview.ps1)和[公开素材边界](PUBLIC_ASSETS.md)。 |
| 后端维护 | [应用使用的接口](../supabase/functions/pilgrimage-v2/index.ts)、[项目工作约定](../AGENTS.md)和[云端维护记录](DISK_IO_FIX_20261029.md)。注册配置变更按当前任务单独核对，V2 初始化和旧注册联调步骤仅供历史查阅。 |

提交和素材维护按[版本管理约定](VERSION_CONTROL.md)执行。本机 `CODEX_CONTEXT_INDEX.md` 记录阶段状态、曾做过的检查和历史来源。
