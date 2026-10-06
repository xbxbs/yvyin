# 上游与修改说明

## AMLL 参考和策略移植

- 上游：AMLL / Apple Music-like Lyrics，AMLL Contributors。
- 官方仓库：https://github.com/amll-dev/applemusic-like-lyrics
- 本版核对的运行包：`@applemusic-like-lyrics/core@0.6.0`，以 npm 发布包内的 `dist/amll-core.mjs` 和 source map 为准。
- 上游许可证：AGPL-3.0-only，完整文本见 `LICENSE` 和应用内 `assets/licenses/AMLL-AGPL-3.0.txt`。
- 原生适配文件：`AmllSpring.kt`、`LyricMotionPolicy.kt`、`LyricsScreen.kt`、`PlayerScreen.kt`。2026-10-05 改动：移植 `src/utils/spring.ts` 的解析弹簧与延迟目标队列，行位移和缩放共用帧时钟；按上游实现累计错峰、模糊/明暗过渡、常规上浮与长音强调。所有实现仍为 Kotlin / Jetpack Compose，未接入 DOM、WebView 或 JavaScript 运行时。
- 本次原生适配补充：缓存独立字形布局，预留模糊/上浮绘制余量，在歌词视口最终合成层应用上下像素渐隐；播放采样通过连续时钟校正，避免正常播放时间倒退。这些是 Android 渲染与播放适配，不代表 Apple Music 官方代码。
- 对照工具：`tools/amll-benchmark/`，在浏览器里使用原版 npm 包；不是 Apple Music 官方程序，也不代表 Apple 内部实现。
- 等待点补充移植：对照该 npm 包 `src/lyric-player/base/interlude-dots.ts` 的 `lightingEasing`、`dotEnterAlpha` 和第三点尾随点亮逻辑；保留本应用既有、用户认可的整组缩放与退出曲线，并非宣称与 Apple Music 官方实现完全一致。

为保留本版策略移植的上游许可要求，本项目可许可的程序源码及修改按 AGPL-3.0-only 提供，随 APK 同时交付对应源码归档。源码包包含构建脚本、资源生成脚本和本次适配源码；不要求用户自行反编译 APK。

## Haze 1.3.1

- 组件：Haze 1.3.1，`dev.chrisbanes.haze:haze:1.3.1`。
- 上游：https://github.com/chrisbanes/haze；版权：Chris Banes。
- 许可证：Apache License, Version 2.0（Apache-2.0）。该版本许可原文：https://github.com/chrisbanes/haze/blob/1.3.1/LICENSE。
- 用途：本轮玻璃公共材质的背景源与效果层分离。主线程已写入 Haze 运行时依赖及 MusicGlass/GlassTabBar 接线，旧 FrostedBackdrop 已移至 `dist/legacy-ui-backup/` 可恢复；尚未真机验收，不代表 Apple 官方实现或用户已认可效果，也不据此宣称旧 dist APK 已包含 Haze。
- 随包许可源文件：`app/src/main/assets/licenses/haze-Apache-2.0.txt`，已附 `Copyright Chris Banes`，完整 Apache-2.0 原文取自 Ubuntu `/usr/share/common-licenses/Apache-2.0`。设置页自动枚举 `assets/licenses`；已确认本轮 release APK 收录该文件，页面展示仍待真机核对。

## 独立资源

- OkHttp 4.12.0 与 Okio 3.9.0：Square, Inc. 及项目贡献者，Apache-2.0；用于隔离全局 Cookie 的封面下载，不传递播放凭据。上游分别为 https://github.com/square/okhttp 和 https://github.com/square/okio，许可见 `app/src/main/assets/licenses/network-Apache-2.0.txt`。

- Inter 与 Noto Sans SC 字体：SIL Open Font License 1.1。完整文本在 `app/src/main/assets/licenses/`。
- 用户此前提供的《如诗一般的形容妳》音频、歌词和封面已移出 APK 资源，保留在工程 `dist/legacy-demo-backup/` 中。权利归各自权利人，程序许可证不授予这些媒体资源的再分发权。
- 本地扫描读取用户授权的 MediaStore / 文档 URI；在线元信息与媒体权限归相应提供方。第三方 APK 只用于协议分析，不将其 DEX、Hermes 字节码、品牌素材或私密凭据打入本应用。
- Android / Kotlin / AndroidX 依赖保留各自许可证。本项目不包含 Apple 专有字体、商标授权或 Apple 服务接口。
