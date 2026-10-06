package com.xuebi.zhongduan.ui.designsystem

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp

/**
 * 高频结构尺寸。
 *
 * 间距应走 [ZdSpacing]，圆角应走 `Motion.Radius`；这里仅收纳需要跨组件复用的
 * 点击区、导航和身份尺寸，避免界面代码重新散落一批 "看起来差不多" 的 dp 数字。
 */
object ZdSize {
    val minTouch: Dp = 48.dp

    /**
     * 一个可见高度为 [visible] 的控件套在 [minTouch] 触控盒里时，上下各多出的看不见的余量。
     *
     * 排版节奏按**可见边缘**算：相邻块的间距减去这部分，肉眼看到的间距才等于令牌值。
     * 不这么算，36dp 的胶囊和 12sp 的轮末字各自多出 6–16dp 空白，轮末读不出属于哪一轮。
     */
    fun touchSlack(visible: Dp): Dp = ((minTouch - visible) / 2).coerceAtLeast(0.dp)

    /**
     * 浅色下卡片的浮起高度。
     *
     * 只给 2dp，而且只给浅色。这不是 Material 的 elevation 台阶——那套东西
     * 4dp 起步、带明确方向，投出来是一道硬边落影。苹果与澎湃的卡片影是
     * **弥散的一小片**，几乎只在卡片正下方一两个像素处可见，作用是让边缘不再
     * 是一条数学上的直线，而不是宣告"我比背景高 8dp"。
     *
     * 深色不用它：黑影打在深底上什么都看不见，那边靠顶缘镜面高光
     * （见 `Tone.Lift`）。
     */
    val cardLift: Dp = 2.dp

    val topBarHeight: Dp = 48.dp
    /** 顶栏左胶囊的最大宽度；给右侧回滚与更多胶囊保留稳定空间。 */
    val topBarTitleMaxWidth: Dp = 200.dp
    /** 当前模型文字随内容收口，超长名称在左胶囊内省略。 */
    val topBarIdentityTextMaxWidth: Dp = 120.dp
    /** Maximum width of the modal conversation navigation panel. */

    val drawerWidth: Dp = 352.dp
    /**
     * 设置分组的最小行高；多行说明与放大字体按内容自然撑开。
     */
    val settingRowMinHeight: Dp = 52.dp
    /**
     * 抽屉会话与过程信息使用同一档 52。
     *
     * 与 [settingRowMinHeight] 同值是结论不是巧合：两者都是「一行一个意图」
     * 的扫描型列表，没有任何理由让同一种阅读行为在两个页面里高度不同。
     */
    val conversationRowMinHeight: Dp = 52.dp
    /** 底部主输入区需要容纳编辑行与操作行，但不应膨胀成面板。 */
    val composerMinHeight: Dp = 64.dp
    /** 输入卡局部模糊：保留背景色块，压住后面的文字细节。 */
    val composerBlurRadius: Dp = 24.dp
    /** 顶栏局部模糊，不将正文行扩散成大面积亮带。 */
    val chatHeaderBlurRadius: Dp = 16.dp
    /** 独立画在模糊层之上的反光轮廓。 */
    val composerRimWidth: Dp = 1.dp
    val segmentedControlHeight: Dp = 48.dp
    val statusDot: Dp = 6.dp
    val navigationIcon: Dp = 20.dp

    /**
     * 图标按钮里字形相对按钮外框的左缩进。
     *
     * ## 为什么需要这个数
     *
     * [ZdIconButton] 是 [minTouch] 见方的盒子，[navigationIcon] 的字形**居中**放在里面。
     * 也就是说盒子左缘和字形左缘并不重合，中间恒定隔着 (44-20)/2 = 12dp 的透明触控区。
     *
     * 于是「文字和图标按钮左对齐」有两种做法，只有一种是对的：
     * - 让文字对齐**盒子**左缘 → 文字看起来比图标凸出去 12dp（人眼看字形，不看不可见的触控区）
     * - 让文字对齐**字形**左缘 → 这才是视觉对齐
     *
     * 输入卡此前用的是第一种，注释还写着「文字与图标真正成一行」——
     * 正文左缘 8、Plus 字形左缘 20，同一张卡上两条左缘。
     *
     * 用推导式而不是写 `12.dp`：两个来源尺寸任意一个改动，这个数自动跟上；
     * 写死就会在下次调图标档位时静默错位。
     */
    val iconGlyphInset: Dp get() = (minTouch - navigationIcon) / 2

