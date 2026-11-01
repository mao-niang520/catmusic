# CatMusic 开发与功能说明

本文面向源码读者，介绍 CatMusic 1.27.0 的结构、主要数据流、构建方式和测试要点。README 中已经有面向使用者的功能简介；这里不包含作者的签名密钥、真实赞赏二维码、私人媒体或本地路径。

## 1. 工程结构

```text
app/src/main/java/com/catmusic/app/
├── MainActivity.kt            单 Activity，全屏 Compose 宿主
├── CatMusicApplication.kt
├── data/                      曲库、导入、歌词解析、偏好与统计
├── playback/                  Media3 服务、控制器、PCM 音频分析
├── online/                    MusicDL 在线页的 Kotlin/Java 网络桥
├── ui/
│   ├── CatMusicApp.kt         导航、全局背景、底部导航
│   ├── CatMusicViewModel.kt
│   ├── components/            玻璃、封面、歌词、播放快照等复用 UI
│   └── screens/               音乐馆、播放器、歌词、在线、设置等
└── util/                      LRC 解析、文件辅助

app/src/main/assets/
├── musicdl/                   MusicDL Lite 搜索/试听页面
├── soundfield/               原创全屏声场页面与 WebGL
└── licenses/                 随 APK 分发的第三方许可证

licenses/                      仓库根目录的第三方许可证全文
docs/                          开发文档、声场说明、合成演示图
```

## 2. 本地曲库与导入

- `MusicRepository` 使用应用私有目录保存音频副本、封面和歌词。导入时会把文件复制进应用，并读取标题、歌手、专辑、内嵌封面等信息。
- 支持混合导入：一次选择音频、LRC/TXT 和图片；按规范化文件名配对，同目录中 `cover` / `folder` / `front` / `封面` 等图片可作为该目录的封面复用。
- 数据索引、歌单、歌词偏移、偏好与统计保存在 SharedPreferences；封面与歌词以文件形式保存。
- `Track` 持有应用内音频文件名和原始来源位置。删除歌曲只删除应用私有副本、封面、歌词缓存和歌单引用，**不会删除设备上的原始音频**。
- 导入和删除都由 ViewModel 调度，UI 只负责进度和结果反馈。

## 3. 播放与音频视觉

- 播放核心是 Media3 `ExoPlayer`，由 `PlaybackService`（`MediaSessionService`）托管；`MediaSession` 向通知栏、锁屏和 Compose UI 暴露控制。
- `PlaybackController` / `CatMusicViewModel` 维护播放队列、随机与循环模式、定时暂停和淡入淡出。
- 音频视觉数据来自解码后的本地 PCM：`AudioReactiveEngine` 把音频分流成八个频段，并生成节拍与电平，发布到 `AudioReactiveSignal`。
- 该分析**不需要麦克风权限**，不录音、不保存音频，也不联网。
- 原生封面页和歌词页都使用封面采样得到的配色；`GlassBackdropView` 以约 60 fps 绘制缓慢缩放的封面色光晕、高光与微尘，供 QWEA0 `LiquidGlassView` 实时采样。

## 4. 全屏声场（1.27.0）

`SoundFieldScreen` 在「音浪」开启时加载本地 WebView：

```text
file:///android_asset/soundfield/index.html?quality=<level>&fps=<rate>
```

- 页面拥有完整歌词、菜单、进度条、校准和导入 UI；WebGL 画布位于所有 DOM 控件下方，因此不需要把 Compose 控件叠到 SurfaceView 上。
- Kotlin 通过 `window.catmusic` 下发歌词 JSON、曲目信息、封面缩略图、播放位置、八频段、安全区与陀螺仪状态。
- 页面通过 `CatMusicBridge` 回传 `ready / close / toggle / seek / setOffset / importLyrics / toggleAudioMode / setGyroEnabled`。
- 返回键先询问页面是否关闭菜单/校准，再退出声场；12 秒内没有 `ready` 会回退到原生歌词页。
- WebView 只加载 `file:///android_asset/soundfield/`，`blockNetworkLoads = true`，不申请录音权限。

