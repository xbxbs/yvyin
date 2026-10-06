package com.xuebi.zhongduan.ui.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.xuebi.zhongduan.ui.theme.Tone

/**
 * 语义颜色唯一入口。视觉主体保持中性，单一强调色表达交互与活动状态。
 *
 * ## 只有一套外观
 *
 * 「流光玻璃」开关已移除，相关代码也已全部删除（枚举、CompositionLocal、
 * 色场实现、以及散在 13 处的 `if (ambientGlass)` 分支）。
 *
 * 一个外观开关的真实代价就写在这个文件的历史里：每一项颜色都得是
 * `if (ambientGlass) A else B`，任何一次调色都要在两边各判断一次。
 * 两套都得维护，实际只有一套被认真看过。
 * 结果不是"用户多了个选择"，是两套都做不好。
 *
 * 现在中性一族全部取自 [zdPalette]（见 `DESIGN_LANGUAGE.md` §1、§2），
 * 四级台阶明确：ground → raised → chip → nested，容器一律不描边。
 * 固定 chrome（顶栏、底栏、抽屉、弹层）仍然是真毛玻璃——那是**材质分工**，
 * 不是可切换的主题。
 *
 * ## 不再读组件库的配色
 *
 * 这个类此前还揣着一个 `scheme: Colors` 构造参数，值来自 `MiuixTheme.colorScheme`。
 * 颜色搬进 [ZdPalette] 之后它一处也没被读过——一个谁都不看、却让人以为
 * 「颜色还有第二个来源」的字段。字段已删，`Colors` / `MiuixTheme` 两个 import
 * 一并去掉：现在从这个文件的 import 列表就能一眼看出颜色只有一个源。
 */
