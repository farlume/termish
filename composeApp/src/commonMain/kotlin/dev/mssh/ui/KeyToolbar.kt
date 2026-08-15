package dev.mssh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    HOME("HOME"), END("END"), PGUP("PGUP"), PGDN("PGDN"),
    CTRL_C("⌃C"), CTRL_D("⌃D"), CTRL_Z("⌃Z"), CTRL_L("⌃L"),
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
    SpecialKey.CTRL_C -> byteArrayOf(0x03)
    SpecialKey.CTRL_D -> byteArrayOf(0x04)
    SpecialKey.CTRL_Z -> byteArrayOf(0x1a)
    SpecialKey.CTRL_L -> byteArrayOf(0x0c)
    SpecialKey.CTRL, SpecialKey.ALT -> ByteArray(0)
}

private fun escapeSeq(suffix: String): ByteArray = ("\u001b[" + suffix).encodeToByteArray()

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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        KeyButton("⌨", false, theme) { onToggleKeyboard() }
        KeyButton("CTRL", ctrlActive, theme) { onToggleCtrl() }
        KeyButton("ALT", altActive, theme) { onToggleAlt() }
        KeyButton("ESC", false, theme) { onKey(SpecialKey.ESC) }
        KeyButton("TAB", false, theme) { onKey(SpecialKey.TAB) }
        KeyButton("⌃C", false, theme) { onKey(SpecialKey.CTRL_C) }
        KeyButton("⌃D", false, theme) { onKey(SpecialKey.CTRL_D) }
        KeyButton("⌃Z", false, theme) { onKey(SpecialKey.CTRL_Z) }
        KeyButton("⌃L", false, theme) { onKey(SpecialKey.CTRL_L) }
        KeyButton("↑", false, theme) { onKey(SpecialKey.UP) }
        KeyButton("↓", false, theme) { onKey(SpecialKey.DOWN) }
        KeyButton("←", false, theme) { onKey(SpecialKey.LEFT) }
        KeyButton("→", false, theme) { onKey(SpecialKey.RIGHT) }
        KeyButton("PASTE", false, theme) { onPaste() }
    }
}

@Composable
private fun KeyButton(label: String, active: Boolean, theme: TerminalTheme, onClick: () -> Unit) {
    val bg = if (active) theme.cursor() else Color.Transparent
    val fg = if (active) theme.background() else theme.foreground()
    Box(
        Modifier
            .height(40.dp)
            .clip(MaterialTheme.shapes.small)
            .background(bg)
            .border(1.dp, fg.copy(alpha = 0.45f), MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, style = MaterialTheme.typography.labelMedium)
    }
}