    /**
     * 全宽顶栏和输入条的水平内边距。
     *
     * 正文左缘是 [ZdSpacing.screenEdge]。两端按钮把字形居中放在 [minTouch] 里，
     * 盒子比字形宽出 [iconGlyphInset]。内边距取两者之差，字形才和消息正文
     * 落在同一条线上；直接铺满 [ZdSpacing.screenEdge] 会让字形再往里缩一截。
     */
    val chromeEdgeInset: Dp get() = ZdSpacing.screenEdge - iconGlyphInset

    /**
     * 顶栏与书写条这两块玻璃**内部**的行缩进。两块共用这一个值，
     * 上下两端的首枚图标字形、标题 / 占位字才落在同一条竖线上（此前差 2dp / 6dp）。
     */
    val chromeRowInset: Dp get() = ZdSpacing.xxs

    /**
     * 最小可视图标：折叠箭头、可切换标记、选中勾。
     *
     * 顶栏身份区的 `chevrons-up-down` 用这一档：它是「这里可以点」的唯一提示，
     * 比它再小（旧值 11dp）在实机上就只是一团灰点，等于没有提示。
     */
    val iconSmall: Dp = 16.dp

    /**
     * 过程卡、状态行与紧凑选择器里的辅助字形。
     * 保留 16dp 布局槽的场景使用 [iconSmall]；纯行内图形降到 14dp，避免与 13sp 正文争抢重量。
     */
    val iconSupporting: Dp = 14.dp

    /** 强调图标：空状态、页头使用 24 档。 */
    val iconLarge: Dp = 24.dp

    /**
     * 上下文余量条的厚度。
     *
     * 只做"扫一眼看出紧不紧张"的示意，不承载读数，所以压到 3dp：
     * 再厚就会把紧凑事实带撑高一档，与同屏其他状态条错位。
     */
    val headroomGauge: Dp = 3.dp

    /**
     * 承载读数的进度条（安装 / 更新总进度，见 ZdProgressBar）的厚度：MIUI 式 6dp 圆头粗条。
     * 它是页面里唯一的进度读数，要一眼看得到推进，不能像页线那样细。
     */
    val progressBarHeight: Dp = 6.dp

    /**
     * 运行环境各格左侧状态记号（勾 / 点 / 空心圈）的画布尺寸。
     * 放在 24 图标槽里居中；勾、点、圈共用一个画布，视觉重量才一致（此前勾 14、点只有 3）。
     */
    val setupStatusMark: Dp = 20.dp

    /** 底部弹层拖拽把手的厚度。 */
    val dragHandleThickness: Dp = 4.dp

    /** 对话流内联身份头像：助手回合开头的那一枚，比顶栏轻一档。 */
    val avatarSmall: Dp = 24.dp

    /** 头像、模型图标；顶栏头像使用 32 档。 */
    val avatar: Dp = 32.dp

    /**
     * 紧凑方块使用 36 档。
     *
     * 三处共用同一档：抽屉头像、弹层拖拽把手宽、输入区待发附件缩略轨
     * 按角色各起一个名字会逐渐漂成三个近似值。
     */
    val compactTile: Dp = 36.dp

    /**
     * 设置行左侧统一的裸线性图标槽 = [iconLarge]（24 档）。
     *
     * 转发而不是再写一个 `24.dp`：20 那一档的名字已经是 [navigationIcon]，
     * 24 这一档的名字已经是 [iconLarge]，同一个值留两个名字就是 [icon] 那个老问题。
     *
     * 从 20 升到 24 的理由是层级而非偏好：行标题 16sp，左槽 20dp 与它几乎同重，
     * 眼睛扫下来图标和文字互相争抢，读不出「图标是从属标记、标题才是内容」。
     * 24 拉开到 1.5 倍才让左槽退回标记位。[ZdDivider] 的起始内缩是推导式
     * （`lg + settingIconTile + md`），会自动从 48 跟到 52，不需要另改。
     */
    val settingIconTile: Dp get() = iconLarge

    /** 紧凑选择器的可见高度；触控区域仍统一走 [minTouch]。 */
    val compactSelectorHeight: Dp = 32.dp

