package dev.mssh.term

/**
 * 文本选择：基于缓冲的绝对行坐标。用于终端内的长按/拖动选择与复制。
 */
class TerminalSelection(private val buffer: TerminalBuffer) {

    var startRow = 0
        private set
    var startCol = 0
        private set
    var endRow = 0
        private set
    var endCol = 0
        private set

    var isActive: Boolean = false
        private set

    fun start(row: Int, col: Int) {
        startRow = row
        startCol = col
        endRow = row
        endCol = col
        isActive = true
    }

    fun extend(row: Int, col: Int) {
        endRow = row
        endCol = col
    }

    fun clear() {
        isActive = false
    }

    fun contains(row: Int, col: Int): Boolean {
        if (!isActive) return false
        val (r1, c1, r2, c2) = normalized()
        if (row < r1 || row > r2) return false
        val from = if (row == r1) c1 else 0
        val to = if (row == r2) c2 else buffer.cols - 1
        return col in from..to
    }

    private fun normalized(): SelectionBounds {
        return if (startRow < endRow || (startRow == endRow && startCol <= endCol)) {
            SelectionBounds(startRow, startCol, endRow, endCol)
        } else {
            SelectionBounds(endRow, endCol, startRow, startCol)
        }
    }

    fun selectedText(): String {
        if (!isActive) return ""
        val (r1, c1, r2, c2) = normalized()
        val lines = ArrayList<String>()
        for (r in r1..minOf(r2, buffer.totalLines() - 1)) {
            if (r < 0) continue
            val line = buffer.absLine(r)
            val from = if (r == r1) c1.coerceIn(0, buffer.cols - 1) else 0
            val to = if (r == r2) c2.coerceIn(0, buffer.cols - 1) else buffer.cols - 1
            if (from > to) { lines.add(""); continue }
            val sb = StringBuilder()
            for (c in from..to) {
                val cell = line.cells[c]
                if (cell.isWideTail) continue
                sb.appendCodePointSafely(cell.codePoint)
            }
            lines.add(sb.toString().trimEnd())
        }
        return lines.joinToString("\n").trimEnd()
    }

    private data class SelectionBounds(val r1: Int, val c1: Int, val r2: Int, val c2: Int)
}

private fun StringBuilder.appendCodePointSafely(cp: Int) {
    if (cp <= 0xFFFF) {
        append(cp.toChar())
    } else {
        val x = cp - 0x10000
        append((0xD800 + (x shr 10)).toChar())
        append((0xDC00 + (x and 0x3FF)).toChar())
    }
}
