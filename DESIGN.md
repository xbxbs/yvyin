---
name: "余音"
description: "资料库首页、动作菜单、歌曲详情与浮动底栏的原生 Compose 视觉规范"
colors:
  library-accent: "#FA2D48"
  library-ground: "#000000"
  text-primary: "#FFFFFF"
  library-secondary: "rgba(235, 235, 245, 0.6)"
  library-hairline: "rgba(255, 255, 255, 0.1)"
  library-raised: "#1C1C1E"
  menu-text: "rgba(255, 255, 255, 0.96)"
  information-label: "rgba(255, 255, 255, 0.68)"
  popup-base: "#242426"
  popup-tint: "rgba(36, 36, 38, 0.78)"
  popup-fallback: "rgba(36, 36, 38, 0.98)"
  navigation-tint: "rgba(37, 37, 39, 0.46)"
typography:
  headline:
    fontFamily: "sans-serif"
    fontSize: "30sp"
    fontWeight: 600
    lineHeight: "40.5sp"
  title:
    fontFamily: "sans-serif"
    fontSize: "20sp"
    fontWeight: 600
    lineHeight: "27sp"
  body:
    fontFamily: "sans-serif"
    fontSize: "16sp"
    fontWeight: 400
    lineHeight: "21.6sp"
  label:
    fontFamily: "sans-serif"
    fontSize: "13sp"
    fontWeight: 400
    lineHeight: "17.55sp"
  menu-action:
    fontFamily: "sans-serif"
    fontSize: "17sp"
    fontWeight: 400
    lineHeight: "22sp"
  information-value:
    fontFamily: "sans-serif"
    fontSize: "15sp"
    lineHeight: "20sp"
    fontFeature: "tnum"
  navigation-label:
    fontFamily: "sans-serif"
    fontSize: "11sp"
    fontWeight: 400
    lineHeight: "14.3sp"
rounded:
  landing-entry: "8dp"
  artwork-search: "10dp"
  menu: "16dp"
  information: "22dp"
  navigation: "32dp"
  capsule: "50%"
spacing:
  navigation-inset: "4dp"
  small: "8dp"
  row-gap: "12dp"
  menu-inset: "16dp"
  page-inset: "18dp"
  information-inset: "20dp"
  bottom-clearance: "24dp"
components:
  context-menu:
    backgroundColor: "{colors.popup-tint}"
    textColor: "{colors.menu-text}"
    rounded: "{rounded.menu}"
    width: "280dp"
  information-panel:
    backgroundColor: "{colors.popup-tint}"
    rounded: "{rounded.information}"
    width: "344dp"
  menu-action:
    textColor: "{colors.menu-text}"
    typography: "{typography.menu-action}"
    padding: "11dp 16dp"
  floating-navigation:
    backgroundColor: "{colors.navigation-tint}"
    rounded: "{rounded.navigation}"
    padding: "{spacing.navigation-inset}"
    width: "236dp"
    height: "64dp"
  navigation-pill:
    rounded: "{rounded.capsule}"
    width: "76dp"
    height: "56dp"
---

# Design System: 余音

## Overview

**Creative North Star: "Apple Music 保留面与 KernelSU 浮动底栏"**

本文件记录用户已确认方向及当前原生 Compose 实现，不另造品牌概念。范围限于资料库首页、动作菜单、歌曲详情和浮动导航；原播放页的布局、字号、取色背景不是本轮重做对象。页面以黑底、白色正文和灰色辅助信息承载内容，沿用红色操作色；玻璃材料集中在浮动导航和弹层。

方向合同见 [design/UI_REVISION.md](design/UI_REVISION.md)。底栏参考 KernelSU 提交 `e7b071100754b55e28be6930f9967d99adb4a385` 的 `FloatingBottomBar`，使用工程既有 Kyant Backdrop 管线适配；跟手状态和弹簧是本地实现，不等同于完整移植。本文依据源码提取，尚无可操作真机或模拟器的渲染与交互验收，不宣称像素一致或玻璃观感已获认可。

**Key Characteristics:**

- 内容先于装饰：平面入口之后直接呈现最近添加封面。
- 单一弹层材料：动作与详情分别组织，不在菜单内部套灰色圆卡。
- 导航随手指连续移动；松手后接续速度，保留原生语义操作。
- 使用 Android 系统字体与 dp/sp；不以网页预览代替原生证据。

