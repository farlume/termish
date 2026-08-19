package dev.termish.ui

import androidx.compose.runtime.Composable
import java.io.File
import java.io.InputStream
import javax.swing.JFileChooser

private const val CHUNK = 64 * 1024

@Composable
actual fun rememberFilePicker(
    onPicked: (name: String, size: Long, readChunk: () -> ByteArray?) -> Unit,
): () -> Unit = {
    Thread {
        val chooser = JFileChooser()
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            val f: File = chooser.selectedFile
            // 文件流式读：readChunk 逐块拉取，大文件不整体驻内存
            val input: InputStream = f.inputStream()
            onPicked(f.name, f.length()) {
                val buf = ByteArray(CHUNK)
                val n = input.read(buf)
                if (n < 0) {
                    input.close()
                    null
                } else {
                    buf.copyOf(n)
                }
            }
        }
    }.start()
}
