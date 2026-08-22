package dev.termish.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import dev.termish.generated.resources.Res
import dev.termish.generated.resources.fira_code_bold
import dev.termish.generated.resources.fira_code_regular
import dev.termish.generated.resources.jetbrains_mono_bold
import dev.termish.generated.resources.jetbrains_mono_regular
import dev.termish.generated.resources.pt_mono_regular
import dev.termish.generated.resources.source_code_pro_bold
import dev.termish.generated.resources.source_code_pro_regular
import dev.termish.generated.resources.ubuntu_mono_bold
import dev.termish.generated.resources.ubuntu_mono_regular
import org.jetbrains.compose.resources.Font

/**
 * 终端等宽字体选项。每个字体必须带 regular + bold 两个字重
 * （终端有粗体渲染），且为等宽字体（度量一致性是终端正确渲染的前提）。
 */
@Immutable
enum class TerminalFont(
    val id: String,
    val label: String,
) {
    JETBRAINS("jetbrains", "JetBrains Mono"),
    FIRA("fira", "Fira Code"),
    SOURCE_CODE("sourcecode", "Source Code Pro"),
    UBUNTU("ubuntu", "Ubuntu Mono"),
    PT_MONO("ptmono", "PT Mono"),
    ;

    companion object {
        fun byId(id: String): TerminalFont = entries.firstOrNull { it.id == id } ?: JETBRAINS
    }
}

/**
 * 当前终端字体（AppRoot 按设置提供）。monospaceFontFamily() 从它读取，
 * 切换字体后全树（画布/工具栏/页头）自动重组。
 */
val LocalTerminalFont = staticCompositionLocalOf { TerminalFont.JETBRAINS }

/**
 * 终端等宽字体：使用随应用打包的字体（OFL / Ubuntu Font Licence）。
 *
 * 为什么不能依赖系统字体：
 * - Android 上 `FontFamily.Monospace` / `Typeface.MONOSPACE` 在部分 OEM
 *   （ColorOS、MIUI、EMUI 等）会解析到并非严格等宽的字体，且不同机型的
 *   字形宽度不一致，导致按"格子宽度"绘制的终端文字/光标整体错位
 *   （典型现象：模拟器正常，真机光标跑到行尾之外）。
 * - iOS 各版本系统 monospace 字体度量也有差异。
 *
 * 内置字体后，测量（行列计算）与绘制用的是同一个字体文件，三端行为一致。
 */
@Composable
fun monospaceFontFamily(): FontFamily {
    val font = LocalTerminalFont.current
    return when (font) {
        TerminalFont.JETBRAINS ->
            FontFamily(
                Font(Res.font.jetbrains_mono_regular, FontWeight.Normal),
                Font(Res.font.jetbrains_mono_bold, FontWeight.Bold),
            )
        TerminalFont.FIRA ->
            FontFamily(
                Font(Res.font.fira_code_regular, FontWeight.Normal),
                Font(Res.font.fira_code_bold, FontWeight.Bold),
            )
        TerminalFont.SOURCE_CODE ->
            FontFamily(
                Font(Res.font.source_code_pro_regular, FontWeight.Normal),
                Font(Res.font.source_code_pro_bold, FontWeight.Bold),
            )
        TerminalFont.UBUNTU ->
            FontFamily(
                Font(Res.font.ubuntu_mono_regular, FontWeight.Normal),
                Font(Res.font.ubuntu_mono_bold, FontWeight.Bold),
            )
        // PT Mono 仅单字重：Bold 请求由 regular 合成（Compose 伪粗），可接受
        TerminalFont.PT_MONO ->
            FontFamily(
                Font(Res.font.pt_mono_regular, FontWeight.Normal),
                Font(Res.font.pt_mono_regular, FontWeight.Bold),
            )
    }
}

/**
 * 宽字符（中文等 CJK）使用的字体族。
 *
 * JetBrains Mono 不含 CJK 字形。Android/桌面端系统会自动回退，返回默认族即可；
 * iOS 上 CMP 对自定义字体的缺字回退不可靠（FontFamily.Default/SansSerif 都无效），
 * 必须显式加载内置的 Noto Sans SC 才能渲染中文。
 */
@Composable
expect fun cjkFontFamily(): FontFamily