Frontmatter 是已提取 token 的机器可读清单，dp/sp 保留 Android 单位，不可直接当作 CSS。最低触摸尺寸、宽度约束、Compose 弹簧和材质行为由下文及 `.impeccable/design.json` 补充。sidecar 的 `components` 留空，不制作需要虚构 CSS 运行时的原生组件预览，也不生成源码不存在的配色色阶。

## Colors

### Primary

`library-accent` 是既有红色操作色，用于首页入口图标、显式操作、菜单选中项和当前导航标签。不会替换播放页自己的封面取色方案。

### Neutral

`library-ground` 与 `text-primary` 构成内容底和常规正文；`library-secondary` 承担数量、艺人和次级说明。`library-raised` 用于搜索等既有抬升底色，搜索框实际将其以 0.58 的透明度合成到黑底，不采样后方歌曲文字。

`menu-text` 与 `information-label` 区分动作/参数值和辅助标签；`library-hairline` 用于细分隔。弹层的底色、磨砂 tint 和不支持模糊时的 fallback 分别对应 `popup-base`、`popup-tint`、`popup-fallback`。`navigation-tint` 是导航的中性覆盖色，不引入常驻彩色光晕。

颜色来源：`LibraryScreen.kt`、`AppSheet.kt`、`PlayerInformation.kt`、`AppContextMenu.kt`、`LiquidGlass.kt`；这些文件均位于 `app/src/main/java/com/example/xuebimc/`。

## Typography

使用 Android 系统 `sans-serif`，中文跟随系统字形回退；`PlayerTypography` 的强调字族为 `sans-serif-medium`。不捆绑或伪称使用 Apple 专有字体。

`headline` 对应资料库标题，`title` 对应“最近添加”等区块标题，`body` 对应首页入口与封面标题，`label` 对应首页数量及次级说明。`LibraryText` 的行高为字号的 1.35 倍，并关闭额外字体 padding；不要把这些首页 token 套用到原播放页。

`menu-action` 保持常规字重，不把动作列表整体加粗；`information-value` 用于参数值，并启用等宽数字特性。`navigation-label` 对应浮动底栏的单行标签，行高为字号的 1.3 倍。曲名和艺人采用既有换行/截断策略，详情长值优先完整显示。

来源：`LibraryScreen.kt::LibraryText`、`PlayerTypography.kt`、`AppSheet.kt::SheetActionRow`、`PlayerInformation.kt::PlayerInformationRow`、`GlassTabBar.kt::GlassText`。

## Layout

首页在安全区内使用 `page-inset` 横向留白，内容顶部为 14dp，不再为空工具栏预留原先的顶部大块空白。标题和更多操作共享一行，摘要位于标题下，搜索在其后；这项排列只约束本轮资料库首页。

六个入口默认两列三行，列间距 20dp，单项最小高度 54dp。`fontScale > 1.25` 或 `screenWidthDp < 340` 时回退一列，不缩小字号来维持双列。搜索输入、清除和取消操作均有至少 48dp 的高度；这不等于全应用触摸目标均已审计。

最近添加使用真实歌曲封面，最多展示 12 项；窄屏两列，`screenWidthDp >= 600` 时三列，方形封面后依次为歌名和艺人。底部内容留白由浮动控件预留高度加 `bottom-clearance` 组成，不通过折叠底栏反复改变列表 padding。

菜单与详情分别使用 frontmatter 中的目标宽度，实际宽度不超过可用宽度减 32dp。菜单贴近触发点并限制在安全区内；详情在安全区内居中。菜单最大高度为安全区的 74%，详情为 86%，长内容内部滚动。详情参数在 `fontScale > 1.2`、内容宽度小于 250dp、值长于 32 字符或显式 `stacked` 时改为上下排列；专辑信息始终上下排。

来源：`LibraryScreen.kt::LibrarySurface/LibraryLandingNavigation`、`AppContextMenu.kt::Render`、`PlayerInformation.kt::PlayerInformationRow`、`GlassTabBar.kt::GlassTabBar`。

## Elevation & Depth

首页入口保持平面，封面与文字是主要视觉层级；深度集中在覆盖内容的导航与弹层。菜单使用 Haze 局部模糊（28dp）、零噪声、单一连续材料和原生阴影（12dp elevation），页面本身不做全屏模糊。动作菜单遮罩为黑色 0.12，详情为 0.28。

