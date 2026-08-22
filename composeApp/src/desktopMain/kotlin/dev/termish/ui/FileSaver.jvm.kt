package dev.termish.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import java.io.File
import javax.swing.JFileChooser
import kotlinx.coroutines.launch

/** Desktop：JFileChooser 保存对话框，选完后在 EDT 上回调。 */
@Composable
actual fun rememberFileSaver(onReady: (name: String, sink: FileSink) -> Unit): (name: String) -> Unit {
    val main = rememberCoroutineScope()
    return { name ->
        Thread {
            val chooser = JFileChooser()
            chooser.selectedFile = File(name)
            if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                val file = chooser.selectedFile
                val out = file.outputStream()
                main.launch {
                    onReady(
                        file.name,
                        object : FileSink {
                            override fun write(bytes: ByteArray) = out.write(bytes)

                            override fun close() {
                                try {
                                    out.close()
                                } catch (_: Exception) {
                                }
                            }
                        },
                    )
                }
            }
        }.start()
    }
}
