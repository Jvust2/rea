# FlowSave 0.2 — 流影下载

Android 原生界面 + Python（Chaquopy）+ [Jvust2/yt-dlp](https://github.com/Jvust2/yt-dlp) 下载引擎。由 `Jvust2/rea` 的 `apk/flowsave` 分支维护，避免修改原始 yt-dlp 代码。

## 支持平台（尽最大能力，不做 100% 保证）

- YouTube / Shorts
- X（Twitter）视频
- TikTok 视频
- Instagram Reels / Posts
- 抖音短视频
- Bilibili 视频

> 这 6 个站点的提取器由 yt-dlp 提供。视频必须可访问、可下载；受登录、地区、验证码、签名验证、网络策略、API 变更、DRM 等因素影响，不能保证每条链接都成功。本应用没有 DRM 绕过功能，也不提供登录绕过。

## 0.2 功能

- 清爽的深色中文界面、六平台提示、自动识别分享文案中的视频链接。
- 最佳画质 / 1080P / 720P / 音频（音频优先 m4a，不转码）。
- 自动选择含音轨的媒体文件；若仅提供兼容 MP4 视频轨 + M4A/AAC 音轨，则用 Android **MediaMuxer** 在本地合并，无需 FFmpeg。
- 为 YouTube 提供 `yt-dlp-ejs==0.8.0` + Android ABI 专用 QuickJS（由 GitHub Actions 使用 NDK 编译）。
- Netscape Cookies 文件导入、清除；Cookies 存于应用私有目录，无服务器，绝不上传到 FlowSave。
- 进度、取消、自动保存到系统 `Download/FlowSave`，可从其他 App 分享文字链接到 FlowSave。
- Android 7.0+；当前打包 arm64-v8a、x86_64。

## 构建 APK

GitHub Actions **Android APK** 在 `apk/flowsave` 分支推送时执行：

1. 安装 JDK 17 / Android SDK / NDK / Gradle / Python 3.13
2. 从连接的 `Jvust2/yt-dlp` 仓库打包最新 `yt_dlp` Python 代码
3. 用 NDK 交叉编译 QuickJS executable，放到 `jniLibs/<abi>/libqjs.so`
4. 使用 Gradle/Chaquopy 构建 Debug APK
5. 下载 Actions 构建产物 `FlowSave-v0.2-APK`

安装到 Android 前，请确认你信任源码和构建来源。本项目当前仅 Debug 签名；正式发布需要自行管理签名密钥。

## 当前限制

- 目前没有 FFmpeg，Android MediaMuxer 仅能合并**手机支持的 MPEG-4 媒体轨**；不支持 VP9/Opus 到 MP4 转码，站点只提供不兼容格式时会提示失败。
- `yt-dlp-ejs` + QuickJS 可以增加 YouTube 的格式兼容性，但仍有 JS 挑战、PO Token、登录等限制，无法保证所有 YouTube 内容可下载。
- 部分 TikTok / Instagram / 抖音链接需要登录 Cookies、地区权限或浏览器 TLS 指纹。本版没有 curl_cffi 浏览器模拟，遇到 403 等应说明限制。
- 音频模式优先使用网站提供的原始音轨，不保证转换为 MP3；某些站点只提供音视频合一文件时会尝试从 MP4 提取音轨。
- 下载任务保持应用页面开启时更可靠；尚无后台前台服务、队列任务或媒体库。
- 请只下载自己拥有或获得合法授权的内容，并遵守相应服务条款。

## 本地测试

```bash
python -m unittest discover -s tests -v
```

QuickJS (MIT) license and yt-dlp (Unlicense) license remain applicable to their respective components.