底栏基座使用 Kyant `vibrancy()`、4dp blur 和 24dp/24dp lens；默认镜片调用不启用常驻色散。阴影半径 10dp、偏移 `(0dp, 3dp)`、黑色透明度 0.2。按压时局部白色高光出现在手指附近；移动 pill 采样基座与隐藏的红色标签层，按压力度驱动镜片、内阴影和轻微形变，不绘制常驻彩色光晕。

这些是 Android 渲染参数，不是等价的 CSS 阴影或滤镜保证。缺少 liquid backdrop 时保留既有 Haze 材料及静态选中底色分支；最终合成、边缘清晰度和帧稳定性仍待真机验收。

来源：`AppContextMenu.kt`、`LiquidGlass.kt`、`GlassTabBar.kt::LiquidNavigationBar`。

## Shapes

入口使用 `landing-entry` 的轻圆角；搜索与大封面使用 `artwork-search`。动作菜单与详情分别使用自己的圆角，不将宽详情挤进窄动作菜单的外形。底栏使用 `navigation` 的胶囊基座，移动指示器为 Compose `CircleShape`（frontmatter 的 `capsule`）。

菜单内部动作不再各自包裹圆卡。普通动作分隔线按屏幕密度换算为一个物理像素，左侧跟随菜单内距；分组使用 6dp 的低对比条带。独立 `AppSheet` 表单对话框仍有自己的既有容器，不把这一弹层菜单规范误称为所有对话框的统一重写。

来源：`AppSheet.kt::SheetActionGroup/SheetActionDivider/SheetActionSectionDivider`、`AppContextMenu.kt`、`LibraryScreen.kt`、`GlassTabBar.kt`。

## Components

### 资料库入口与搜索

入口是图标、文字、可选数量组成的平面导航，不是六张大卡。搜索保持独立实色底、明确输入语义、搜索键盘动作以及清除/取消路径。更多按钮沿用原有资料库操作。

### 动作菜单

`context-menu` 从触发点以缩放和透明度进入，可通过返回键、遮罩和符合条件的下拉关闭。每个 `menu-action` 最小高度 48dp；按下覆盖白色 0.12，键盘聚焦覆盖白色 0.08，选中项使用操作色。下拉仅接收内部滚动未消费的顶部位移，不能把正常阅读滚动当作关闭。

### 歌曲详情

`information-panel` 是居中的单层信息表面，包含封面/歌名/艺人、音质标签、文件参数及歌词操作。标题、长专辑和参数值保留足够换行空间；未知信息显示既有“未知”等文案，不补造音质数据。可点击的详情动作最小高度 48dp。

### 浮动导航

`floating-navigation` 包含资料库、在线、设置三个位置，每个位置宽 76dp；`navigation-pill` 在内距以内移动。标签和图标保持自己的颜色层，移动镜片采用基座导出层与隐藏红色标签层的组合采样。点击、键盘 Enter/空格/方向中心键、TalkBack 的 Tab 语义均保留；水平拖动提交目的地，越界/纵向取消回到原选中项，支持 RTL 索引映射。

`LiquidNavigationMotion` 将松手的位置和速度交给可中断弹簧：位移使用阻尼比 1、刚度 620，按压使用阻尼比 1、刚度 1100。无动画分支直接定位，并禁用 pill 的镜片按压形变和速度拉伸；这里只记录该底栏分支，不宣称所有弹层都完成了减少动态效果审计。

## Do's and Don'ts

### Do:

- Do 以当前原生 Compose 源码和方向合同为证据，使用 Android dp/sp 与系统字体。
- Do 在窄屏或大字体下允许入口和参数重排，保留已实现的最小触摸区域。
- Do 将动作菜单和歌曲详情作为宽度、信息密度不同的单层表面。
- Do 在真机检查长中文、字体放大、RTL、键盘/TalkBack、拖动中断和玻璃合成后再作视觉验收。

### Don't:

- Don't 将本轮首页字号或布局规则扩散到已要求保留的播放页。
- Don't 在当前动作菜单内重新叠加独立灰色圆卡，或给导航加入常驻色散与彩色光晕。
- Don't 将 KernelSU 参考、源码检查、构建通过或网页模拟图称为原生界面已经一致或已验收。
- Don't 为未观察到的主题、字体、配色色阶、组件状态或运行时编造 token。
