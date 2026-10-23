# FlowSave · 流影下载 Android

以 `Jvust2/yt-dlp` 的 Python `yt_dlp` 包为下载核心，Chaquopy 将 Python 3.13 嵌入 Android APK；手机无需单独安装 Python。

## 首版功能

- 粘贴和解析视频链接，显示标题、作者及长度（站点支持时）
- 下载无需 FFmpeg 合并的单文件兼容视频（优先不高于 720p）
- 下载站点提供的原始音频流（优先 m4a，不做转码）
- 显示进度，Android 10+ 使用 MediaStore 保存到系统 `Download/FlowSave`
- 支持 Android 7.0+，64 位 arm64-v8a 和 x86_64

## 构建

1. `git clone https://github.com/Jvust2/yt-dlp.git`
2. 将其中的 `yt_dlp` 文件夹复制到 `android-app/app/src/main/python/yt_dlp`
3. 安装 JDK 17、Android SDK API 35、Gradle 8.10.2、Python 3.13
4. 在此目录执行 `gradle -p android-app assembleDebug`
5. APK 在 `android-app/app/build/outputs/apk/debug/app-debug.apk`

若启用 Actions，可使用 `Android APK` 工作流自动构建并下载构建产物。

## 使用提醒

仅保存自己拥有权利或获得授权的内容。部分网站需要账户登录、DRM、JS challenge、FFmpeg 合并或特殊网络环境，首版可能不支持。本项目不绕过 DRM，也不包含 FFmpeg。Debug 构建使用开发调试签名，仅用于自行安装测试。

依赖 yt-dlp 源码来自 `Jvust2/yt-dlp`，项目代码许可证：Unlicense（详见上游仓库 LICENSE）。
