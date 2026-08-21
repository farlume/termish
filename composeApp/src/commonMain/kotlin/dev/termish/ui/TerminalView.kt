package dev.termish.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import dev.termish.term.CellAttr
import dev.termish.term.DEFAULT_CURSOR
import dev.termish.term.DEFAULT_BG
import dev.termish.term.DEFAULT_FG
import dev.termish.term.TerminalBuffer
import dev.termish.term.TerminalCell
import dev.termish.term.TerminalLine
import dev.termish.term.TerminalPalette
import dev.termish.ui.theme.TerminalTheme
import dev.termish.util.cjkFontFamily
import dev.termish.util.monospaceFontFamily

// 基础 16 色值 → ANSI 索引的预计算映射（替代每帧线性 indexOf）
private val BASIC_16_INDEX: Map<Int, Int> =
    TerminalPalette.BASIC_16.mapIndexed { i, v -> v to i }.toMap()

private fun cellColor(c: Int, theme: TerminalTheme): Color {
    val idx = BASIC_16_INDEX[c]
    return if (idx != null) theme.ansi(idx) else Color(0xFF000000.toInt() or c)
}

/** 按背景亮度取黑/白前景，保证「远端只设底色、留默认前景」时叠加场景可读。 */
private fun contrastTextColor(bg: Color): Color {
    val l = bg.red * 0.299f + bg.green * 0.587f + bg.blue * 0.114f
    return if (l >= 0.5f) Color.Black else Color.White
}

private fun cellColors(cell: TerminalCell, theme: TerminalTheme): Pair<Color, Color> {
    // xterm 惯例：BOLD + 基本 8 色 → 提亮到 8-15
    val fgIdx = if (cell.attrs and CellAttr.BOLD != 0 && cell.fg in 0..7) cell.fg + 8 else cell.fg
    val bg = if (cell.bg == DEFAULT_BG) theme.background() else cellColor(cell.bg, theme)
    val fg = when {
        cell.fg != DEFAULT_FG -> cellColor(fgIdx, theme)
        // 远端只设了底色（代码块/高亮区），默认前景按底色亮度自动取黑/白，
        // 避免浅色主题下深底+深字渲染成黑色块
        cell.bg != DEFAULT_BG -> contrastTextColor(bg)
        else -> theme.foreground()
    }
    return if (cell.attrs and CellAttr.INVERSE != 0) Pair(bg, fg) else Pair(fg, bg)
}

private fun runFontWeight(attrs: Int): FontWeight? =
    if (attrs and CellAttr.BOLD != 0) FontWeight.Bold else null

/**
 * 仅可作语句调用（追加语义两端一致）：JVM 上被 java.lang.StringBuilder 同名
 * 成员遮蔽（死代码），Kotlin/Native 上才真正生效。严禁用于表达式求值——
 * 本函数返回 Unit，iOS 上会拿到 "kotlin.Unit"（见 codePointToString 注释）。
 */
private fun StringBuilder.appendCodePoint(cp: Int) {
    if (cp <= 0xFFFF) append(cp.toChar())
    else {
        val x = cp - 0x10000
        append((0xD800 + (x shr 10)).toChar())
        append((0xDC00 + (x and 0x3FF)).toChar())
    }
}

/**
 * 码点 → String。不能写成 StringBuilder().appendCodePoint(cp).toString()：
 * Kotlin/Native 的 StringBuilder 没有公开的 appendCodePoint 成员（stdlib 里仅有
 * internal 实现），会解析到本文件返回 Unit 的私有扩展 → Unit.toString()，
 * iOS 上每个宽字符都渲染成字面串 "kotlin.Unit"（中文乱码根因）；JVM 则因
 * java.lang.StringBuilder 成员遮蔽私有扩展而恰好正确——平台语义分叉，禁用。
 */
private fun codePointToString(cp: Int): String = when {
    cp <= 0xFFFF -> cp.toChar().toString()
    else -> {
        val x = cp - 0x10000
        val hi = 0xD800 + (x shr 10)
        val lo = 0xDC00 + (x and 0x3FF)
        "${hi.toChar()}${lo.toChar()}"
    }
}

/** 一行内一个测量好的文本 run：x 为行内像素偏移，y 由绘制时行位置决定。 */
private class CachedTextRun(val layout: androidx.compose.ui.text.TextLayoutResult, val x: Float)

private class LineText(val runs: List<CachedTextRun>)

/** 按 (行 identity, version) 缓存测量好的文本 run：行内容不变（identity 同、
 *  version 未 bump）时重绘/滚动平移只回放布局，跳过文本测量——全屏文本布局是
 *  每帧最昂贵的部分。只有可见行会被绘制，LRU 容量取可见屏数倍即可。
 *  整体失效由外层 remember(style, wideStyle, theme) 负责（字号/主题变化即重建）。 */
private class LineTextCache(private val capacity: Int = 256) {
    private class Entry(val version: Long, val text: LineText)

    // LinkedHashMap 保持插入序：get/put 时重插到末尾实现 LRU
    //（Kotlin common 的 LinkedHashMap 没有 accessOrder/removeEldestEntry）
    private val map = LinkedHashMap<Any, Entry>()

