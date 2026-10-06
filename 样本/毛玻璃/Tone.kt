package com.xuebi.zhongduan.ui.theme

/**
 * 统一表面色调与透明度规范（设计语言层）。
 *
 * 立这一层的原因，和 [Motion] 完全一样：此前 `copy(alpha = …)` 散落 88 处、取值 39 种，
 * 而且**同一个语义角色各写各的数字** ——
 * 玻璃着色出现过 0.30/0.36/0.38/0.42/0.46/0.50/0.55 七种，
 * 玻璃描边 0.32/0.34/0.36/0.38/0.42 五种，
 * 实心描边 0.40/0.46/0.50/0.52/0.65 五种，
 * 卡片填充 0.72/0.82/0.84/0.92 四种。
 * 同类卡片在不同界面深浅不一，这是 [Motion] 注释里说的"廉价感"最后一块来源。
 *
 * [Motion] 已经把时长与圆角收敛掉，这里补上颜色这一层：
 * 界面只引用语义常量，不再自己写透明度数字。
 *
 * 命名按"玻璃态 / 不透明态 / 强调 / 内容"四组，对应界面里真实存在的四种用法。
 */
object Tone {

    /** 页面背景与系统遮罩。 */
    object Background {
        const val WALLPAPER_SCRIM_TOP = 0.18f
        const val WALLPAPER_SCRIM_BOTTOM = 0.42f
        /** 同窗口模态面板打开时，隔离底层交互但保留背景材质可见。 */
        const val MODAL_SCRIM = 0.54f
        /**
         * 纸面渐隐带的终点（状态栏下方那段）。用「同色、零不透明」而不是 Color.Transparent：
         * 后者是透明黑，渐变插值到一半会在浅色纸面上发灰。
         */
        const val FEATHER_END = 0f
    }

    /**
     * 玻璃态（采样源可用且平台支持模糊）：Surface 本身透明，由 haze 上色，
     * 这里的值是给 haze 的着色强度，不是控件不透明度。
     */
    object Glass {
        /** 常规卡片着色。 */
        const val TINT = 0.48f
        /** 主色/强调卡片着色，比常规略淡，避免主色在毛玻璃上糊成一片。 */
        const val TINT_ACCENT = 0.44f
        /**
         * 需要压住下层内容的容器：顶栏、底栏、抽屉、弹层。
         * 比常规卡片重，否则滚动内容从玻璃后面透上来会干扰这些常驻控件的可读性。
         */
        const val TINT_DEEP = 0.62f
        /** 玻璃态描边：背景已有层次，描边只需勾边。 */
        const val BORDER = 0.36f
        /** 文档阅读区的低透明遮罩；压住复杂壁纸，但仍保留玻璃背景的存在感。 */
        const val READABILITY_SCRIM = 0.22f
    }

    /**
     * 导航 chrome 的语义色调。
     *
     * 毛玻璃的模糊半径与遮罩厚度已移交 `ui/designsystem/glass` 纯规格层，
     * 因为那些值需要**算总和**才知道对不对：旧的 BASE 0.62 叠 TINT 0.34 再叠
     * FALLBACK 0.74，合成后约 0.93 不透明，模糊被压得完全看不见。
     * 现在只有"无模糊能力时的实心回退"还留在这里。
     */
    object Chrome {
        /** 不支持运行时模糊时的实心回退表面。 */
        const val FALLBACK = 0.74f
        /** 输入卡薄雾：深色提亮，浅色轻压暗；背景留给模糊来处理。 */
        const val COMPOSER_TINT = 0.06f
        /** 无模糊时用页面底色保护可读性，避免黑色回退制造深色洞。 */
        const val COMPOSER_FALLBACK = 0.94f
        /** 卡内按钮的淡底和轮廓，避免实色药丸压住玻璃。 */
        const val COMPOSER_CONTROL_FILL = 0.06f
        const val COMPOSER_CONTROL_BORDER = 0.36f
    }

