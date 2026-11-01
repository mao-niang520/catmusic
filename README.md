# CatMusic

**一只爱听歌的猫。** 一个本地优先的 Android 音乐播放器。

Kotlin · Jetpack Compose · Media3 · QWEA0 Liquid Glass · MusicDL · WebGL

<p align="center">
  <img src="docs/images/soundfield.jpg" width="390" alt="CatMusic 原创全屏星潮声场">
  <img src="docs/images/soundfield-landscape.jpg" width="640" alt="CatMusic 横屏声场与歌词">
</p>
<p align="center"><sub>原创 WebGL 声场演示 · 合成封面与演示歌词，非真实专辑或歌词</sub></p>

## 简介

CatMusic 是一个离线优先、没有账号和遥测的 Android 本地音乐播放器。它会把导入的音频复制到应用私有目录，支持 LRC 歌词、自定义封面、歌单、聆听统计和 Media3 后台播放；界面使用真正的 QWEA0 Liquid Glass 液态玻璃和跟随封面的动态色场。它还带有一个基于 MusicDL 思路的在线搜索页，可以从九个公开音乐来源检索、试听，并按音质保存，供个人非商业使用。

**1.27.0 起，全屏音浪页换成了 CatMusic 完全自研的 WebGL「声场」**：页面结构、CSS、着色器、交互与渲染器都在 `app/src/main/assets/soundfield/`，不再包含旧版实验性音浪场景及其依赖资源。两套原创效果「星潮」和「回声」都从当前专辑封面取色，并跟随本地解码出的八频段音频与鼓点运动。

## 公开仓库说明

- **源代码公开，但不是 OSI 开源许可。** CatMusic 原创代码与文档采用 **PolyForm Noncommercial 1.0.0**。第三方组件保留各自许可证，详见 [LICENSE](LICENSE) 与 [NOTICE.md](NOTICE.md)。
- **可公开的完整音浪源码在这里。** `app/src/main/assets/soundfield/` 包含完整页面与 WebGL 渲染器；没有来源不明或被限制再分发的场景代码。
- **不包含**：release 签名密钥、`keystore.properties`、真实微信赞赏二维码（仓库内为不可扫描的占位图）、任何 APK、作者的个人音乐 / 封面 / 歌词、下载缓存，以及 MusicDL 上游参考项目。
- 音浪页运行在离线 WebView 中，`blockNetworkLoads = true`；本地曲库、封面、歌词、歌单与统计不会上传。

## 功能亮点

- **本地优先**：导入音频、LRC/TXT 歌词与封面，复制到应用私有目录；删除歌曲只删软件内副本，不改动手机原文件。
- **完整播放体验**：Media3 / ExoPlayer 后台播放、通知栏与锁屏控制、上/下一首、列表 / 单曲 / 循环、定时暂停与淡入淡出。
- **音乐馆、歌单与统计**：搜索、编辑歌曲信息、自定义封面、歌单增删排序、累计 / 今日 / 近 7 天聆听统计。
- **液态玻璃界面**：QWEA0 Liquid Glass Android（MIT）真实折射与传感器高光，配合封面主色渐变的原生动态背景。
- **歌词**：原生渐变歌词页与 LRC 高亮；支持双语行、单曲歌词偏移校准与任意歌词跳转。
- **原创声场**：
  - **星潮**：10,000 个光点（100 经线 × 100 纬圈）组成的封面球。每个点按球面 UV 从 96×96 封面缩略图逐点取色，映射不镜像；单指拖动可水平 360° 旋转，双指张合缩放。八个解码频段与平滑电平让球壳呼吸，鼓点用弹簧包络将它炸成空心云团，再缓慢回吸。
  - **回声**：原创透视声波光带，多层涟漪随低频与节拍起伏；鼓点向外推开光圈，颜色同样来自当前封面。
  - 效果选择、每个效果各自的视角与缩放、鼓点律动、歌词行数（3–10 行）、陀螺仪开关与灵敏度都会保存在本机。
  - 横屏时歌词在左、声场在右；菜单自动变为紧凑布局。歌词始终位于声场前方，轻点即可跳转。
  - WebGL 不可用或页面加载超时时自动回退到原生歌词页。
- **MusicDL 在线模块**：网易云、QQ、咪咕、酷我、千千、酷狗、JOOX、5sing、Jamendo 九源检索；搜索阶段解析最高可用音质与真实体积；默认无损，可按标准 / 较高 / 无损保存到播放器或系统下载目录。
- **在线与本地播放不互相打断**：切到「在线」或切换标签不会暂停本地播放；只有在线试听真正开始时，本地播放器才淡出暂停。
- **安全删除**：在软件中删除歌曲只删除应用私有副本、封面、歌词和歌单引用；手机原文件永远不会被软件删除。
- **隐私**：无需账号、广告或分析上报。只有主动使用「在线」页时才联网；本地曲库、封面、歌词、歌单与统计不上传。声场页不申请麦克风权限。

## 使用

1. 导入音频、LRC/TXT 歌词和封面；点击歌曲播放，点击底部媒体栏进入全屏播放页。
2. 点击播放页底部左侧「词」进入歌词；首次可从原生歌词页右侧开启音浪，之后会进入上次选择的声场效果。
3. 声场顶部左侧返回，右侧打开设置菜单；底部左侧播放 / 暂停，中间进度线，右侧关闭声场。
4. 菜单提供：星潮 / 回声切换、鼓点律动、歌词行数、陀螺仪灵敏度、姿态陀螺仪、重置视角、歌词校准、导入 / 更换歌词、关闭声场和返回封面。
5. 单指拖动旋转 / 环绕声场，双指张合缩放；轻点歌词或进度线跳转。

