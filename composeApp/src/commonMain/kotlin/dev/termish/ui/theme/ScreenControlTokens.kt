package dev.termish.ui.theme

import androidx.compose.ui.graphics.Color

/** 远程画面悬浮控制器视觉与交互 token。 */
object ScreenControlTokens {
    val PermissionBackground = Color(0xD9B45309)
    val PermissionText = Color(0xE6FFFFFF)

    val FloatingBackground = Color(0xA6000000)
    val MouseSurface = Color(0xD9F3F4F6)
    val MouseStroke = Color(0xCC5F6368)
    val MouseGlyph = Color(0xCC666A70)
    val CloseBackground = Color(0xB31D2025)
    val CursorFill = Color(0xFF111318)
    val CursorOutline = Color.White

    const val SCROLL_DIRECTION_UP = 1
    const val SCROLL_DIRECTION_DOWN = -1
}
