package dev.termish.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.termish.ui.theme.TerminalTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** 特殊键。CTRL/ALT 为粘性修饰键，仅影响后续输入。
 *  低频键（F1–F12 / HOME / END / PGUP / PGDN / DEL / ⌃Z 等）收纳在
 *  工具栏「⋯」溢出面板（MoreKeySheet），不再占常驻键位；
 *  转义映射见下方 specialKeyBytes。 */
enum class SpecialKey(val label: String) {
    ESC("ESC"), TAB("TAB"), CTRL("CTRL"), ALT("ALT"),
    UP("↑"), DOWN("↓"), LEFT("←"), RIGHT("→"),
    CTRL_C("⌃C"), CTRL_D("⌃D"), CTRL_L("⌃L"), CTRL_E("⌃E"),
    ENTER("ENT"),
    /** Shift+Tab（ESC[Z）：TUI 菜单反选；Claude Code 模式切换刚需（软键盘打不出）。
     *  常驻 Tab 长按触发。 */
    SHIFT_TAB("⇧⇥"),
    /** ⌃\（SIGQUIT）：长按 ⌃C/⌃D 触发，杀连 ⌃C 都不响应的顽固进程。 */
    CTRL_BACKSLASH("⌃\\"),
    // ---- 溢出面板键（⋯ 展开） ----
    CTRL_R("⌃R"), CTRL_Z("⌃Z"),
    DEL("DEL"), HOME("HOME"), END("END"), PGUP("PGUP"), PGDN("PGDN"),
    F1("F1"), F2("F2"), F3("F3"), F4("F4"), F5("F5"), F6("F6"),
    F7("F7"), F8("F8"), F9("F9"), F10("F10"), F11("F11"), F12("F12"),
}

fun specialKeyBytes(key: SpecialKey, applicationCursorKeys: Boolean): ByteArray = when (key) {
    SpecialKey.ESC -> byteArrayOf(0x1b)
    SpecialKey.TAB -> byteArrayOf(0x09)
    SpecialKey.UP -> escapeSeq(if (applicationCursorKeys) "OA" else "A")
    SpecialKey.DOWN -> escapeSeq(if (applicationCursorKeys) "OB" else "B")
    SpecialKey.RIGHT -> escapeSeq(if (applicationCursorKeys) "OC" else "C")
    SpecialKey.LEFT -> escapeSeq(if (applicationCursorKeys) "OD" else "D")
    SpecialKey.CTRL_C -> byteArrayOf(0x03)
    SpecialKey.CTRL_D -> byteArrayOf(0x04)
    SpecialKey.CTRL_L -> byteArrayOf(0x0c)
    SpecialKey.CTRL_E -> byteArrayOf(0x05)
    SpecialKey.CTRL_R -> byteArrayOf(0x12)
    SpecialKey.CTRL_Z -> byteArrayOf(0x1a)
    SpecialKey.ENTER -> byteArrayOf(0x0d)
    SpecialKey.SHIFT_TAB -> byteArrayOf(0x1b, 0x5b, 0x5a) // ESC [ Z
    SpecialKey.CTRL_BACKSLASH -> byteArrayOf(0x1c) // SIGQUIT
    SpecialKey.DEL -> byteArrayOf(0x7f)
    SpecialKey.HOME -> escapeSeq(if (applicationCursorKeys) "OH" else "H")
    SpecialKey.END -> escapeSeq(if (applicationCursorKeys) "OF" else "F")
    SpecialKey.PGUP -> escapeSeq("5~")
    SpecialKey.PGDN -> escapeSeq("6~")
    SpecialKey.F1 -> byteArrayOf(0x1b, 0x4f, 0x50) // ESC O P
    SpecialKey.F2 -> byteArrayOf(0x1b, 0x4f, 0x51) // ESC O Q
    SpecialKey.F3 -> byteArrayOf(0x1b, 0x4f, 0x52) // ESC O R
    SpecialKey.F4 -> byteArrayOf(0x1b, 0x4f, 0x53) // ESC O S
    SpecialKey.F5 -> byteArrayOf(0x1b, 0x5b, 0x31, 0x35, 0x7e) // ESC [15~
    SpecialKey.F6 -> byteArrayOf(0x1b, 0x5b, 0x31, 0x37, 0x7e) // ESC [17~
    SpecialKey.F7 -> byteArrayOf(0x1b, 0x5b, 0x31, 0x38, 0x7e) // ESC [18~
    SpecialKey.F8 -> byteArrayOf(0x1b, 0x5b, 0x31, 0x39, 0x7e) // ESC [19~
    SpecialKey.F9 -> byteArrayOf(0x1b, 0x5b, 0x32, 0x30, 0x7e) // ESC [20~
    SpecialKey.F10 -> byteArrayOf(0x1b, 0x5b, 0x32, 0x31, 0x7e) // ESC [21~
    SpecialKey.F11 -> byteArrayOf(0x1b, 0x5b, 0x32, 0x33, 0x7e) // ESC [23~
    SpecialKey.F12 -> byteArrayOf(0x1b, 0x5b, 0x32, 0x34, 0x7e) // ESC [24~
    SpecialKey.CTRL, SpecialKey.ALT -> ByteArray(0)
}

