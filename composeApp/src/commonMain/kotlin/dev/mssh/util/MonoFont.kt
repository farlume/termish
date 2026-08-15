package dev.mssh.util

import androidx.compose.ui.text.font.FontFamily

/**
 * 真正的等宽字体族。
 *
 * Android 上 `FontFamily.Monospace` 在部分 OEM（如 ColorOS）会解析到非等宽字体，
 * 导致字符宽度测量失真（"W" 宽、"i" 窄），终端行列/光标全部错位。
 * 这里用系统保证等宽的 Typeface.MONOSPACE。
 */
expect fun monospaceFontFamily(): FontFamily