    fun get(line: TerminalLine): LineText? {
        val e = map.remove(line.identity) ?: return null
        map[line.identity] = e // 重插到末尾（标记最近使用）
        return if (e.version == line.version) e.text else null
    }

    fun put(line: TerminalLine, text: LineText) {
        map.remove(line.identity)
        map[line.identity] = Entry(line.version, text)
        if (map.size > capacity) {
            val it = map.keys.iterator()
            if (it.hasNext()) {
                it.next()
                it.remove() // 逐出最久未用
            }
        }
    }
}

private fun currentStartAbs(buffer: TerminalBuffer, scrollOffset: Int): Int {
    val scrollback = buffer.scrollbackSize()
    val offset = scrollOffset.coerceIn(0, scrollback)
    return (buffer.totalLines() - buffer.rows - offset).coerceAtLeast(0)
}

/** OSC 8 链接可打开的协议白名单：只放开常规 Web/邮件，避免远端内容触发 file://、intent:// 等。 */
private fun isSafeLinkScheme(url: String): Boolean {
    val scheme = url.substringBefore(':', "").lowercase()
    return scheme in setOf("http", "https", "mailto")
}

@Composable
fun TerminalView(
    controller: TerminalController,
    theme: TerminalTheme,
    fontSizeSp: Float,
    targetCols: Int = 0,
    /** 键盘/工具栏覆盖画布底部的高度（px）：用于向上平移画布保证光标可见。 */
    coveredBottomPx: Float = 0f,
    /** 软键盘是否弹出：仅在键盘弹出时向上平移画布，避免工具栏常驻把顶部（如 herdr 的 switch 菜单）顶出屏幕。 */
    keyboardVisible: Boolean = false,
    /** 首次量到真实画布尺寸后回调（用于以真实行列建连，避免先以 80x24 起 PTY）。 */
    onReady: (cols: Int, rows: Int) -> Unit = { _, _ -> },
    /**
     * 画布尺寸是否已稳定（布局链上游的常驻元素已量到）：false 时不 resize、
     * 不上报 [onReady]——首帧中间尺寸（如工具栏未量到、bottom padding 未
     * 生效）会以错误行数起 PTY / resize，TUI（herdr）跟着整体重排跳动。
     */
    sizeStable: Boolean = true,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()

    var scrollOffset by remember { mutableStateOf(0) }
    // 滚动小数累加器（按行换算）
    var scrollAccum by remember { mutableFloatStateOf(0f) }
    // 首次量到有效画布尺寸后置位（用真实行列建连）
    var readySent by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    // 惯性滚轮动画作用域：rememberCoroutineScope 在部分平台上调度不保证立即执行，
    // 这里用显式 Main 作用域 + 组合销毁时取消
    val inertiaScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    DisposableEffect(Unit) {
        onDispose { inertiaScope.cancel() }
    }

    val fontFamily = monospaceFontFamily()
    val cjkFont = cjkFontFamily()
    // 目标列数模式：字形宽度与字号成线性关系，用 12sp 参考测量反算所需字号
    // 注意：不能用 fontFamily 做键——monospaceFontFamily() 每次组合都返回新实例，
    // 用它做键会导致这里和下面的 sample 每帧重算。
    val effectiveFontSizeSp = remember(canvasSize.width, targetCols, fontSizeSp) {
        if (targetCols > 0 && canvasSize.width > 0) {
            val ref = textMeasurer.measure("0".repeat(16), TextStyle(fontFamily = fontFamily, fontSize = 12.sp))
            val refCellW = ref.size.width.toFloat() / 16f
            val desiredCellW = canvasSize.width.toFloat() / targetCols
            var font = (12f * desiredCellW / refCellW).coerceIn(4f, 32f)
            // 文字测量按整数像素取整，直接反算的 cellW 可能偏大导致实际列数比目标少 1
            // （屏幕右侧留一条空白）。逐次实测校正：列数不足就按比例调小字号并留余量。
            var guard = 0
            while (guard < 5) {
                val sample = textMeasurer.measure(
                    "0".repeat(16),
                    TextStyle(fontFamily = fontFamily, fontSize = font.sp),
                )
                val measuredCellW = sample.size.width.toFloat() / 16f
                val cols = (canvasSize.width / measuredCellW).toInt().coerceAtLeast(1)
                if (cols >= targetCols || font <= 4f) break
                font = (font * (desiredCellW / measuredCellW) * 0.99f).coerceIn(4f, 32f)
                guard++
            }
            font
        } else fontSizeSp
    }

    val fontSize = effectiveFontSizeSp.sp
    val style = TextStyle(fontFamily = fontFamily, fontSize = fontSize)
    // JetBrains Mono 不含 CJK 字形：宽字符（中文/日韩/全角/emoji）改用 cjkFontFamily()
    // （iOS 显式加载 PingFang SC，其他平台走系统回退），避免缺字显示成豆腐块/乱码。
    // 格宽仍按 JetBrains Mono 度量，宽字符天然占 2 格。
    val wideStyle = style.copy(fontFamily = cjkFont)
    // 用较长采样串测单格宽度：避免小字号下单字符宽度像素取整带来的累积误差
    val sample = remember(effectiveFontSizeSp) { textMeasurer.measure("0".repeat(16), style) }
    val cellW = (sample.size.width.toFloat() / 16f).coerceAtLeast(1f)
    val cellH = (sample.size.height.toFloat() * 1.2f).coerceAtLeast(1f)
    val textTop = (cellH - sample.size.height) / 2f
    // pointerInput(Unit) 闭包只在首次组合创建一次，直接捕获 cellW/cellH 会
    // 定格旧值：改字号/旋转/resize 后点击→格子的换算全部错位。
    // rememberUpdatedState 返回的 State 实例稳定，旧闭包读取到的仍是实时值。
    val currentCellW by rememberUpdatedState(cellW)
    val currentCellH by rememberUpdatedState(cellH)

    // 按 (ARGB, bold, 窄/宽字体) 缓存 TextStyle，避免每帧每个 run 都 style.copy
    val textStyleCache = remember(style, wideStyle) { HashMap<Long, TextStyle>() }
    fun cachedTextStyle(base: TextStyle, fg: Color, bold: Boolean): TextStyle {
        val key = ((fg.toArgb().toLong() and 0xffffffffL) shl 2) or
            (if (bold) 2L else 0L) or
            (if (base === wideStyle) 1L else 0L)
        return textStyleCache.getOrPut(key) {
            base.copy(color = fg, fontWeight = if (bold) FontWeight.Bold else null)
        }
    }

    val buffer = controller.buffer
    val latestCanvasSize by rememberUpdatedState(canvasSize)
    val uriHandler = LocalUriHandler.current

    // 行级文本渲染缓存：键 (identity, version) 已随 COW/增量拷贝保证「内容变则
    // version 变」；style/theme 变化时 remember 重建整体失效。命中时重绘只是
    // 回放 TextLayoutResult，滚动时整屏平移几乎全部命中。
    val lineTextCache = remember(style, wideStyle, theme) { LineTextCache() }

    // 测量一行的全部文本 run：窄字符按 (fg, attrs) 连续成 run，宽字符单独测量。
    // 与下方背景/下划线 pass 的遍历规则保持一致。
    fun measureLineText(line: TerminalLine): LineText {
        val runs = ArrayList<CachedTextRun>()
        var i = 0
        while (i < buffer.cols) {
            val cell = line.cells[i]
            if (cell.isWideTail) { i++; continue }
            if (cell.codePoint == ' '.code && cell.attrs and (CellAttr.UNDERLINE or CellAttr.INVERSE) == 0) { i++; continue }

            val (fgColor, _) = cellColors(cell, theme)
            val runStyle = cachedTextStyle(style, fgColor, cell.attrs and CellAttr.BOLD != 0)
            var j = i
            val sb = StringBuilder()
            while (j < buffer.cols) {
                val c2 = line.cells[j]
                if (c2.isWideTail || c2.fg != cell.fg || c2.attrs != cell.attrs) break
                if (c2.width != 1) break
                if (c2.codePoint == ' '.code && c2.attrs and (CellAttr.UNDERLINE or CellAttr.INVERSE) == 0) break
                sb.appendCodePoint(c2.codePoint)
                j++
            }
            if (sb.isNotEmpty()) {
                runs.add(CachedTextRun(textMeasurer.measure(sb.toString(), runStyle), i * cellW))
            }
            // 宽字符单独测量
            if (j < buffer.cols && !line.cells[j].isWideTail && line.cells[j].width == 2) {
                val wide = line.cells[j]
                runs.add(
                    CachedTextRun(
                        textMeasurer.measure(
                            codePointToString(wide.codePoint),
                            cachedTextStyle(wideStyle, cellColors(wide, theme).first, wide.attrs and CellAttr.BOLD != 0),
                        ),
                        j * cellW,
                    ),
                )
                j += 2
            }
            i = j
        }
        return LineText(runs)
    }

    // 触摸 → 终端鼠标事件（X10/SGR）：herdr/vim/htop 等 TUI 开启鼠标上报后，
    // 触摸映射为左键按下/拖拽/释放，滚动手势映射为滚轮。坐标为 1-based 格坐标。
    fun sendMouseEvent(btn: Int, col: Int, row: Int, release: Boolean) {
        val c = (col + 1).coerceIn(1, buffer.cols)
        val r = (row + 1).coerceIn(1, buffer.rows)
        if (buffer.mouseUrxvt) {
            // urxvt 1015：CSI b;x;y M/m，无私有前缀
            controller.sendBytes("\u001b[$btn;$c;$r${if (release) "m" else "M"}".encodeToByteArray())
        } else if (buffer.mouseSgr) {
            controller.sendText("\u001b[<$btn;$c;$r${if (release) "m" else "M"}")
        } else {
            // X10：按下发按钮号，释放发 3；坐标 32 偏移字节，上限 223
            if (c > 223 || r > 223) return
            val b = if (release) 3 else btn
            controller.sendBytes(
                byteArrayOf(0x1b, '['.code.toByte(), 'M'.code.toByte(), (32 + b).toByte(), (32 + c).toByte(), (32 + r).toByte())
            )
        }
    }
    fun pointerCell(pos: Offset): Pair<Int, Int> =
        ((pos.x / currentCellW).toInt().coerceIn(0, buffer.cols - 1)) to
            ((pos.y / currentCellH).toInt().coerceIn(0, buffer.rows - 1))

    // 内容不足一屏时的填充空行：共享同一实例（只读），保证行缓存键稳定，
    // 避免每帧新建实例产生的缓存垃圾
    val emptyLine = remember(buffer.cols) { TerminalLine(buffer.cols) }

    // 尺寸或字号变化时重新计算行列并同步 PTY；sizeStable=false（上游布局
    // 未定，画布尺寸还是中间值）时全部跳过——稳定后 effect 重跑一次到位
    LaunchedEffect(canvasSize, effectiveFontSizeSp, sizeStable) {
        if (!sizeStable || canvasSize.width <= 0 || canvasSize.height <= 0) return@LaunchedEffect
        val cols = (canvasSize.width / cellW).toInt().coerceAtLeast(1)
        val rows = (canvasSize.height / cellH).toInt().coerceAtLeast(1)
        controller.resize(cols, rows, canvasSize.width, canvasSize.height)
        if (!readySent) {
            readySent = true
            onReady(cols, rows)
        }
    }

    Canvas(
        modifier
            // 软键盘弹出且光标被键盘区域遮住时向上平移，保证光标可见（不改变 PTY
            // 尺寸，避免全屏程序随键盘弹收反复重排）。工具栏/导航条常驻不平移；
            // 用户回看滚动时不平移。平移计算放在 graphicsLayer 块内：frame（输出）
            // 与 scrollOffset 的变化只触发 layer 更新，不再重组整个 TerminalView。
            .graphicsLayer {
                @Suppress("UNUSED_VARIABLE") val redraw = controller.frame
                val panUp =
                    if (!keyboardVisible || scrollOffset != 0 || size.height <= 0 || coveredBottomPx <= 0f) {
                        0f
                    } else {
                        val cursorBottomY =
                            (buffer.absCursorRow() - currentStartAbs(buffer, scrollOffset) + 1) * cellH
                        val visibleBottomY = size.height - coveredBottomPx
                        if (cursorBottomY > visibleBottomY) {
                            (cursorBottomY - visibleBottomY).coerceAtMost(size.height)
                        } else 0f
                    }
                translationY = -panUp
            }
            .onSizeChanged { size ->
                canvasSize = size
            }
            // 触摸 → 鼠标事件：TUI 开启上报（1000/1002/1003）时接管手势，
            // 否则不消费事件，保持聚焦键盘/选择/滚动回看的默认行为。
            // 手势语义（鼠标模式下唯一事件源，scrollable 同步让位）：
            //   - 轻点 → 左键按下/释放（释放用按下格，避免手指抖动被 herdr 的 1 格拖拽阈值判成拖拽）
            //   - 单指纵向拖拽 → 滚轮（手指下滑=回看=滚轮上 64，上滑=向下=65），落点跟随手指
            //   - 单指横向拖拽 / 双指拖拽 → 鼠标拖拽 motion（1002/1003，选字、拖分割线、pane 内拖拽）
            .pointerInput(Unit) {
                // 纵向滚轮手势的惯性：手指抬起后按速度持续发衰减滚轮，直到停下
                var inertiaJob: Job? = null
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // 手指落下即停惯性。必须在 awaitFirstDown 之后：awaitEachGesture
                    // 在上个手势块返回后立即重入本块（循环里先调 block 再等 all-up），
                    // 若先 cancel，刚启动的惯性会在同一帧被掐死（拖多少是多少）。
                    inertiaJob?.cancel()
                    if (buffer.mouseTracking <= 0) return@awaitEachGesture
                    down.consume()
                    val downId = down.id
                    val downCell = pointerCell(down.position)
                    var (col, row) = downCell
                    // 手势方向（null=尚未判定）：按整体位移主导方向决定语义并锁定，
                    // 避免真实手指的横向抖动把纵向滚动误判成横向拖拽（herdr 会当
                    // 成选区并复制）。纵向主导 → 只发滚轮；横向主导 → 才发 Down+motion。
                    var vertical: Boolean? = null
                    var totalDx = 0f
                    var totalDy = 0f
                    // 延迟到手势意图明确后再发 Down：
                    //   - 轻点 → 按下+抬起同格（点击）
                    //   - 单指纵向拖拽 → 只发滚轮（不按下/抬起）：herdr 对"按下后在其他格抬起"
                    //     会判成拖拽选区并复制 → OSC 52 → app 反复弹「远端已写入剪贴板」
                    //   - 横向/多指拖拽 → 补发 Down + drag-motion + Up
                    var downSent = false
                    var sentMotion = false
                    var sentWheel = false
                    fun sendDownIfNeeded() {
                        if (!downSent) {
                            downSent = true
                            sendMouseEvent(0, downCell.first, downCell.second, release = false)
                        }
                    }
                    var scrollAccumY = 0f
                    var prevX = down.position.x
                    var prevY = down.position.y
                    var lastPos = down.position
                    // 最近事件时间/位置窗口，用于估算手指抬起时的速度
                    val recent = ArrayDeque<Pair<Long, Float>>()
                    var movedCell = false
                    var multiTouch = false
                    while (true) {
                        val event = awaitPointerEvent()
                        // 第二根手指参与 → 切换为鼠标拖拽语义
                        if (event.changes.count { it.pressed } > 1) multiTouch = true
                        val change = event.changes.firstOrNull { it.id == downId } ?: continue
                        // 以 pressed 状态判断结束而非 changedToUp()：极快的点击可能被 Compose
                        // 合并为单帧（down+up 同帧时 previousPressed=false，changedToUp 永不成立），
                        // 否则释放事件丢失、手势循环卡死，后续点击全被吞。
                        if (!change.pressed) {
                            if (downSent) {
                                val upPos = if (movedCell && sentMotion) change.position else down.position
                                val (uc, ur) = pointerCell(upPos)
                                sendMouseEvent(0, uc, ur, release = true)
                            } else if (!sentWheel) {
                                // 纯轻点（无位移、无滚轮）：补发同格按下+抬起，保证点击语义
                                val (uc, ur) = pointerCell(down.position)
                                sendMouseEvent(0, uc, ur, release = false)
                                sendMouseEvent(0, uc, ur, release = true)
                            } else {
                                // 纵向滚轮手势：按手指抬起速度补惯性滚轮（衰减），
                                // 方向与滑动一致，落点固定在手最后位置（钳到第 1 行起）
                                val velocityY = if (recent.size >= 2) {
                                    val newest = recent.last()
                                    val oldest = recent.first()
                                    val dtMs = (newest.first - oldest.first).coerceAtLeast(16L)
                                    (newest.second - oldest.second) * 1000f / dtMs
                                } else 0f
                                if (kotlin.math.abs(velocityY) >= 150f) {
                                    val btn = if (velocityY > 0f) 64 else 65
                                    val (wc, wr) = pointerCell(lastPos)
                                    val wheelRow = maxOf(wr, 1)
                                    val startV = kotlin.math.abs(velocityY)
                                    inertiaJob?.cancel()
                                    inertiaJob = inertiaScope.launch {
                                        var vel = startV
                                        var accum = 0f
                                        while (vel > 50f) {
                                            val dt = 16f
                                            accum += vel * dt / 1000f
                                            val notches = (accum / currentCellH).toInt()
                                            if (notches != 0) {
                                                repeat(kotlin.math.abs(notches)) {
                                                    sendMouseEvent(btn, wc, wheelRow, release = false)
                                                }
                                                accum -= notches * currentCellH
                                            }
                                            vel *= 0.94f
                                            delay(16)
                                        }
                                    }
                                }
                            }
                            break
                        }
                        change.consume()
                        val dx = change.position.x - prevX
                        val dy = change.position.y - prevY
                        prevX = change.position.x
                        prevY = change.position.y
                        lastPos = change.position
                        recent.addLast(change.uptimeMillis to change.position.y)
                        // 只保留最近 ~150ms 的事件：长按后拖动时，长按期的静止事件
                        // 会稀释速度估算（长按 500ms + 快速拖动 → 平均速度接近 0），
                        // 惯性就永远不触发。按时间裁剪后速度反映真实拖动速度。
                        val nowMs = change.uptimeMillis
                        while (recent.isNotEmpty() && nowMs - recent.first().first > 150L) {
                            recent.removeFirst()
                        }
                        totalDx += dx
                        totalDy += dy
                        // 位移超过一格后判定方向并锁定（横向抖动不改变纵向手势）
                        if (vertical == null && !multiTouch &&
                            (kotlin.math.abs(totalDx) >= currentCellW || kotlin.math.abs(totalDy) >= currentCellH)
                        ) {
                            vertical = kotlin.math.abs(totalDy) >= kotlin.math.abs(totalDx)
                        }
                        val (nc, nr) = pointerCell(change.position)
                        if (nc != col || nr != row) {
                            col = nc
                            row = nr
                            movedCell = true
                            // 横向主导/多指 → 鼠标拖拽（分割线、选区）；纵向主导 → 已换算滚轮
                            val wantMotion = buffer.mouseTracking >= 1002 && (multiTouch || vertical == false)
                            if (wantMotion) {
                                sendDownIfNeeded()
                                sendMouseEvent(32, col, row, release = false)
                                sentMotion = true
                            }
                        }
                        // 单指纵向位移 → 滚轮（每 cellH 一档），落点跟随手指当前位置
                        if (!multiTouch) {
                            scrollAccumY += dy
                            if (vertical == true) {
                                val notches = (scrollAccumY / currentCellH).toInt()
                                if (notches != 0) {
                                    val btn = if (notches > 0) 64 else 65
                                    val (wc, wr) = pointerCell(change.position)
                                    // 快速上滑到顶部时，最后一批滚轮会落在第 0 行——桌面布局下那是
                                    // herdr 的 tab 栏，每个滚轮都会切一次 tab（表现为松手后疯狂切 tab）。
                                    // 滚轮钳制到第 1 行起：pane 区完全可滚，chrome 永不接收滚轮；
                                    // 点击切 tab 走 Down/Up 路径，不受影响。
                                    val wheelRow = maxOf(wr, 1)
                                    repeat(kotlin.math.abs(notches)) {
                                        sendMouseEvent(btn, wc, wheelRow, release = false)
                                    }
                                    scrollAccumY -= notches * currentCellH
                                    sentWheel = true
                                }
                            }
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = tap@{ pos ->
                        // TUI 鼠标模式：点击事件由上面的鼠标 handler 全权上报，本地不拦截
                        if (buffer.mouseTracking > 0) return@tap
                        val startAbsNow = currentStartAbs(buffer, scrollOffset)
                        val col = (pos.x / currentCellW).toInt().coerceIn(0, buffer.cols - 1)
                        val row = (pos.y / currentCellH).toInt().coerceIn(0, buffer.rows - 1)
                        val absRow = startAbsNow + row
                        var opened = false
                        if (absRow in 0 until buffer.totalLines()) {
                            val link = buffer.absLine(absRow).cells[col].link
                            if (link != null && isSafeLinkScheme(link)) {
                                uriHandler.openUri(link)
                                opened = true
                            }
                        }
                        // 点空白处收起选区
                        if (!opened && controller.selection.isActive) {
                            controller.selection.clear()
                        }
                    },
                    onDoubleTap = tap@{ pos ->
                        // TUI 鼠标模式（herdr/vim 等）：选区由远端自己管理，
                        // 本地双击选词会与远端选区冲突，且长按/慢滑误触会整行复制
                        if (buffer.mouseTracking > 0) return@tap
                        // 双击选词并复制
                        val startAbsNow = currentStartAbs(buffer, scrollOffset)
                        val col = (pos.x / currentCellW).toInt().coerceIn(0, buffer.cols - 1)
                        val row = (pos.y / currentCellH).toInt().coerceIn(0, buffer.rows - 1)
                        val absRow = startAbsNow + row
                        if (absRow < buffer.totalLines()) {
                            val line = buffer.absLine(absRow)
                            fun isWordCell(idx: Int): Boolean {
                                val c = line.cells[idx]
                                if (c.isWideTail) return false
                                val cp = c.codePoint
                                return cp < 128 && (cp.toChar().isLetterOrDigit() || cp.toChar() in "._-~/:@%")
                            }
                            if (isWordCell(col)) {
                                var s = col
                                while (s > 0 && isWordCell(s - 1)) s--
                                var e = col
                                while (e < buffer.cols - 1 && isWordCell(e + 1)) e++
                                controller.selection.start(absRow, s)
                                controller.selection.extend(absRow, e)
                                val text = controller.selection.selectedText()
                                if (text.isNotEmpty()) onCopy(text)
                            }
                        }
                    },
                    onLongPress = longPress@{ pos ->
                        // 同上：鼠标模式下本地长按选词禁用（用户长按后滑动会误触整行复制）
                        if (buffer.mouseTracking > 0) return@longPress
                        val startAbsNow = currentStartAbs(buffer, scrollOffset)
                        val col = (pos.x / currentCellW).toInt().coerceIn(0, buffer.cols - 1)
                        val row = (pos.y / currentCellH).toInt().coerceIn(0, buffer.rows - 1)
                        val absRow = startAbsNow + row
                        controller.selection.start(absRow, col)
                        controller.selection.extend(absRow, buffer.cols - 1)
                        val text = controller.selection.selectedText()
                        if (text.isNotEmpty()) {
                            onCopy(text)
                        }
                    },
                )
            }
            // 滚动回看：scrollable 自带 fling 惯性衰减；
            // delta 为手指拖动方向（下滑 delta>0 → 内容跟手下移 → 看历史 → scrollOffset 增大）
            .scrollable(
                orientation = Orientation.Vertical,
                state = rememberScrollableState { delta ->
                    // TUI 鼠标模式：手势由上面的鼠标 handler 全权处理（避免拖拽既发
                    // drag-motion 又发滚轮的混合序列），本地滚动只在非鼠标模式生效
                    if (buffer.mouseTracking > 0) return@rememberScrollableState delta
                    // DECSET 1007 alternate scroll：备用屏里没有回看可滚，
                    // 滚动手势转成方向键交给远端全屏程序（less/vim 等）
                    if (buffer.altScreen && buffer.alternateScroll) {
                        scrollAccum += delta
                        val rows = (scrollAccum / cellH).toInt()
                        if (rows != 0) {
                            val key = if (rows > 0) "\u001b[B" else "\u001b[A"
                            repeat(kotlin.math.abs(rows)) {
                                controller.sendBytes(key.encodeToByteArray())
                            }
                            scrollAccum -= rows * cellH
                        }
                        return@rememberScrollableState delta
                    }
                    scrollAccum += delta
                    val rows = (scrollAccum / cellH).toInt()
                    if (rows != 0) {
                        scrollOffset = (scrollOffset + rows).coerceIn(0, buffer.scrollbackSize())
                        scrollAccum -= rows * cellH
                    }
                    delta
                },
            )
            // 滚动条拖动：仅右边缘命中时消费事件，其余交给 scrollable。
            // 鼠标模式下让位（TUI 的 UI 元素可能在右边缘，如 herdr 的 tab 按钮）；
            // 默认 requireUnconsumed=true：上游鼠标处理已消费的事件不再触发。
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (buffer.mouseTracking > 0) return@awaitEachGesture
                    val barZone = size.width - 24.dp.toPx()
                    if (down.position.x >= barZone && buffer.scrollbackSize() > 0) {
                        drag(down.id) { change ->
                            val sb = buffer.scrollbackSize()
                            val h = latestCanvasSize.height
                            if (sb > 0 && h > 0) {
                                val total = buffer.totalLines().toFloat()
                                val trackH = h.toFloat()
                                val thumbH = (buffer.rows / total * trackH).coerceAtLeast(24f)
                                val ratio = (change.position.y / (trackH - thumbH)).coerceIn(0f, 1f)
                                scrollOffset = (ratio * sb).toInt().coerceIn(0, sb)
                            }
                            change.consume()
                        }
                    }
                }
            },
    ) {
        // 观察重绘序号触发 redraw（否则 Compose 会因 draw 内容未变而跳过重绘）
        @Suppress("UNUSED_VARIABLE")
        val redraw = controller.frame

        // 每次绘制都基于最新 buffer/滚动位置计算可视区
        val scrollback = buffer.scrollbackSize()
        val offset = scrollOffset.coerceIn(0, scrollback)
        val totalLines = buffer.totalLines()
        val startAbs = (totalLines - buffer.rows - offset).coerceAtLeast(0)

        // 背景（alpha 恒为 1，仅用于消费 redraw 以建立重绘观察）
        drawRect(theme.background().copy(alpha = if (redraw >= 0L) 1f else 1f))

        val lines = ArrayList<TerminalLine>(buffer.rows)
        for (r in 0 until buffer.rows) {
            val abs = startAbs + r
            lines.add(if (abs < totalLines) buffer.absLine(abs) else emptyLine)
        }

        // 键盘弹起/字号变化时，canvas 尺寸与 buffer 行列数存在一帧的瞬态不一致，
        // 越界绘制文本会导致 drawText 内部约束为负而崩溃，这里统一跳过越界文本。
        fun drawTextInBounds(text: String, topLeft: Offset, textStyle: TextStyle) {
            if (topLeft.x < 0f || topLeft.y < 0f || topLeft.x >= size.width || topLeft.y >= size.height) return
            drawText(textMeasurer = textMeasurer, text = text, topLeft = topLeft, style = textStyle)
        }

        // 缓存布局的回放版本：跳过测量，直接画 TextLayoutResult
        fun drawLayoutInBounds(run: CachedTextRun, topLeft: Offset) {
            if (topLeft.x < 0f || topLeft.y < 0f || topLeft.x >= size.width || topLeft.y >= size.height) return
            drawText(textLayoutResult = run.layout, topLeft = topLeft)
        }

        // 背景色 run
        for (r in lines.indices) {
            val line = lines[r]
            val y = r * cellH
            var i = 0
            while (i < buffer.cols) {
                val cell = line.cells[i]
                val bgRaw = cell.bg
                var j = i
                while (j < buffer.cols && line.cells[j].bg == bgRaw) j++
                if (bgRaw != DEFAULT_BG) {
                    val (_, bgColor) = cellColors(cell, theme)
                    drawRect(
                        color = bgColor,
                        topLeft = Offset(i * cellW, y),
                        size = Size((j - i) * cellW, cellH),
                    )
                }
                i = j
            }
        }

        // 文本 run：按行 (identity, version) 缓存测量结果——未变行只回放布局，
        // 滚动时整屏内容平移几乎全部命中缓存，不再每帧全屏文本重排
        for (r in lines.indices) {
            val line = lines[r]
            val y = r * cellH
            val lineText = lineTextCache.get(line)
                ?: measureLineText(line).also { lineTextCache.put(line, it) }
            for (run in lineText.runs) {
                drawLayoutInBounds(run, Offset(run.x, y + textTop))
            }
        }

        // 下划线
        for (r in lines.indices) {
            val line = lines[r]
            val y = r * cellH
            var i = 0
            while (i < buffer.cols) {
                val cell = line.cells[i]
                if (cell.attrs and CellAttr.UNDERLINE != 0 && !cell.isWideTail) {
                    var j = i
                    while (j < buffer.cols && line.cells[j].attrs and CellAttr.UNDERLINE != 0 && !line.cells[j].isWideTail) j++
                    val (fgColor, _) = cellColors(cell, theme)
                    drawRect(
                        color = fgColor,
                        topLeft = Offset(i * cellW, y + cellH - 2f),
                        size = Size((j - i) * cellW, 1.5f),
                    )
                    i = j
                } else i++
            }
        }

        // OSC 8 超链接下划线（与 SGR 下划线分开画，避免依赖属性位）
        for (r in lines.indices) {
            val line = lines[r]
            val y = r * cellH
            var i = 0
            while (i < buffer.cols) {
                val cell = line.cells[i]
                if (cell.link != null && !cell.isWideTail) {
                    var j = i
                    while (j < buffer.cols && line.cells[j].link == cell.link && !line.cells[j].isWideTail) j++
                    val (fgColor, _) = cellColors(cell, theme)
                    drawRect(
                        color = fgColor.copy(alpha = 0.65f),
                        topLeft = Offset(i * cellW, y + cellH - 2f),
                        size = Size((j - i) * cellW, 1.5f),
                    )
                    i = j
                } else i++
            }
        }

        // 选择高亮
        if (controller.selection.isActive) {
            val sel = controller.selection
            val selTop = minOf(sel.startRow, sel.endRow)
            val selBottom = maxOf(sel.startRow, sel.endRow)
            for (abs in selTop..selBottom) {
                val r = abs - startAbs
                if (r !in 0 until buffer.rows) continue
                val fromCol = if (abs == sel.startRow && abs == sel.endRow) minOf(sel.startCol, sel.endCol)
                else if (abs == sel.startRow) sel.startCol
                else if (abs == sel.endRow) sel.endCol
                else -1
                if (fromCol == -1) {
                    drawRect(theme.selection(), Offset(0f, r * cellH), Size(buffer.cols * cellW, cellH))
                } else {
                    val toCol = if (abs == sel.endRow) maxOf(sel.startCol, sel.endCol) else buffer.cols - 1
                    drawRect(
                        theme.selection(),
                        Offset(fromCol * cellW, r * cellH),
                        Size((toCol - fromCol + 1) * cellW, cellH),
                    )
                }
            }
        }

        // 光标
        val cursorAbs = buffer.absCursorRow()
        val cursorScreenRow = cursorAbs - startAbs
        // 协议显隐（DECSET 25）与渲染闪烁相位叠加：
        // 稳态样式（2/4/6）常亮，闪烁样式按相位交替
        val steadyCursor = buffer.cursorStyle == 2 || buffer.cursorStyle == 4 || buffer.cursorStyle == 6
        if (buffer.cursorVisible && cursorScreenRow in 0 until buffer.rows &&
            (steadyCursor || controller.cursorBlinkPhase)
        ) {
            val cursorColor = if (buffer.cursorColor != DEFAULT_CURSOR) {
                Color(0xFF000000.toInt() or buffer.cursorColor)
            } else theme.cursor()
            val cursorColPx = buffer.cursorCol * cellW
            val cursorRowPx = cursorScreenRow * cellH
            when (buffer.cursorStyle) {
                // DECSCUSR：下划线 / 竖线光标
                3, 4 -> drawRect(
                    color = cursorColor,
                    topLeft = Offset(cursorColPx, cursorRowPx + cellH - 2f),
                    size = Size(cellW, 2f),
                )
                5, 6 -> drawRect(
                    color = cursorColor,
                    topLeft = Offset(cursorColPx, cursorRowPx),
                    size = Size(2f, cellH),
                )
                else -> {
                    drawRect(
                        color = cursorColor,
                        topLeft = Offset(cursorColPx, cursorRowPx),
                        size = Size(cellW, cellH),
                    )
                    // 块状光标下的字符反色显示（下划线/竖线光标不遮挡字符）
                    if (cursorAbs in 0 until totalLines) {
                        val line = buffer.absLine(cursorAbs)
                        if (buffer.cursorCol < buffer.cols) {
                            val cell = line.cells[buffer.cursorCol]
                            if (!cell.isWideTail && cell.codePoint != ' '.code) {
                                val (fg, bg) = cellColors(cell, theme)
                                drawTextInBounds(
                                    codePointToString(cell.codePoint),
                                    Offset(cursorColPx, cursorRowPx + textTop),
                                    (if (cell.width == 2) wideStyle else style)
                                        .copy(color = bg, fontWeight = runFontWeight(cell.attrs)),
                                )
                            }
                        }
                    }
                }
            }
        }

        // 滚动条（有历史时显示在右侧，可拖动）
        if (scrollback > 0) {
            val barW = 6f
            val barX = size.width - barW - 4f
            val trackH = (size.height - 4f).coerceAtLeast(1f)
            val total = totalLines.toFloat().coerceAtLeast(buffer.rows.toFloat())
            val thumbH = (buffer.rows / total * trackH).coerceAtLeast(24f)
            val thumbY = (offset.toFloat() / scrollback * (trackH - thumbH)).coerceIn(0f, trackH - thumbH)
            drawRect(theme.foreground().copy(alpha = 0.12f), Offset(barX, 2f), Size(barW, trackH))
            drawRect(theme.foreground().copy(alpha = 0.45f), Offset(barX, 2f + thumbY), Size(barW, thumbH))
        }
    }
}
