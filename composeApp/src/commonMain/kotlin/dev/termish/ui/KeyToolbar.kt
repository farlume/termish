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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.termish.ui.theme.TerminalTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** 特殊键。CTRL/ALT 为粘性修饰键，仅影响后续输入。
 *  低频键（F1–F12 / HOM / END / PGUP / PGDN / DEL / ⌫ / ⌃Z）随固定两行工具栏
 *  设计移除（17d8db2），如需重新暴露，转义映射见该 commit 之前的展开层实现。 */
enum class SpecialKey(val label: String) {
    ESC("ESC"), TAB("TAB"), CTRL("CTRL"), ALT("ALT"),
    UP("↑"), DOWN("↓"), LEFT("←"), RIGHT("→"),
    CTRL_C("⌃C"), CTRL_D("⌃D"), CTRL_L("⌃L"), CTRL_E("⌃E"),
    ENTER("ENT"),
    /** Shift+Tab（ESC[Z）：TUI 菜单反选；Claude Code 模式切换刚需（软键盘打不出）。 */
    SHIFT_TAB("⇧⇥"),
    /** ⌃\（SIGQUIT）：长按 ⌃C/⌃D 触发，杀连 ⌃C 都不响应的顽固进程。 */
    CTRL_BACKSLASH("⌃\\"),
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
    SpecialKey.ENTER -> byteArrayOf(0x0d)
    SpecialKey.SHIFT_TAB -> byteArrayOf(0x1b, 0x5b, 0x5a) // ESC [ Z
    SpecialKey.CTRL_BACKSLASH -> byteArrayOf(0x1c) // SIGQUIT
    SpecialKey.CTRL, SpecialKey.ALT -> ByteArray(0)
}

private fun escapeSeq(suffix: String): ByteArray = ("\u001b[${suffix}").encodeToByteArray()

/** 按住连发参数（对齐实体键盘手感）：先延迟后连发，越按越快不会失控。 */
private const val REPEAT_INITIAL_DELAY_MS = 400L
private const val REPEAT_INTERVAL_MS = 60L

/**
 * 终端功能键工具栏：固定两行（无展开层）。
 *
 * 行 1（控制）：ESC ⇧⇥ ⌃C ⌃L PST ↑ {} ⌨（ESC 左上、⌨ 右上拇指区）。
 * 行 2（修饰/编辑）：CTRL ALT ⌃D / ← ↓ → ENT（CTRL/ALT 左下，同实体键盘
 * 底行；↑/↓ 第 6 列上下对齐；/ 由 onChar 直接发送）。
 * 两行均 8 列等宽。
 *
 * - 方向键/⌫ 类支持**按住连发**（400ms 后 60ms/次）
 * - 长按宏：**ESC 双发**（Claude Code 编辑历史）；**⌃C → ⌃\\**（SIGQUIT 杀顽固进程）
 * - 粘性 CTRL/ALT + 系统键盘字母即可敲出 ⌃A/⌃E/⌃R 等组合
 * - 符号键由系统键盘提供；画布惯性滚动替代 PgUp/PgDn
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
    /** 命令片段插入面板（行 2 的「{}」键位；原符号键 / 由系统键盘提供）。 */
    onSnippets: () -> Unit = {},
    /** 普通字符键（如 /），直接作为终端输入发送。 */
    onChar: (String) -> Unit = {},
    theme: TerminalTheme,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 行 1：ESC 左上（实体键盘位置直觉）；PST/{}/面板类键靠右；⌨ 右上拇指区
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            RepeatKeyButton("ESC", theme,
                onTap = { onKey(SpecialKey.ESC) },
                onLongPress = { onKey(SpecialKey.ESC); onKey(SpecialKey.ESC) })
            KeyButton("⇧⇥", false, theme) { onKey(SpecialKey.SHIFT_TAB) }
            RepeatKeyButton("⌃C", theme,
                onTap = { onKey(SpecialKey.CTRL_C) },
                onLongPress = { onKey(SpecialKey.CTRL_BACKSLASH) })
            KeyButton("⌃L", false, theme) { onKey(SpecialKey.CTRL_L) }
            KeyButton("PST", false, theme) { onPaste() }
            KeyButton("↑", false, theme, repeat = true) { onKey(SpecialKey.UP) }
            KeyButton("{}", false, theme) { onSnippets() }
            KeyButton("⌨", false, theme) { onToggleKeyboard() }
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
            .height(40.dp)
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
        Text(label, color = fg, style = MaterialTheme.typography.labelSmall, maxLines = 1)
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
