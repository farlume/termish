package dev.termish.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Typeface as SkiaTypeface
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle

/**
 * iOS：CJK 显式按名取系统 PingFang SC。
 *
 * 不能用 FontFamily.Default：CMP 1.8.1 Canvas 绘制路径的缺字回退不可靠
 * （宽字符 run 指到 Default 仍乱码）；资源字体（Noto Sans SC）在 iOS 的
 * 异步加载也有时序问题（曾导致整段中文乱码）。FontMgr 走 CoreText 系统
 * 字体库，同步、确定性；iOS 9+ 必有 PingFang SC。
 *
 * 降级链：legacyMakeTypeface → matchFamilyStyle → Default。
 * 取不到 PingFang（理论上仅极端环境）回退 Default：字体问题不该让
 * app 在组合期崩溃，降级后最多回到乱码，行为不劣于修复前。
 */
private val cjkFamily: FontFamily by lazy {
    runCatching {
        val typeface = FontMgr.default.legacyMakeTypeface("PingFang SC", FontStyle.NORMAL)
            ?: FontMgr.default.matchFamilyStyle("PingFang SC", FontStyle.NORMAL)
        typeface?.let { FontFamily(SkiaTypeface(it)) } ?: FontFamily.Default
    }.getOrDefault(FontFamily.Default)
}

@Composable
actual fun cjkFontFamily(): FontFamily = cjkFamily
