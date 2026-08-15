package dev.mssh.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import dev.mssh.generated.resources.Res
import dev.mssh.generated.resources.noto_sans_sc_regular
import org.jetbrains.compose.resources.Font

/** iOS：显式加载内置 Noto Sans SC（覆盖全部常用汉字），不依赖 CMP 的字形回退。 */
@Composable
actual fun cjkFontFamily(): FontFamily = FontFamily(Font(Res.font.noto_sans_sc_regular))