    /** 不透明态（关闭玻璃）：实心表面与描边。 */
    object Solid {
        /** 顶栏、主卡片填充。留一点透明度，滚动时下层内容仍有若隐若现的层次。 */
        const val FILL = 0.90f
        /** 嵌套/次级卡片填充，比 [FILL] 淡，用来区分层级。 */
        const val FILL_NESTED = 0.76f
        /** 常规描边。 */
        const val BORDER = 0.48f
    }

    /**
     * 卡片浮起。
     *
     * ## 深色和浅色靠的不是同一种物理
     *
     * 卡片此前是**零阴影、零描边**，浮起全压在一个 1.2:1 的填充台阶上。
     * 台阶本身没错（和 Apple 的分组底↔卡片同档），但只有台阶的矩形读起来是
     * "一块颜色略不同的方块"，不是一张浮起来的卡——这就是"默认感"的来源。
     *
     * 补的不是 Material 那种有方向的落影：
     *
     * - **浅色**：卡片浮在纸上，光从上方来，底下有一小片弥散的影。用极低的
     *   elevation 就够；再大就变成 Material 的硬投影，和苹果澎湃都不像。
     * - **深色**：黑影打在深底上什么都看不见（这是深色 UI 最常见的假动作）。
     *   真正的线索是**顶缘一道镜面高光**：光扫过卡片上棱。iOS 深色卡片、
     *   macOS 深色窗口、澎湃深色卡片用的都是这个，不是投影。
     *
     * 高光不是描边（§2）。描边是四边等亮的**分隔线**；这里是单侧的**光照线索**，
     * 用垂直渐变让亮度只落在上棱、侧边迅速衰减、底边为零。
     */
    object Lift {
        /** 深色下顶缘镜面高光的强度。再高就从"受光"变成"描了一道白边"。 */
        const val SPECULAR = 0.075f

        /**
         * 高光渐变衰减到零的位置（占容器高度）。
         *
         * 只给上棱那一线，不是给整条侧边刷渐变：0.25 意味着到 1/4 高度就完全消失，
         * 侧边只在最靠上的一小段带一点余光，符合光从正上方来的方向。
         */
        const val SPECULAR_FADE = 0.25f
    }

    /** 强调轮廓：选中态、主色与错误色的描边。 */
    object Accent {
        const val BORDER = 0.28f
        /** 玻璃态下强调描边要略重才看得见。 */
        const val BORDER_GLASS = 0.34f
    }

    /** 内容层级：文字与图标的次级、禁用态。 */
    object Content {
        /** 固定运行圆环的背景轨道。 */
        const val RUNNING_TRACK = 0.2f
        /** 正文压一档：用在彩色容器上的正文，纯白/纯黑会太硬。 */
        const val SOFT = 0.80f
        /** 次级说明文字。优先用 onSurfaceVariant 令牌，确实需要再压一档时才用这个。 */
        const val MUTED = 0.62f
        /** 禁用态。 */
        const val DISABLED = 0.38f
    }

    /** 只服务于文字与移动高光的低亮度光学层，禁止用作容器填充。 */
    object Light {
        const val TITLE_GLOW = 0.10f
        const val TITLE_GLOW_RADIUS = 2.5f
        const val THINKING_GLOW = 0.14f
        const val THINKING_GLOW_RADIUS = 3f
        const val SHIMMER_ENABLED_PEAK = 0.30f
        const val SHIMMER_DISABLED_PEAK = 0.12f
        const val SHIMMER_SHOULDER_FACTOR = 0.45f
        const val TRANSPARENT = 0f
    }

    /** 极淡填充：斑马纹、行内代码底色这类"几乎看不见但要有"的层。 */
    const val FILL_FAINT = 0.06f
    /** 行内代码等需要比斑马纹略清晰、但不能形成独立卡片的底色。 */
    const val FILL_SUBTLE = 0.10f
}