private fun escapeSeq(suffix: String): ByteArray = ("\u001b[${suffix}").encodeToByteArray()

/** 按住连发参数（对齐实体键盘手感）：先延迟后连发，越按越快不会失控。 */
private const val REPEAT_INITIAL_DELAY_MS = 400L
private const val REPEAT_INTERVAL_MS = 60L

/**
 * 终端功能键工具栏：固定两行 + 可展开到四行。
 *
 * 行 1（控制）：ESC TAB ⌃C ⌃L {} ↑ ⌨ ▾（ESC 左上、⌨ 右上拇指区；
 * ▾ 展开/收起行 3/4，会话内保持；↑ 第 6 列与行 2 的 ↓ 上下对齐）。
 * 行 2（修饰/编辑）：CTRL ALT ⌃D / ← ↓ → ENT（CTRL/ALT 左下，同实体键盘
 * 底行；↑/↓ 第 6 列上下对齐；/ 由 onChar 直接发送）。
 * 行 3（展开）：PST ⇧⇥ DEL HOME END PGUP PGDN ⌃\\
 * 行 4（展开）：F1–F7 + ⎇（Git 面板；F8–F12 极低频不占位）。
 * 每行均 8 列等宽正方形键。
 *
 * - 方向键/⌫ 类支持**按住连发**（400ms 后 60ms/次）
 * - 长按宏：**ESC 双发**（Claude Code 编辑历史）；**TAB → ⇧⇥**（TUI 反选/模式切换）；
 *   **⌃C/⌃D → ⌃\\**（SIGQUIT 杀顽固进程）
 * - 粘性 CTRL/ALT + 系统键盘字母即可敲出 ⌃A/⌃E/⌃R 等组合（不占键位）
 * - 符号键由系统键盘提供；画布惯性滚动替代 PgUp/PgDn（展开行提供实体键）
 *
 * 展开状态上提至调用方（[expanded]/[onExpandedChange]），常驻两行高度经
 * [onBaseHeightChanged] 回调：画布只按常驻高度布局，展开行 3/4 覆盖画布
 * 而非压缩画布——不触发 PTY resize，TUI 不重排不闪屏。
 */
