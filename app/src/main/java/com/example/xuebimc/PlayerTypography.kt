package com.example.xuebimc

import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * Local libraries contain arbitrary titles, so a bundled subset font would mix glyph sources
 * inside one string. Use the system faces: every CJK glyph then shares one design per weight.
 */
internal object PlayerTypography {
    val latin = FontFamily(Font(DeviceFontFamilyName("sans-serif"), FontWeight.Normal))
    val chinese = latin
    val medium = FontFamily(Font(DeviceFontFamilyName("sans-serif-medium"), FontWeight.Normal))
    val bold = FontFamily(Font(DeviceFontFamilyName("sans-serif"), FontWeight.Bold))

    fun isChinese(text: String): Boolean = text.any { it.code in 0x2E80..0x9FFF || it.code in 0xF900..0xFAFF }

    @Suppress("UNUSED_PARAMETER")
    fun familyFor(text: String, medium: Boolean = false): FontFamily = if (medium) this.medium else latin
}
