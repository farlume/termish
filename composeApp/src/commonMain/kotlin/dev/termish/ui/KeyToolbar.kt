package dev.termish.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.termish.ui.theme.TerminalTheme

/** 特殊键。CTRL/ALT 为粘性修饰键，仅影响后续输入。
 *  低频键（F1–F12 / HOM / END / PGUP / PGDN / DEL / ⌫ / ⌃Z）随固定两行工具栏
 *  设计移除（17d8db2），如需重新暴露，转义映射见该 commit 之前的展开层实现。 */
enum class SpecialKey(val label: String) {
    ESC("ESC"), TAB("TAB"), CTRL("CTRL"), ALT("ALT"),
    UP("↑"), DOWN("↓"), LEFT("←"), RIGHT("→"),
    CTRL_C("⌃C"), CTRL_D("⌃D"), CTRL_L("⌃L"), CTRL_E("⌃E"),
    ENTER("ENT"),
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
    SpecialKey.CTRL, SpecialKey.ALT -> ByteArray(0)
}

private fun escapeSeq(suffix: String): ByteArray = ("[" + suffix).encodeToByteArray()

/**
 * 终端功能键工具栏：固定两行（无展开层）。
 *
 * 行 1（修饰/控制）：CTRL ALT ESC TAB ⌃C ↑ ⌃L ⌨（两行均 8 列，按钮更宽）。
 * 行 2（编辑/导航）：⌃D PST / + 一个空位 + ← ↓ → ENT；
 * ↑ 与 ↓ 上下对齐，⌨ 与 ENT 同在右上/右下角拇指区。
 * 粘性 CTRL/ALT + 系统键盘字母即可敲出 ⌃A/⌃E/⌃R 等组合，不设专用按钮；
 * 符号键由系统键盘提供；画布惯性滚动替代 PgUp/PgDn。
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
    /** 普通字符键（如 /），直接作为终端输入发送。 */
    onChar: (String) -> Unit = {},
    theme: TerminalTheme,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 行 1：修饰/控制 + ↑（第 6 列，与行 2 的 ↓ 上下对齐）+ ⌨（右上角）
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            KeyButton("CTRL", ctrlActive, theme) { onToggleCtrl() }
            KeyButton("ALT", altActive, theme) { onToggleAlt() }
            KeyButton("ESC", false, theme) { onKey(SpecialKey.ESC) }
            KeyButton("TAB", false, theme) { onKey(SpecialKey.TAB) }
            KeyButton("⌃C", false, theme) { onKey(SpecialKey.CTRL_C) }
            KeyButton("↑", false, theme) { onKey(SpecialKey.UP) }
            KeyButton("⌃L", false, theme) { onKey(SpecialKey.CTRL_L) }
            KeyButton("⌨", false, theme) { onToggleKeyboard() }
        }
        // 行 2：⌃D/PST 左侧、两个空位、方向键 + ENT 右侧（8 列均分，按钮与行 1 等宽）
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            KeyButton("⌃D", false, theme) { onKey(SpecialKey.CTRL_D) }
            KeyButton("PST", false, theme) { onPaste() }
            KeyButton("/", false, theme) { onChar("/") }
            KeyButton("⌃E", false, theme) { onKey(SpecialKey.CTRL_E) }
            KeyButton("←", false, theme) { onKey(SpecialKey.LEFT) }
            KeyButton("↓", false, theme) { onKey(SpecialKey.DOWN) }
            KeyButton("→", false, theme) { onKey(SpecialKey.RIGHT) }
            KeyButton("ENT", false, theme) { onKey(SpecialKey.ENTER) }
        }
    }
}

/** 等宽按钮：weight(1f) 均分行宽。 */
@Composable
private fun RowScope.KeyButton(
    label: String,
    active: Boolean,
    theme: TerminalTheme,
    onClick: () -> Unit,
) {
    val bg = if (active) theme.cursor() else Color.Transparent
    val fg = if (active) theme.background() else theme.foreground()
    Box(
        Modifier
            .weight(1f)
            .height(40.dp)
            .clip(MaterialTheme.shapes.small)
            .background(bg)
            .border(1.dp, fg.copy(alpha = 0.45f), MaterialTheme.shapes.small)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}
