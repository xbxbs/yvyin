# AMLL 同曲对照

此目录只用于浏览器基准，不参与 Android APK 编译。Android 界面始终是 Kotlin / Compose。

- 固定 npm 核心版本 `@applemusic-like-lyrics/core@0.6.0`，禁止用另一个主分支提交冒充本次运行版本。
- 使用工程内同一首歌的逐字时间戳、封面、背景以及 Noto Sans SC Medium 子集。
- 歌词从 24863ms 开始，排除制作信息；390×844 CSS 像素视口，字体 31.2px，顶部锚点。
- 时间由连续浏览器时钟推进，未播放音频；用来检查布局、运动和时间戳高亮，不是音画延迟测试。
- 页面外壳为本项目的最小对照布局，不是 AMLL React 全功能播放器，更不是 Apple Music 实录。

Linux / ARM64 环境运行：

```sh
bash tools/amll-benchmark/prepare.sh
```

需要 Node/npm、浏览器运行依赖，以及可写的 npm 和浏览器缓存。脚本自动把依赖与可执行工具安装到 Ubuntu 的 `/root/amll-benchmark`，不在手机共享存储执行二进制。可用 `BENCHMARK_WORK` 调整工作目录、`PROJECT_DIR` 指定源工程目录；不要在 Android 宿主直接执行 Linux 二进制。

`BENCHMARK_DIR` 指定静态站点目录，默认 `/root/amll-benchmark/site`；`BENCHMARK_OUTPUT` 指定输出目录，默认 `/root/amll-benchmark/output`。脚本仅监听 `127.0.0.1:8795`，录制完成即关闭服务器。

输出包含四个固定时间戳 PNG、连续换句 PNG 帧和 `motion-samples.json`。采样文件保留浏览器页面错误；检查错误为空后，才可将这些文件称为真实运行结果。截图采样并不等同 60fps 性能测试，也不能替代 Android 真机对照。
