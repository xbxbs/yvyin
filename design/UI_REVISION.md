# 本轮界面重做约束

用户已明确以 Apple Music 的播放器为保留面，以 KernelSU 的浮动底栏为新参考；此前截图是被否定的旧界面，不是本轮要复刻的目标。原先取消的 init 问卷不重新启动。原生 Compose 直接实现，不制作冒充实机的网页截图。

## Direction contract

- THESIS：让资料库内容在首屏出现；首页不再是六个大行加一块顶部空白，弹窗不再是嵌套的灰色圆卡。
- OWN-WORLD：黑色内容底、白色常规正文、灰色次级信息、既有红色操作色。玻璃仅用在浮动导航和弹层，播放页既有取色背景不变。
- STORY：进入即能搜索、打开歌曲/歌单/分类或看到最近封面；动作菜单只管操作，歌曲参数使用宽度独立的详情面板。
- FIRST VIEWPORT：首页标题与更多同一行，摘要在标题下，搜索在其后；六个入口压成两列三行的平面导航，随后即显示最近添加封面。大字体时导航回退单列，触摸区域不小于48dp。
- FORM：按用户点名的 Apple/KernalSU 路径实现，非随机概念选型。KernelSU参考 commit e7b071100754b55e28be6930f9967d99adb4a385，manager FloatingBottomBar.kt；本工程保留既有可编译的 Kyant Backdrop 管线并移植适配。
- MOTION：导航指示器可中断滑动与按压，菜单从触发点进出；背景与播放器位置不参与本轮重排。
- FINISH：unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

## 验证边界

没有可操作的真机/模拟器连接，因此只报告代码审查和构建证据，不宣称最终渲染或玻璃观感已获认可。本轮不用新增位图资产。