    /** 锚定菜单最多展示约六个标准触控行，更多内容在菜单内部滚动。 */
    val popupMenuMaxHeight: Dp = 264.dp
    /** 高频短菜单保持 44dp 触控行，不让六个文字选项膨胀成半屏面板。 */
    val popupItemMinHeight: Dp = minTouch
    /** 思考强度只有短标签，使用内容匹配的窄菜单。 */
    val compactMenuWidth: Dp = 144.dp
    /** 文件卡片的三个短操作，采用更窄的菜单；行热区仍至少 [minTouch]。 */
    val deliveredFileMenuWidth: Dp = 128.dp
    /** 常规单列操作菜单宽度；长命令建议另用 commandMenuWidth。 */
    val standardMenuWidth: Dp = 220.dp
    val commandMenuMinWidth: Dp = 280.dp
    val commandMenuMaxWidth: Dp = 320.dp

    /** Snackbar 在平板上不应无限拉宽；手机仍占满可用宽度。 */
    val snackbarMaxWidth: Dp = 560.dp
    /** Toast 与编辑页悬浮底栏之间的最小视觉间隔。 */
    val toastDockClearance: Dp = 96.dp

    /**
     * 输入框里待发送图片的方形预览。
     *
     * 这行注释此前写的是"已发送图片的方形缩略图"，而这个令牌的唯一调用点是
     * `ComposerAttachment`——**输入框**。已发送侧当时根本不画图，
     * 所以这句话描述的是一个不存在的东西。
     */
    val thumbnail: Dp = 64.dp

    /**
     * 已发送气泡里的图片。
     *
     * 比输入框预览大一档，因为两侧回答的是不同的问题：输入框只需要"我选中了这张"
     * （缩略图够了），而气泡里的图**是消息内容本身**，要能看清才算送达。
     * 苹果的信息应用里发出去的照片同理——它不是一枚附件角标。
     *
     * 没有取满气泡宽度：多图时仍走横向轨，等宽方图扫起来是一条整齐的带子；
     * 满宽会让两张图变成上下两块，把一条消息读成两条。
     */
    val chatImage: Dp = 120.dp

    /**
     * 已发送气泡里的**单图**。
     *
     * 单图不是附件轨上的一枚格子，是这条消息本身——用户发一张报错截图，
     * 就是为了让人和模型看清它。120dp 的格子档在这条用法上仍然读成角标，
     * 竖屏截图在中间缩成一条带。单图独享一档更大的高度，宽度仍随原图比例走
     * （见 `ThumbnailAspect`），多图才退回 [chatImage] 的等宽轨。
     */
    val chatImageLarge: Dp = 200.dp

    /** 用户消息、排队消息与其附件轨的最大宽度。 */
    val chatBubbleMaxWidth: Dp = 320.dp

    /**
     * 气泡左侧必须保留的最小留白。
     *
     * 只有 [chatBubbleMaxWidth] 一个上限时，气泡的实际内缩完全取决于屏宽：
     * 412dp 机器上剩 68dp 留白，右对齐一目了然；而 360dp 机器上只剩 16dp，
     * 长消息几乎铺满整行，与助手正文的满宽版式再也分不出来——
     * 说话人区分在最小目标机型上恰好失效（`GOAL_PROGRESS` 的验证清单里
     * 「360-375dp 小屏竖屏」正是必测项）。
     *
     * 下限与上限一起用：窄屏由它保证内缩，宽屏由上限防止气泡拉得过长。
     */
    val chatBubbleMinGutter: Dp = 32.dp

    /** Markdown 表格在窄屏中横向滚动时的最小可读宽度。 */
    val markdownTableMinWidth: Dp = 480.dp

    /** 崩溃详情等短模态正文的最大高度，避免在小屏上吞掉整个页面。 */
    val dialogBodyMax: Dp = 280.dp

    /** 用户消息气泡圆角（四角同为 20，照 Claude 手机端）。 */
    val chatBubbleRadius: Dp = 20.dp

    /** 行内代码底色圆角。 */
    val inlineCodeRadius: Dp = 5.dp

    /** 运行记号的布局框与其中呼吸圆点的直径。 */
    val runningIndicatorBox: Dp = 20.dp
    val runningIndicatorDot: Dp = 8.dp

    /** 聊天页圆形浮动按钮（回到最新）的可见直径。 */
    val chatFloatingButton: Dp = 40.dp

    // ── 聊天页（照 Claude 手机端结构，2026-09-27）──

    /** 聊天页顶栏高度：← | 居中标题 + 副标题 | ⋮。 */
    val chatTopBarHeight: Dp = 56.dp