## 技术栈

| 层 | 使用 |
|---|---|
| 语言 / UI | Kotlin 2.0.21、Jetpack Compose |
| 播放 | AndroidX Media3 / ExoPlayer、MediaSessionService |
| 音频视觉 | 本地 PCM 八频段分析、自定义 WebGL 点精灵 / 光带着色器 |
| 视觉 | QWEA0 Liquid Glass Android（MIT）、跟随封面的原生渐变背景 |
| 在线音乐 | 自带 MusicDL Lite WebView 页面 + Kotlin/Java 网络桥 |
| 存储 | 应用私有目录、SharedPreferences、WebView localStorage |
| 构建 | Gradle 8.7、AGP、compileSdk 36、minSdk 26 |

## 快速开始

### 环境

- JDK 17 或 21（作者使用 JDK 21）
- Android SDK Platform 36 和 Build Tools 36
- Gradle Wrapper 随仓库提供

### 构建 Debug APK

```bash
git clone https://github.com/mao-niang520/catmusic.git
cd catmusic
```

创建 `local.properties`（不要提交）：

```properties
sdk.dir=/path/to/Android/Sdk
```

或在环境中设置 `ANDROID_HOME` / `ANDROID_SDK_ROOT`。

```bash
./gradlew :app:assembleDebug
```

Windows 使用 `gradlew.bat`。产物为 `app/build/outputs/apk/debug/app-debug.apk`。

### Release 签名

本仓库不包含作者的签名密钥，也不提供可直接安装的 APK。请创建你自己的 keystore，并在本地新建 `keystore.properties`：

```properties
storeFile=/path/to/your-release.jks
storePassword=********
keyAlias=your-alias
keyPassword=********
```

然后运行 `./gradlew :app:assembleRelease`。不要把 keystore 或 `keystore.properties` 提交到 Git。

## 目录结构

```text
app/src/main/java/com/catmusic/app/   Kotlin 应用：曲库、播放、UI、在线桥
app/src/main/assets/musicdl/          MusicDL Lite 在线页面资源（PolyForm，非商业）
app/src/main/assets/soundfield/       原创声场：HTML / CSS / WebGL / 桥接
app/src/main/res/                     图标、字体、主题与界面资源
licenses/                             第三方许可证与声明
docs/                                 开发说明、声场说明、合成演示图
```

更多技术细节：

- [声场实现与交互说明](docs/SOUNDFIELD.md)
- [完整开发与功能说明](docs/DEVELOPMENT.zh-CN.md)
- [第三方声明](NOTICE.md)

## 隐私与数据

- 不需要账号、广告、分析上报或云端同步。
- 导入的音频、封面和歌词只保存在应用私有目录；除非用户主动使用在线页，否则不会联网。
- 在线页只把搜索关键词和所选来源发给第三方公开接口，不携带账号 Cookie，不上传本地文件。
- 声场歌词页禁用网络加载；音频由本地 PCM 分析驱动，不使用麦克风。
- 在线内容、封面、歌词和音频的权利归各平台 / 权利人所有；本项目不托管、不再分发任何第三方音频。请遵守平台条款和当地法律，仅将在线功能用于个人非商业用途。

## 已知限制

- 在线来源不是官方开放 API，平台改版、地区限制或版权限制可能导致某一来源暂时不可用。
- 声场是视觉功能，不影响音频解码与播放；极低端设备在高画质档可能发热或降帧，可切到「轻」或「标准」。
- 本项目采用非商业许可证；如需商业使用，请分别联系 CatMusic 原创代码与各第三方组件的权利人。

## 许可证

- **CatMusic 原创代码与文档**：PolyForm Noncommercial License 1.0.0，见 [LICENSE](LICENSE)。这是 source-available 的非商业许可，不是 OSI 开源许可。
- **第三方组件**：MusicDL / MusicDL Lite（PolyForm Noncommercial 1.0.0）、MusicSquare（Apache-2.0）、QWEA0 Liquid Glass Android（MIT）、Outfit 字体（SIL OFL）等，保留各自版权与许可。完整列表见 [NOTICE.md](NOTICE.md) 与 [`licenses/`](licenses/)。
- **声场**：`app/src/main/assets/soundfield/`、`particle` 风格点精灵、星潮与回声着色器、页面样式和桥接均为 CatMusic 原创实现，随 CatMusic 原创代码一同授权，不包含第三方场景代码。

## English

CatMusic is a local-first Android music player built with Kotlin, Jetpack Compose and Media3. It imports local audio, artwork and LRC lyrics into app-private storage, and supports playlists, listening statistics, background playback, a QWEA0 liquid-glass UI, and an optional MusicDL-powered search/download page for personal noncommercial use.

Since **1.27.0**, the full-screen sound field is CatMusic's own HTML/CSS/WebGL implementation. The **Star Tide** effect wraps roughly 10,000 cover-coloured points around a non-mirrored globe that breathes with eight decoded bands and bursts open on beats; the **Echo** effect is an original perspective wave lattice driven by the same local PCM signal. Both effects persist their own view, effect choice, beat strength and lyric layout locally. The page runs offline (`blockNetworkLoads`), requests no microphone permission, and falls back to the native lyric screen if WebGL is unavailable.

This public export excludes signing keys, the real WeChat donation QR code (a non-scannable placeholder is included), APKs, personal media and downloaded music. CatMusic's original code is source-available under the **PolyForm Noncommercial License 1.0.0**; third-party components retain their own licenses. See [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md). No affiliation with any music platform; no audio files are hosted or redistributed.

---

一个给爱听歌的猫做的小项目。感谢 MusicDL、MusicSquare、QWEA0 Liquid Glass 以及所有公开音乐服务与开源作者。
