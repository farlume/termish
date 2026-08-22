package dev.termish.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import java.io.File
import javax.swing.JFileChooser
import kotlinx.coroutines.launch

/** Desktop：JFileChooser 选保存目录，按相对路径建子目录写入。 */
@Composable
actual fun rememberDirectorySaver(onReady: (name: String, sink: DirectorySink) -> Unit): (name: String) -> Unit {
    val main = rememberCoroutineScope()
    return { name ->
        Thread {
            val chooser =
                JFileChooser().apply {
                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                    selectedFile = File(name)
                }
            if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                val root = chooser.selectedFile
                main.launch {
                    onReady(
                        root.name,
                        object : DirectorySink {
                            override fun openFile(relativePath: String): FileSink {
                                val f = File(root, relativePath)
                                f.parentFile?.mkdirs()
                                val out = f.outputStream()
                                return object : FileSink {
                                    override fun write(bytes: ByteArray) = out.write(bytes)

                                    override fun close() {
                                        try {
                                            out.close()
                                        } catch (_: Exception) {
                                        }
                                    }
                                }
                            }

                            override fun close() {}
                        },
                    )
                }
            }
        }.start()
    }
}
