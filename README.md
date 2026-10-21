# XMusic

Android 在线音乐播放器：插件化多音源，Material 3 动态取色。

## 功能

- **音源插件**：`.qplug`（Rhino JS）在线安装与热更新，内置 6 源目录——网易云音乐、QQ 音乐、酷我、咪咕、酷狗、汽水音乐
- **在线浏览**：搜索、歌单 / 专辑 / 歌手详情、扫码与 Cookie 登录
- **播放**：Media3 队列、进度持久化重启恢复、逐字歌词、悬浮歌词（覆盖在其它应用上方）
- **本地**：收藏、最近播放、歌单批量缓存下载（进度 / 取消 / 分组），离线直接播放
- **界面**：Material 3 动态取色、深色模式

## 构建

```bash
./gradlew assembleDebug      # 调试包
./gradlew assembleRelease    # 正式包
```

- minSdk 26 · Kotlin + Jetpack Compose + Media3
- 音源插件仓库见 `PluginCatalog.SOURCES`（GitHub Releases，经 gh-proxy 镜像分发）
