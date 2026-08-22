package dev.termish.term

/** 终端颜色：以 0xRRGGBB 编码的 Int。默认前景/背景用负哨兵值。 */
const val DEFAULT_FG = -1
const val DEFAULT_BG = -2

/** 光标颜色未设置（用主题默认色）的哨兵值。 */
const val DEFAULT_CURSOR = -3

/** ARGB Long → 0xRRGGBB Int（终端协议应答用，如 OSC 10/11/12 的颜色查询）。 */
fun argbToRgb(c: Long): Int {
    val r = ((c shr 16) and 0xff).toInt()
    val g = ((c shr 8) and 0xff).toInt()
    val b = (c and 0xff).toInt()
    return (r shl 16) or (g shl 8) or b
}

object TerminalPalette {
    /** 16 个基础 ANSI 颜色（VGA 标准）。 */
    val BASIC_16: IntArray =
        intArrayOf(
            0x000000, // 0 black
            0xcd0000, // 1 red
            0x00cd00, // 2 green
            0xcdcd00, // 3 yellow
            0x0000ee, // 4 blue
            0xcd00cd, // 5 magenta
            0x00cdcd, // 6 cyan
            0xe5e5e5, // 7 white
            0x7f7f7f, // 8 bright black
            0xff0000, // 9 bright red
            0x00ff00, // 10 bright green
            0xffff00, // 11 bright yellow
            0x5c5cff, // 12 bright blue
            0xff00ff, // 13 bright magenta
            0x00ffff, // 14 bright cyan
            0xffffff, // 15 bright white
        )

    private val CUBE = intArrayOf(0, 95, 135, 175, 215, 255)

    /** xterm 256 色板：16 基础色 + 216 色立方体 + 24 灰度。 */
    val PALETTE_256: IntArray =
        IntArray(256) { i ->
            when {
                i < 16 -> BASIC_16[i]
                i < 232 -> {
                    val n = i - 16
                    val r = CUBE[n / 36]
                    val g = CUBE[(n % 36) / 6]
                    val b = CUBE[n % 6]
                    rgb(r, g, b)
                }
                else -> {
                    val v = 8 + (i - 232) * 10
                    rgb(v, v, v)
                }
            }
        }

    fun rgb(
        r: Int,
        g: Int,
        b: Int,
    ): Int = ((r and 0xff) shl 16) or ((g and 0xff) shl 8) or (b and 0xff)

    fun red(c: Int): Int = (c ushr 16) and 0xff

    fun green(c: Int): Int = (c ushr 8) and 0xff

    fun blue(c: Int): Int = c and 0xff
}

/** 单元格属性位掩码。 */
object CellAttr {
    const val BOLD = 1
    const val DIM = 1 shl 1
    const val ITALIC = 1 shl 2
    const val UNDERLINE = 1 shl 3
    const val BLINK = 1 shl 4
    const val INVERSE = 1 shl 5
    const val HIDDEN = 1 shl 6
    const val STRIKE = 1 shl 7
}

/**
 * 简易字符宽度（列数）：1 或 2。
 * 覆盖常用全角 / CJK / 表情符号区间，足够移动端 SSH 场景使用。
 */
object CharWidth {
    fun wcwidth(cp: Int): Int {
        if (cp < 0x20) return 1 // C0 控制字符按 1 处理
        if (cp == 0x200B) return 1 // zero-width space
        if (cp in 0x0300..0x036F) return 1 // 组合字符简化处理
        if (cp in 0x1100..0x115F) return 2 // Hangul Jamo
        if (cp in 0x2E80..0x303E) return 2 // CJK 部首/标点
        if (cp in 0x3041..0x33FF) return 2 // 日文假名、CJK 兼容
        if (cp in 0x3400..0x4DBF) return 2 // CJK 扩展 A
        if (cp in 0x4E00..0x9FFF) return 2 // CJK 统一表意文字
        if (cp in 0xA000..0xA4CF) return 2 // Yi
        if (cp in 0xAC00..0xD7A3) return 2 // Hangul 音节
        if (cp in 0xF900..0xFAFF) return 2 // CJK 兼容表意
        if (cp in 0xFE10..0xFE19) return 2 // 竖排标点
        if (cp in 0xFE30..0xFE6F) return 2 // CJK 兼容形式
        if (cp in 0xFF00..0xFF60) return 2 // 全角形式
        if (cp in 0xFFE0..0xFFE6) return 2
        if (cp in 0x1F300..0x1FAFF) return 2 // 表情符号
        if (cp in 0x1F000..0x1F02F) return 2
        if (cp in 0x20000..0x2FFFD) return 2 // CJK 扩展 B+
        if (cp in 0x30000..0x3FFFD) return 2
        return 1
    }
}