详细渲染、手势、持久化键和质量档位见 [SOUNDFIELD.md](SOUNDFIELD.md)。

### 声场数据流

```text
Media3 ExoPlayer
   │ decoded PCM
   ▼
AudioReactiveEngine ── eight bands / beat / level ──► SoundFieldScreen ──► window.catmusic.audio
   │                                                                          │
   └── playback position / duration ─────────────────────────────────────────►│
                                                                              ▼
                                                        field.js WebGL (Star Tide / Echo)
```

歌词数据流：

```text
LRC file → LrcParser → SoundFieldScreen.lyricsJson() → window.catmusic.lyrics()
   → app.js timed / plain / empty view
   → tap line or offset change → CatMusicBridge.seek / setOffset → Media3 / Track
```

## 5. MusicDL 在线模块

- `app/src/main/assets/musicdl/www` 是离线打包的 MusicDL Lite 页面。WebView 打开该本地页，页面只访问公开搜索/解析接口。
- `MusicBridge` 与 `MusicApi` 负责 Kotlin/Java 网络请求、来源适配、音频代理与下载；页面通过 `Native` 回调结果。
- `OnlineMusicScreen` 托管页面、下载保存面板、播放器状态和返回处理。
- 九个来源：网易云、QQ、咪咕、酷我、千千、酷狗、JOOX、5sing、Jamendo。
- 搜索阶段会尝试最高可播放音质并尽量解析真实文件大小；无法得到可播放流的歌曲会按 musicdl 的行为过滤。
- 保存到播放器时把音频、封面和可获得的歌词写入应用私有目录；保存到系统「音乐 / CatMusic」时使用 `DownloadManager`，不改动设备原文件。
- 只发送搜索关键词与来源标识；不携带本地曲库、封面、歌词或统计。
- 在线接口不是官方开放 API，平台改版可能让某个来源失效；本项目不托管或再分发音频。

## 6. 构建

环境：

- JDK 17 或 21
- Android SDK Platform 36、Build Tools 36
- Gradle Wrapper（随仓库提供）

```bash
./gradlew :app:assembleDebug
./gradlew :app:lintRelease
./gradlew :app:assembleRelease
```

Windows 使用 `gradlew.bat`。`local.properties` 中的 `sdk.dir` 或环境变量
`ANDROID_HOME` / `ANDROID_SDK_ROOT` 必须指向有效 SDK。

Release 签名需要本地 `keystore.properties`，格式：

```properties
storeFile=/path/to/your-release.jks
storePassword=********
keyAlias=your-alias
keyPassword=********
```

公开仓库不包含作者的 keystore、密码或 APK。不要提交这些文件。

## 7. 测试要点

- 冷启动、导入音频/歌词/封面、删除歌曲后原文件仍在。
- 播放/暂停、切歌、后台、通知栏、定时暂停、淡入淡出。
- 进入「在线」或切换标签时本地播放不中断；在线试听真正开始时本地才淡出暂停。
- 音浪页：首次进入、菜单开关、星潮/回声切换、拖动旋转、双指缩放、歌词跳转、校准 ±0.1 秒、行数调整、陀螺仪、横竖屏、系统返回、重启后效果与视角记忆。
- WebGL 不可用时能否回退到原生歌词页。
- 在线页搜索多个来源、默认音质、真实体积、按音质保存与取消保存。

## 8. 版本与许可

- 1.27.0：移除旧实验性音浪页面，启用完全原创的「星潮 / 回声」声场；新增行数、陀螺仪、视角与效果记忆，并提高默认内部渲染像素比。
- 1.25–1.26：在线音源适配、音质与体积解析、粒子球、滚动与返回键修复。
- 1.23–1.24：QWEA0 液态玻璃、动态封面背景、MusicDL 合并。
- 更早版本：本地曲库、Media3 后台、歌单、统计、LRC 等功能逐步加入。

源码公开规则见根目录 [README](../README.md)、[NOTICE](../NOTICE.md) 与 [LICENSE](../LICENSE)。原创代码采用 PolyForm Noncommercial 1.0.0；不是 OSI 开源许可。