    /** 顶栏 ⋮ 菜单、书写条 ⊕ 菜单的宽度。 */
    val chatMenuWidth: Dp = 208.dp

    /** 菜单行高下限：单行 48，带副标题时由内容自然撑开。 */
    val chatMenuItemMinHeight: Dp = 48.dp

    /** 书写条下行圆形按钮与模型药丸的可见高度；点击区由 clickable 保持最小触达。 */
    val composerControl: Dp = 36.dp

    /** 书写条上方状态小药丸的高度。 */
    val composerStatusPillHeight: Dp = 36.dp

    /** 弹层列表行高下限（单行 56；带副标题由内容撑到约 64）。 */
    val sheetRowHeight: Dp = 56.dp

    /** 弹层 / 设置列表带副标题的最小行高（64）。 */
    val sheetRowTallHeight: Dp = 64.dp

    /** 侧栏导航项最小行高，无底色。 */
    val drawerNavRowHeight: Dp = 48.dp

    /** 侧栏「最近」会话行行高（08 实测 52）：20 图标 + 17sp 单行。 */
    val drawerRecentRowHeight: Dp = 48.dp

    /** 侧栏左下首字母头像直径（→ 设置）。 */
    val drawerAvatar: Dp = 48.dp

    /** 反白「+ 新会话」药丸高度（侧栏与会话列表共用）。 */
    val newSessionPillHeight: Dp = 48.dp

    /** 会话列表卡：最小高 72、圆角 20、左侧 32 方块图标。 */
    val sessionCardMinHeight: Dp = 72.dp
    val sessionCardRadius: Dp = 20.dp
    val sessionCardIcon: Dp = 32.dp

    // ── 设置分组卡（照 Claude 手机端 07/09，2026-09-27）──

    /** 设置分组卡的最小行高：单行 52，带副标题 60；大字体时自然增长。 */
    val settingCellHeight: Dp = 52.dp
    val settingCellTallHeight: Dp = 56.dp

    /** 设置行左侧图标（20 图形）。 */
    val settingCellIcon: Dp = 20.dp

    /** 设置里的填充输入框最小高度（raised 圆角 12 框，内含标签 + 一行输入）。 */
    val settingFieldMinHeight: Dp = 52.dp

    /** 子代理角色卡里「模型 / 思考强度 / 上下文窗口」这一列标签的固定宽度：值左缘对齐成一条线。 */
    val subagentFieldLabelWidth: Dp = 80.dp

    /** 变更徽标（两段拼接的 +N / -N 小胶囊）高度（05 实测 20）。 */
    val diffBadgeHeight: Dp = 20.dp

    /** 变更徽标圆角（05 实测 6）。 */
    val diffBadgeRadius: Dp = 6.dp

    /** 步骤弹层里一步一行的固定行高（04 实测约 52）；竖细连线按这个高度算端点。 */
    val processStepRowHeight: Dp = 52.dp
    val processSummaryLabelHeight: Dp = 24.dp

    /** 步骤之间竖细连线两端离图标的留白。 */
    val processStepConnectorGap: Dp = 4.dp

    /** 详情代码区行号栏最小宽度（两位数 + 右留白）。 */
    val codeGutterMinWidth: Dp = 32.dp
    // ── chrome（顶栏 / 弹层 / 锚定菜单 / 对话框），照 Claude 手机端 07/01/04 实测 ──

    /** 通用顶栏栏身高度（不含状态栏）。 */
    val appBarHeight: Dp = 56.dp

    /** 顶栏图标图形尺寸；触控仍是 [minTouch]。 */
    val appBarIcon: Dp = 20.dp

    /** 底部弹层顶部拖拽条。 */
    val sheetHandleWidth: Dp = 36.dp
    val sheetHandleHeight: Dp = 4.dp

    /** 锚定菜单（01）宽度与行高。 */
    val anchoredMenuWidth: Dp = 230.dp
    val anchoredMenuRowMinHeight: Dp = 48.dp
    val anchoredMenuRowWithDetailMinHeight: Dp = 56.dp

    /** 锚定菜单行左右内边距（01 实测图标左缘离菜单边约 20）。 */
    val anchoredMenuRowInset: Dp = 20.dp

    /** 锚定菜单最多约七行（01 实测六行 + 余量），更多在菜单内滚动。 */
    val anchoredMenuMaxHeight: Dp = 392.dp

    /** 轻提示药丸的最小高度。 */
    val toastPillMinHeight: Dp = 44.dp
}
