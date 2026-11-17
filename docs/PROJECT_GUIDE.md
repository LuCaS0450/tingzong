# 听踪开发入口

## 现行代码

| 任务 | 从这里开始 |
| --- | --- |
| 页面与导航 | [主界面和路由](../app/src/main/java/cn/musicpilgrimage/app/ui/PilgrimageApp.kt)。从路由追到当前页面和它使用的控件。 |
| 世界地图 | [Android 世界页](../app/src/main/java/cn/musicpilgrimage/app/ui/room/SwissWorldWall.kt)、[地点选择和摘要](../app/src/main/java/cn/musicpilgrimage/app/ui/room/WorldPlacePanels.kt)及 [MapLibre 网页](../app/src/main/assets/design/swiss-world/index.html)。 |
| 档案与行程 | [发行](../app/src/main/java/cn/musicpilgrimage/app/ui/room/NativeReleaseArchive.kt)、[作品](../app/src/main/java/cn/musicpilgrimage/app/ui/room/NativeWorkArchive.kt)、[地点](../app/src/main/java/cn/musicpilgrimage/app/ui/room/NativePlaceArchive.kt)和[行程](../app/src/main/java/cn/musicpilgrimage/app/ui/room/NativeTripArchive.kt)档案由 Compose 绘制。行程列表和新增入口见[行程页面](../app/src/main/java/cn/musicpilgrimage/app/ui/room/MusicTripScreens.kt)。 |
| 登录与个人资料 | [账号界面](../app/src/main/java/cn/musicpilgrimage/app/ui/room/MusicAccountScreens.kt)。足迹和收藏见[个人档案](../app/src/main/java/cn/musicpilgrimage/app/ui/room/MusicArchiveScreens.kt)。 |
| 数据与规则 | [数据仓库](../app/src/main/java/cn/musicpilgrimage/app/data/Repositories.kt)、[作品目录与搜索](../app/src/main/java/cn/musicpilgrimage/app/data/MusicCatalogue.kt)、[地点搜索与到访规则](../app/src/main/java/cn/musicpilgrimage/app/data/CatalogSearch.kt)。 |
| 构建与本机预览 | [构建说明](../README.md#本地配置与构建)、[独立预览入口](../scripts/start-standalone-preview.ps1)和[公开素材边界](PUBLIC_ASSETS.md)。 |
| 后端维护 | [应用使用的 Edge Function](../supabase/functions/pilgrimage-v2/index.ts)、[迁移账本](../supabase/v2/)和[云端维护记录](DISK_IO_FIX_20261029.md)。旧初始化和注册联调步骤仅供历史查阅。 |

## 共用交互

- 页面层级、纸面和配色使用 `RoomHeader`、`RoomPaper`、`RoomColors`。主次操作已有 `SwissButton`、`SwissOutlinedButton`、`SwissTextButton`。点击目标可以大于可见底板，单独的图标按钮提供读屏名称。[应用主题](../app/src/main/java/cn/musicpilgrimage/app/ui/Design.kt) · [页面与配色](../app/src/main/java/cn/musicpilgrimage/app/ui/room/MusicRoomDesign.kt) · [按钮](../app/src/main/java/cn/musicpilgrimage/app/ui/room/SwissButtons.kt)
- 下滑面板使用 `AppBottomSheet`，双操作确认使用 `AppConfirmation`。需要输入时通过 `details` 放入输入框，使用 `confirmEnabled` 控制提交。允许重试的异步确认传入 `busy`、等待文案和就地错误。自定义选择列表使用 `AppCenterConfirmDialog`。[面板和确认框](../app/src/main/java/cn/musicpilgrimage/app/ui/AppBottomSheet.kt)
- 社区删除确认后立即关框。确定请求尚未发送的连接失败由数据层自动重试一次，最终仍无法确认时由当前页提示。[删除请求](../app/src/main/java/cn/musicpilgrimage/app/data/ContentDeletionRetry.kt)
- 收藏与行程自动同步。后台状态和排查信息由数据层处理。主动操作的反馈说明结果及必要的下一步，省略缺失的选填信息。动效随减少动态设置调整。[保存反馈](../app/src/main/java/cn/musicpilgrimage/app/ui/AppBottomSheet.kt) · [撤销提示](../app/src/main/java/cn/musicpilgrimage/app/ui/room/MusicArchiveScreens.kt) · [后台资料同步](../app/src/main/java/cn/musicpilgrimage/app/data/PersonalLibrarySync.kt)
- 档案大图统一使用 `AppPhotoViewer`，页面返回时恢复原浏览位置。世界的图钉保留可访问名称，地点名称在选择后的摘要或档案显示。[大图查看器](../app/src/main/java/cn/musicpilgrimage/app/ui/AppPhotoViewer.kt) · [世界地图约定](../AGENTS.md)

## 按改动选验证

按[项目工作约定](../AGENTS.md#开发验证与交付约定)在一批修改完成后集中验证。文档和纯文案看差异，逻辑选相关 JVM 用例，界面实看受影响页面及关键操作。保留核心规则、真实用户流程、数据隔离和复杂缺陷的覆盖。

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*MusicTripTest'
.\gradlew.bat :app:connectedDebugAndroidTest -PtingzongReview=true '-Pandroid.testInstrumentationRunnerArguments.class=cn.musicpilgrimage.app.MusicAccountLayoutTest'
```

- `-PtingzongReview=true` 使用独立的调试安装包和应用数据。Gradle 设备任务结束后会清理测试安装。需要保留已装调试包的数据时，可用 `adb shell am instrument -w -e class 完整类名 应用包名.test/androidx.test.runner.AndroidJUnitRunner` 定向运行匹配的测试包。
- 需要特定界面截图时，支持选择截图的用例可加 `'-Pandroid.testInstrumentationRunnerArguments.captureScreenshots=sign-out-layout-fixture'`，直接运行设备测试时使用 `-e captureScreenshots sign-out-layout-fixture`。
- 无类筛选的 `connectedDebugAndroidTest` 排除标注 `ExtendedDeviceTest` 的场景。整曲、重启、真实服务或本机私有目录验收按场景准备，再用 `class=完整类名#方法名` 或既有分阶段脚本运行。明确选择类时，先看该类是否混有需要额外准备的方法。
- 涉及本机完整素材打包时，使用 `:app:testDebugUnitTest -PtingzongVerifyLocalAssets=true --tests '*RecordedSongTest' --tests '*ArchivePhotographyManifestTest'`。两组默认仍覆盖自包含的曲谱解析和清单合并逻辑。
- Supabase 权限变更可用 `npm --prefix supabase/tests test` 验证该目录的 PGlite 测试，依赖按需安装。函数逻辑从对应 `*_test.ts` 选 Deno 用例。托管服务和真实测试账号按相关流程单独验收。
- 跨模块的大批变更在合并前按风险补回归。正式源码交付包单独解压构建一次。

提交和素材维护按[版本管理约定](VERSION_CONTROL.md)执行。本机 `CODEX_CONTEXT_INDEX.md` 记录阶段状态和验证结果，`DOCUMENT_INDEX.md` 索引现行文档、来源和留档。
