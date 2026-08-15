package dev.mssh.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 特殊键。CTRL/ALT 为粘性修饰键，仅影响后续输入。 */
enum class SpecialKey(val label: String) {
    ESC("ESC"), TAB("TAB"), CTRL("CTRL"), ALT("ALT"),
    UP("↑"), DOWN("↓"), LEFT("←"), RIGHT("→"),
    HOME("HOME"), END("END"), PGUP("PGUP"), PGDN("PGDN"),
    SLASH("/"), PIPE("|"), DASH("-"), TILDE("~"), DOLLAR("$"), DOT("."), COLON(":"),
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
    SpecialKey.SLASH -> "/".encodeToByteArray()
    SpecialKey.PIPE -> "|".encodeToByteArray()
    SpecialKey.DASH -> "-".encodeToByteArray()
    SpecialKey.TILDE -> "~".encodeToByteArray()
    SpecialKey.DOLLAR -> "$".encodeToByteArray()
    SpecialKey.DOT -> ".".encodeToByteArray()
    SpecialKey.COLON -> ":".encodeToByteArray()
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
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        KeyButton("CTRL", ctrlActive) { onToggleCtrl() }
        KeyButton("ALT", altActive) { onToggleAlt() }
        KeyButton("ESC", false) { onKey(SpecialKey.ESC) }
        KeyButton("TAB", false) { onKey(SpecialKey.TAB) }
        KeyButton("↑", false) { onKey(SpecialKey.UP) }
        KeyButton("↓", false) { onKey(SpecialKey.DOWN) }
        KeyButton("←", false) { onKey(SpecialKey.LEFT) }
        KeyButton("→", false) { onKey(SpecialKey.RIGHT) }
        KeyButton("|", false) { onKey(SpecialKey.PIPE) }
        KeyButton("-", false) { onKey(SpecialKey.DASH) }
        KeyButton("~", false) { onKey(SpecialKey.TILDE) }
        KeyButton("$", false) { onKey(SpecialKey.DOLLAR) }
        KeyButton("/", false) { onKey(SpecialKey.SLASH) }
        KeyButton(".", false) { onKey(SpecialKey.DOT) }
        KeyButton(":", false) { onKey(SpecialKey.COLON) }
    }
}

@Composable
private fun KeyButton(label: String, active: Boolean, onClick: () -> Unit) {
    if (active) {
        FilledTonalButton(onClick = onClick, modifier = Modifier.height(40.dp)) {
            Text(label)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = Modifier.height(40.dp)) {
            Text(label)
        }
    }
}