@Composable
fun KeyToolbar(
    ctrlActive: Boolean,
    altActive: Boolean,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    applicationCursorKeys: Boolean,
    onKey: (SpecialKey) -> Unit,
    onToggleKeyboard: () -> Unit = {},
    onPaste: () -> Unit = {},
    /** 命令片段插入面板（行 1 的「{}」键位）。 */
    onSnippets: () -> Unit = {},
    /** Git 面板入口（行 4 展开的「⎇」键位；画布 FAB 的备用入口）。 */
    onGit: () -> Unit = {},
    /** 普通字符键（如 /），直接作为终端输入发送。 */
    onChar: (String) -> Unit = {},
    /** 行 3/4 展开状态（上提：画布按常驻高度布局，展开行覆盖画布不 resize）。 */
    expanded: Boolean = false,
    onExpandedChange: (Boolean) -> Unit = {},
    /** 常驻两行高度回调（px；展开不改变）——画布 bottom padding 的依据。 */
    onBaseHeightChanged: (Int) -> Unit = {},
    theme: TerminalTheme,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 常驻两行单独包一层量高度（不受展开影响）：画布布局/建连门槛用它；
        // 内层也要 spacedBy——间距丢了会两行贴死（外层的只作用于内层块整体）
        Column(
            Modifier.onSizeChanged { onBaseHeightChanged(it.height) },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
        // 行 1：ESC 左上（实体键盘位置直觉）；TAB 常驻（shell 补全高频，长按 = ⇧⇥）；
        // {} 片段 / ⌨ / ▾ 展开靠右（拇指区）
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            RepeatKeyButton("ESC", theme,
                onTap = { onKey(SpecialKey.ESC) },
                onLongPress = { onKey(SpecialKey.ESC); onKey(SpecialKey.ESC) })
            KeyButton("TAB", false, theme,
                onLongPress = { onKey(SpecialKey.SHIFT_TAB) }) { onKey(SpecialKey.TAB) }
            RepeatKeyButton("⌃C", theme,
                onTap = { onKey(SpecialKey.CTRL_C) },
                onLongPress = { onKey(SpecialKey.CTRL_BACKSLASH) })
            KeyButton("⌃L", false, theme) { onKey(SpecialKey.CTRL_L) }
            KeyButton("{}", false, theme) { onSnippets() }
            // ↑ 保持第 6 列与行 2 的 ↓ 上下对齐（原设计约束）
            KeyButton("↑", false, theme, repeat = true) { onKey(SpecialKey.UP) }
            KeyButton("⌨", false, theme) { onToggleKeyboard() }
            // 展开/收起：Material 箭头图标（22dp：48dp 触控目标配 24dp 图标的
            // 规范内取值，比原文字符号大而清晰、又不像 30dp 那样撑满键）
            KeyButton("", expanded, theme,
                icon = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                iconSize = 22.dp,
            ) { onExpandedChange(!expanded) }
        }
        // 行 2：CTRL/ALT 左下（同实体键盘底行）；↑/↓ 第 6 列上下对齐；ENT 右下
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            KeyButton("CTRL", ctrlActive, theme) { onToggleCtrl() }
            KeyButton("ALT", altActive, theme) { onToggleAlt() }
            RepeatKeyButton("⌃D", theme,
                onTap = { onKey(SpecialKey.CTRL_D) },
                onLongPress = { onKey(SpecialKey.CTRL_BACKSLASH) })
            KeyButton("/", false, theme) { onChar("/") }
            KeyButton("←", false, theme, repeat = true) { onKey(SpecialKey.LEFT) }
            KeyButton("↓", false, theme, repeat = true) { onKey(SpecialKey.DOWN) }
            KeyButton("→", false, theme, repeat = true) { onKey(SpecialKey.RIGHT) }
            KeyButton("ENT", false, theme) { onKey(SpecialKey.ENTER) }
        }
        } // 常驻两行高度量测结束（行 3/4 在外层，不计入 onBaseHeightChanged）
        // 行 3/4（▾ 展开）：低频键一步直达，不遮挡画布；每行保持 8 键等宽
        if (expanded) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                KeyButton("PST", false, theme) { onPaste() }
                KeyButton("⇧⇥", false, theme) { onKey(SpecialKey.SHIFT_TAB) }
                KeyButton("DEL", false, theme) { onKey(SpecialKey.DEL) }
                KeyButton("HOME", false, theme) { onKey(SpecialKey.HOME) }
                KeyButton("END", false, theme) { onKey(SpecialKey.END) }
                KeyButton("PGUP", false, theme) { onKey(SpecialKey.PGUP) }
                KeyButton("PGDN", false, theme) { onKey(SpecialKey.PGDN) }
                KeyButton("⌃\\", false, theme) { onKey(SpecialKey.CTRL_BACKSLASH) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                KeyButton("F1", false, theme) { onKey(SpecialKey.F1) }
                KeyButton("F2", false, theme) { onKey(SpecialKey.F2) }
                KeyButton("F3", false, theme) { onKey(SpecialKey.F3) }
                KeyButton("F4", false, theme) { onKey(SpecialKey.F4) }
                KeyButton("F5", false, theme) { onKey(SpecialKey.F5) }
                KeyButton("F6", false, theme) { onKey(SpecialKey.F6) }
                KeyButton("F7", false, theme) { onKey(SpecialKey.F7) }
                // Git 面板入口（画布 FAB 的备用入口；F8–F12 极低频不占位）
                KeyButton("⎇", false, theme) { onGit() }
            }
        }
    }
}

