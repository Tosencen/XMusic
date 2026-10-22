# XMusic

> **免责声明**
>
> 本项目仅用于个人学习与练习，由一个播放器外壳和一套插件系统组成，不提供在线音源，也不分发受版权保护的媒体。插件作者与用户需自行遵守服务条款及当地法律。

Android 音乐播放器，插件化架构：应用本体只是一个插件运行框架，搜索、播放、歌词、登录等能力全部由第三方 `.qplug` 插件（Rhino JS）提供。

## 功能

- **插件系统**：`.qplug` 在线安装与热更新；内置插件目录——`netease`、`qq`、`kuwo`、`migu`、`kugou`、`soda`
- **浏览**：搜索、歌单 / 专辑 / 歌手详情、扫码与 Cookie 登录
- **播放**：Media3 队列、进度持久化重启恢复、逐字歌词、悬浮歌词（覆盖其它应用上方）
- **本地**：收藏、最近播放、歌单批量缓存下载（进度 / 取消 / 分组），离线播放
- **界面**：Material 3 动态取色、深色模式

## 构建

```bash
./gradlew assembleDebug      # 调试包
./gradlew assembleRelease    # 正式包
```

- minSdk 26 · Kotlin + Jetpack Compose + Media3
- 各插件仓库见 `PluginCatalog.SOURCES`（GitHub Releases 分发）
