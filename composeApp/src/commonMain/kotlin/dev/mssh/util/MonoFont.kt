package dev.mssh.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import dev.mssh.generated.resources.Res
import dev.mssh.generated.resources.jetbrains_mono_bold
import dev.mssh.generated.resources.jetbrains_mono_regular
import org.jetbrains.compose.resources.Font

/**
 * 终端等宽字体：使用随应用打包的 JetBrains Mono（OFL 协议）。
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
fun monospaceFontFamily(): FontFamily = FontFamily(
    Font(Res.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(Res.font.jetbrains_mono_bold, FontWeight.Bold),
)

/**
 * 宽字符（中文等 CJK）使用的字体族。
 *
 * JetBrains Mono 不含 CJK 字形。Android/桌面端系统会自动回退，返回默认族即可；
 * iOS 上 CMP 对自定义字体的缺字回退不可靠（FontFamily.Default/SansSerif 都无效），
 * 必须显式加载内置的 Noto Sans SC 才能渲染中文。
 */
@Composable
expect fun cjkFontFamily(): FontFamily
