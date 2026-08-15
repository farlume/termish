package dev.mssh.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily

/** Android/Desktop：系统字体自带 CJK 回退。 */
@Composable
actual fun cjkFontFamily(): FontFamily = FontFamily.Default