class ZdColors internal constructor(
    val dark: Boolean
) {
    private val palette: ZdPalette get() = zdPalette(dark)

    val bg: Color get() = palette.ground
    val pageBackground: Color get() = bg

    val inkStrong: Color get() = palette.inkStrong
    val ink: Color get() = palette.ink
    val inkSoft: Color get() = palette.inkSoft
    val inkFaint: Color get() = palette.inkFaint

    @Deprecated("文字只有四档", ReplaceWith("inkFaint"))
    val inkGhost: Color get() = inkFaint

    val line: Color get() = palette.line
    val lineSoft: Color get() = palette.line.copy(alpha = Tone.Glass.BORDER)

    /**
     * 卡片顶缘的镜面高光；只有深色下非零。
     *
     * 深色里投影是无效的（黑影打在深底上看不见），浮起感来自光扫过上棱。
     * 浅色则相反：纸面上本就没有这道高光，卡片靠底下那一小片弥散影浮起
     * （见 [ZdSize.cardLift]），所以这里返回全透明——同一个调用点两种模式
     * 各自拿到正确的那一种，不需要在界面层写 if。
     */
    val cardSpecular: Color
        get() = if (dark) Color.White.copy(alpha = Tone.Lift.SPECULAR) else Color.Transparent

    /**
     * 浮动玻璃的 1px 反光边（用户钉死：重反光轻模糊）。
     *
     * 上缘亮、下缘几乎消失：读作光扫过玻璃的棱，而不是一圈框线。
     * 浅色纸面上白光看不见，改由页线色在下缘收一道轮廓。
     */
    val glassRimHighlight: Color
        get() = if (dark) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.92f)
    val glassRimShade: Color
        get() = if (dark) Color.White.copy(alpha = 0.03f) else palette.line.copy(alpha = 0.8f)

    /** 深色轻提亮、浅色轻压暗，卡内轮廓按钮共用。 */
    val composerControlFill: Color
        get() = (if (dark) Color.White else Color.Black).copy(alpha = Tone.Chrome.COMPOSER_CONTROL_FILL)
    val composerControlBorder: Color
        get() = palette.inkStrong.copy(alpha = Tone.Chrome.COMPOSER_CONTROL_BORDER)

    val accent: Color get() = palette.accent
    val accentBorder: Color get() = accent.copy(alpha = Tone.Accent.BORDER)
    val onAccent: Color get() = palette.onAccent
    val accentContainer: Color get() = palette.accentContainer
    val onAccentContainer: Color get() = palette.onAccentContainer
    /**
     * 正在发生的事：运行中、流式输出、实时状态点。
     *
     * 此前它就是 `accent` 的别名，于是"可以点的按钮"和"正在跑"同色——
     * 两件完全不同的事说成一件，而"还在不在动"恰恰是挂机回来的人第一眼要找的。
     * 与主题蓝保持同一色相，但降低一点透明度，避免状态行压过正文。
     */
    val live: Color get() = palette.live
    val activityText: Color get() = palette.live.copy(alpha = 0.86f)

    /** 比页面更深的面：会话列表卡（Claude Code 首页）。 */
    val sunken: Color get() = palette.sunken
    /** 反白主操作：新会话药丸、发送键。 */
    val inverseFill: Color get() = palette.inverse
    val onInverseFill: Color get() = palette.onInverse
    /** 变更徽标：保持清晰的绿 / 红，不与普通状态的低饱和色混用。 */
    val diffAdd: Color get() = palette.diffAdd
    val diffAddFill: Color get() = palette.diffAddFill
    val diffDel: Color get() = palette.diffDel
    val diffDelFill: Color get() = palette.diffDelFill

    val ok: Color get() = palette.ok
    val okBg: Color get() = ok.copy(alpha = Tone.FILL_SUBTLE)
    val okBorder: Color get() = ok.copy(alpha = Tone.Accent.BORDER)
    val err: Color get() = palette.err
    val errBg: Color get() = err.copy(alpha = Tone.FILL_SUBTLE)
    val errBorder: Color get() = err.copy(alpha = Tone.Accent.BORDER)
    val warn: Color get() = palette.warn
    val online: Color get() = ok
    val identityGradient: Brush get() = Brush.linearGradient(listOf(accent, accent))
    val identityGlow: Color get() = accent
    val onIdentity: Color get() = onAccent

    @Deprecated("改用 identityGradient", ReplaceWith("identityGradient"))
    val userBubble: Brush get() = identityGradient
    val userBubbleBorder: Color get() = accent.copy(alpha = Tone.Accent.BORDER)

    @Deprecated("改用 onIdentity", ReplaceWith("onIdentity"))
    val onUserBubble: Color get() = onIdentity

    // ---- 四级台阶（DESIGN_LANGUAGE §2）----
    // ground(bg) → raised → chip → nested。语义名保留旧写法以免一次改动几百个调用点，
    // 但取值全部落到这四级上，不再各自映射到 Miuix 的六七个 surfaceContainer* 档位——
    // 那些档位彼此差值极小，叠在一起根本分不出来，等于有台阶而无层次。
    val surfaceStandard: Color get() = palette.raised
    val surfaceNested: Color get() = palette.nested
    val surfaceDeep: Color get() = palette.nested
    val drawer: Color get() = palette.drawer
    val drawerPanel: Color get() = palette.drawerPanel
    val drawerSelected: Color get() = palette.drawerSelected
    val contentGroupFill: Color get() = palette.raised
    val fieldFill: Color get() = palette.chip
    val selectedFill: Color get() = palette.nested
    val glassFill: Color get() = palette.raised
    val glassStrongFill: Color get() = palette.nested

    /**
     * chrome 玻璃面的表面色，**不透明**。
     *
     * 不透明是 Haze 的语义要求：`backgroundColor` 指"半透明源内容背后的实色"。
     * 旧的 `chromeBase` 传了 alpha 0.62 的半透明色进去，模糊采样到透明像素，
     * 于是模糊淡到看不见 —— 遮罩厚度由 `glass` 规格层单独决定，不混在这个色里。
     */
    val chromeSurface: Color get() = palette.raised
    val chromeFallback: Color get() = chromeSurface.copy(alpha = Tone.Chrome.FALLBACK)
    val strokeSoft: Color get() = lineSoft
    val strokeStrong: Color get() = palette.line.copy(alpha = Tone.Solid.BORDER)
    val readabilityScrim: Color get() = chromeSurface.copy(alpha = Tone.Glass.READABILITY_SCRIM)

    /**
     * 说话人身份底色：用户气泡。
     *
     * 作者身份既不是动作也不是状态，按 §4 不配拿满强调色；
     * 容器档是同一族里最轻的一档，够把说话人分出来，
     * 又不会让用户的一句话画得比助手的答案还响。
     */
    val identityFill: Color get() = accentContainer

    /** 身份底色上的文字。 */
    val onIdentityFill: Color get() = onAccentContainer

    val chipFill: Color get() = palette.chip
    val chipBorder: Color get() = lineSoft
    val pressedFill: Color get() = accent.copy(alpha = Tone.FILL_SUBTLE)

    val inputFill: Color get() = fieldFill
    val badgeBg: Color get() = surfaceNested
    val badgeBorder: Color get() = lineSoft
    val badgeText: Color get() = inkSoft
    val toolIconBg: Color get() = surfaceNested
    val toolIconBorder: Color get() = lineSoft
    val spinnerTrack: Color get() = surfaceNested
    val shimmerDim: Color get() = inkFaint
    val shimmerBright: Color get() = inkStrong

    val codeKey: Color get() = accent
    val codeString: Color get() = live
    val codeNumber: Color get() = warn

    // 旧极光属性只为源码兼容；背景实现已静态化，不再消费这些值。
    @Deprecated("全屏极光已停用", ReplaceWith("bg")) val auroraA: Color get() = bg
    @Deprecated("全屏极光已停用", ReplaceWith("bg")) val auroraB: Color get() = bg
    @Deprecated("全屏极光已停用", ReplaceWith("bg")) val auroraC: Color get() = bg
    @Deprecated("全屏极光已停用") val auroraAlpha: Float get() = 0f
}

val zdColors: ZdColors
    @Composable
    @ReadOnlyComposable
    get() = ZdColors(dark = isSystemInDarkTheme())