/**
 * 可复用 KeyButton：支持三种手感。
 * - repeat=true：按住连发（方向键等导航键）
 * - onLongPress：长按宏（如 ESC 双发、⌃C→⌃\）
 * - 二者互斥：连发优先；无连发且有 onLongPress 时长按触发宏一次
 */
@Composable
private fun RowScope.KeyButton(
    label: String,
    active: Boolean,
    theme: TerminalTheme,
    repeat: Boolean = false,
    /** 图标键（如展开箭头）：渲染大号 Material 图标而非文字。 */
    icon: ImageVector? = null,
    iconSize: Dp = 24.dp,
    // 注意参数顺序：onTap 必须是最后一个参数（调用处大量使用尾随 lambda），
    // 误放 onLongPress 在末尾会让尾随 lambda 绑到长按回调——单击失灵、方向键全废
    onLongPress: (() -> Unit)? = null,
    onTap: () -> Unit = {},
) {
    val bg = if (active) theme.cursor() else Color.Transparent
    val fg = if (active) theme.background() else theme.foreground()

    // 按住连发状态机：pressing=true 期间 LaunchedEffect 循环发送
    var pressing by remember { mutableStateOf(false) }
    if (repeat) {
        LaunchedEffect(pressing) {
            if (!pressing) return@LaunchedEffect
            delay(REPEAT_INITIAL_DELAY_MS)
            while (isActive && pressing) {
                onTap()
                delay(REPEAT_INTERVAL_MS)
            }
        }
        DisposableEffect(Unit) {
            onDispose { pressing = false }
        }
    }

    Box(
        Modifier
            .weight(1f)
            // 正方形键：高随宽（weight 均分），上限 48dp（Android 触控目标上限，
            // 防 desktop 宽窗口键变成巨块）；写死高度会出现 44×40 的长方形
            .heightIn(max = 48.dp)
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.small)
            .background(bg)
            .border(1.dp, fg.copy(alpha = 0.45f), MaterialTheme.shapes.small)
            .then(
                if (repeat) {
                    // pointerInput 方案：press/release 全自管，才能做按住连发
                    Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            pressing = true
                            onTap()
                            // 等待抬起或取消；连发键无长按语义，不做单击消歧
                            try {
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val released = ev.changes.all { !it.pressed }
                                    val consumed = ev.changes.any { it.isConsumed }
                                    if (released || consumed) break
                                }
                            } catch (_: CancellationException) {
                                // 抬起时 awaitPointerEvent 抛取消属正常路径
                            } finally {
                                pressing = false
                            }
                        }
                    }
                } else {
                    // combinedClickable：单击 + 可选长按宏
                    Modifier.combinedClickable(
                        onClick = onTap,
                        onLongClick = onLongPress,
                    )
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(iconSize))
        } else {
            Text(
                label,
                color = fg,
                // 两级字号保持视觉一致：符号键（↑↓←→ ⌨ / ⎇）与 22dp 图标
                // 同档（≈14sp 视觉）；多字符文字键（ESC/CTRL/F1…）11sp。
                // 简单按长度分档：单字符=符号，其余=文字标签
                style = if (label.length == 1) {
                    MaterialTheme.typography.labelLarge
                } else {
                    MaterialTheme.typography.labelSmall
                },
                maxLines = 1,
            )
        }
    }
}

/** 长按宏专用别名（无连发）。 */
@Composable
private fun RowScope.RepeatKeyButton(
    label: String,
    theme: TerminalTheme,
    onTap: () -> Unit,
    onLongPress: (() -> Unit)? = null,
) = KeyButton(label, false, theme, repeat = false, onLongPress = onLongPress, onTap = onTap)
