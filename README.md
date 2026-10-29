# 听踪 · Tingzong

从音乐作品探索真实地点，并连接演奏、到访记录与旅行计划的 Android 项目。采用 Kotlin、Jetpack Compose、Room、Supabase、MapLibre、CameraX 和原生音频实现，支持 Android 8.0（API 26）及以上版本。

本仓库用于个人持续开发和公开保存源码。初始基线建立于 2026-10-30，此前设计、研究及实施过程不作为已有 Git 提交历史导入。当前工程仍在持续开发，公开仓库不是发布版或完整素材包。

## 仓库范围

- `app/`：Android 源码、测试、数据库 schema，以及许可明确的字体、地图字典和钢琴采样。
- `supabase/`：后端函数、配置、迁移账本与测试。既有迁移记录不应直接对现有生产环境全部重放。
- `scripts/`：当前预览入口、云端维护工具和版本管理检查。
- `docs/`：[版本管理约定](docs/VERSION_CONTROL.md)、[公开资源与缺失素材说明](docs/PUBLIC_ASSETS.md)、当前云端维护修复说明。

封面、MV 截图、商业歌曲录音、歌曲谱面、授权未明确的美术，以及历史研究和设计资料不随公开仓库上传。源码仍可能引用这些本机路径或外部资源地址；新克隆的工程不能完整还原当前本机的画面和曲库。详见 [PUBLIC_ASSETS.md](docs/PUBLIC_ASSETS.md)。

## 本地配置与构建

需要 Android Studio 自带 JDK、Android SDK 37、NDK 28.2.13676358、CMake 3.22.1。Gradle Wrapper 随仓库提供。SDK 路径通过 Android Studio 或本机 `local.properties` 设置。

将 `services.example.properties` 复制为 `services.local.properties`，仅填写客户端所需公开连接配置。服务端权限密钥、数据库口令和签名文件留在本机，不能写入客户端或提交到 Git。公开副本不提供原项目后台凭据。

```powershell
$env:JAVA_HOME = "$env:ProgramFiles\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug '-Pkotlin.compiler.execution.strategy=in-process' --console=plain
```

独立模拟器入口为 `scripts/start-standalone-preview.ps1`，需已有对应 SDK、AVD 和安装包。缺失的历史预览素材需自行补齐；本轮建库不改变现有应用、云端服务或模拟器状态。

## 开发与版本管理

`main` 保持稳定；较大功能使用临时分支，完成相关验证后合并。每批明确修改验证完成后，自动提交并推送到 GitHub。本机非公开资料使用独立、没有远程的素材仓库记录版本，见 [VERSION_CONTROL.md](docs/VERSION_CONTROL.md)。

首次克隆后启用检查及署名：

```powershell
git config --local core.hooksPath .githooks
git config --local user.name LuCaS0450
git config --local user.email 147964947+LuCaS0450@users.noreply.github.com
```

检查脚本使用 Python 3 标准库。云端维护工具另需 `scripts/requirements-cloud.txt`、本机 PostgreSQL 工具和私有配置，不会在建库时执行云端操作。

## 授权状态

本项目暂未为自有源码选择开源许可证。第三方字体、字典和钢琴采样保留各自许可及署名，不能将整个仓库视为采用同一种许可。见 [公开资源清单](docs/PUBLIC_ASSETS.md)。
