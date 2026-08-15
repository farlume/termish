package dev.mssh.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.mssh.ui.theme.TerminalTheme

/** 特殊键。CTRL/ALT 为粘性修饰键，仅影响后续输入。 */
enum class SpecialKey(val label: String) {
    ESC("ESC"), TAB("TAB"), CTRL("CTRL"), ALT("ALT"),
    UP("↑"), DOWN("↓"), LEFT("←"), RIGHT("→"),
    HOME("HOM"), END("END"), PGUP("PGU"), PGDN("PGD"),
    CTRL_C("⌃C"), CTRL_D("⌃D"), CTRL_L("⌃L"), CTRL_Z("⌃Z"),
    ENTER("ENT"), BACKSPACE("⌫"), DEL("DEL"),
    F1("F1"), F2("F2"), F3("F3"), F4("F4"), F5("F5"),
    F6("F6"), F7("F7"), F8("F8"), F9("F9"),
    F10("F10"), F11("F11"), F12("F12"),
}

fun specialKeyBytes(key: SpecialKey, applicationCursorKeys: Boolean): ByteArray = when (key) {
    SpecialKey.ESC -> byteArrayOf(0x1b)
    SpecialKey.TAB -> byteArrayOf(0x09)
    SpecialKey.UP -> escapeSeq(if (applicationCursorKeys) "OA" else "A")
    SpecialKey.DOWN -> escapeSeq(if (applicationCursorKeys) "OB" else "B")
    SpecialKey.RIGHT -> escapeSeq(if (applicationCursorKeys) "OC" else "C")
    SpecialKey.LEFT -> escapeSeq(if (applicationCursorKeys) "OD" else "D")
    SpecialKey.HOME -> escapeSeq(if (applicationCursorKeys) "OH" else "H")
    SpecialKey.END -> escapeSeq(if (applicationCursorKeys) "OF" else "F")
    SpecialKey.PGUP -> escapeSeq("5~")
    SpecialKey.PGDN -> escapeSeq("6~")
    SpecialKey.DEL -> escapeSeq("3~")
    SpecialKey.CTRL_C -> byteArrayOf(0x03)
    SpecialKey.CTRL_D -> byteArrayOf(0x04)
    SpecialKey.CTRL_L -> byteArrayOf(0x0c)
    SpecialKey.CTRL_Z -> byteArrayOf(0x1a)
    SpecialKey.ENTER -> byteArrayOf(0x0d)
    SpecialKey.BACKSPACE -> byteArrayOf(0x7f)
    // xterm：F1-F4 = ESC O P/Q/R/S，F5-F12 = ESC [ 15/17/18/19/20/21/23/24 ~
    SpecialKey.F1 -> "OP".encodeToByteArray()
    SpecialKey.F2 -> "OQ".encodeToByteArray()
    SpecialKey.F3 -> "OR".encodeToByteArray()
    SpecialKey.F4 -> "OS".encodeToByteArray()
    SpecialKey.F5 -> escapeSeq("15~")
    SpecialKey.F6 -> escapeSeq("17~")
    SpecialKey.F7 -> escapeSeq("18~")
    SpecialKey.F8 -> escapeSeq("19~")
    SpecialKey.F9 -> escapeSeq("20~")
    SpecialKey.F10 -> escapeSeq("21~")
    SpecialKey.F11 -> escapeSeq("23~")
    SpecialKey.F12 -> escapeSeq("24~")
    SpecialKey.CTRL, SpecialKey.ALT -> ByteArray(0)
}

private fun escapeSeq(suffix: String): ByteArray = ("[" + suffix).encodeToByteArray()

/**
 * 终端功能键工具栏：默认两行常驻 + ▾ 展开全功能。
 *
 * 常驻（高频）：⌨ CTRL ALT ESC TAB ⌃C + 方向键倒 T + ENT/⌫/PST/HOM/END。
 * 展开（低频）：F1-F12、PGU/PGD/DEL、⌃D/⌃Z/⌃L。
 * 粘性 CTRL/ALT + 系统键盘字母即可敲出 ⌃A/⌃E/⌃R 等组合，不设专用按钮；
 * 符号键由系统键盘提供。
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
    theme: TerminalTheme,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (expanded) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                KeyButton("F1", false, theme) { onKey(SpecialKey.F1) }
                KeyButton("F2", false, theme) { onKey(SpecialKey.F2) }
                KeyButton("F3", false, theme) { onKey(SpecialKey.F3) }
                KeyButton("F4", false, theme) { onKey(SpecialKey.F4) }
                KeyButton("F5", false, theme) { onKey(SpecialKey.F5) }
                KeyButton("F6", false, theme) { onKey(SpecialKey.F6) }
                KeyButton("F7", false, theme) { onKey(SpecialKey.F7) }
                KeyButton("F8", false, theme) { onKey(SpecialKey.F8) }
                KeyButton("F9", false, theme) { onKey(SpecialKey.F9) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                KeyButton("F10", false, theme) { onKey(SpecialKey.F10) }
                KeyButton("F11", false, theme) { onKey(SpecialKey.F11) }
                KeyButton("F12", false, theme) { onKey(SpecialKey.F12) }
                KeyButton("PGU", false, theme) { onKey(SpecialKey.PGUP) }
                KeyButton("PGD", false, theme) { onKey(SpecialKey.PGDN) }
                KeyButton("DEL", false, theme) { onKey(SpecialKey.DEL) }
                KeyButton("⌃D", false, theme) { onKey(SpecialKey.CTRL_D) }
                KeyButton("⌃Z", false, theme) { onKey(SpecialKey.CTRL_Z) }
                KeyButton("⌃L", false, theme) { onKey(SpecialKey.CTRL_L) }
            }
        }
        // 常驻行 1：高频功能键 + ↑（第 8 列）+ 展开/收起
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            KeyButton("⌨", false, theme) { onToggleKeyboard() }
            KeyButton("CTRL", ctrlActive, theme) { onToggleCtrl() }
            KeyButton("ALT", altActive, theme) { onToggleAlt() }
            KeyButton("ESC", false, theme) { onKey(SpecialKey.ESC) }
            KeyButton("TAB", false, theme) { onKey(SpecialKey.TAB) }
            KeyButton("⌃C", false, theme) { onKey(SpecialKey.CTRL_C) }
            Spacer(Modifier.weight(1f))
            KeyButton("↑", false, theme) { onKey(SpecialKey.UP) }
            KeyButton(if (expanded) "▴" else "▾", false, theme) { expanded = !expanded }
        }
        // 常驻行 2：编辑/导航键 + ← ↓ →（↓ 与上行 ↑ 对齐）
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            KeyButton("ENT", false, theme) { onKey(SpecialKey.ENTER) }
            KeyButton("⌫", false, theme) { onKey(SpecialKey.BACKSPACE) }
            KeyButton("PST", false, theme) { onPaste() }
            KeyButton("HOM", false, theme) { onKey(SpecialKey.HOME) }
            KeyButton("END", false, theme) { onKey(SpecialKey.END) }
            Spacer(Modifier.weight(1f))
            KeyButton("←", false, theme) { onKey(SpecialKey.LEFT) }
            KeyButton("↓", false, theme) { onKey(SpecialKey.DOWN) }
            KeyButton("→", false, theme) { onKey(SpecialKey.RIGHT) }
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
