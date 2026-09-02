package dev.termish.ui

import androidx.compose.runtime.Composable
import javax.swing.JFileChooser

private const val CHUNK = 64 * 1024

@Composable
actual fun rememberFilePicker(onPicked: (PickedFile) -> Unit): () -> Unit =
    {
        Thread {
            val chooser = JFileChooser()
            chooser.isMultiSelectionEnabled = true
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                // 多选：每个选中文件独立流式读，逐文件回调
                chooser.selectedFiles.forEach { f ->
                    // 文件流式读：readChunk 逐块拉取，大文件不整体驻内存
                    fun openReader(): PickedFileReader {
                        val input = f.inputStream()
                        return PickedFileReader(
                            readChunk = {
                                val buf = ByteArray(CHUNK)
                                val n = input.read(buf)
                                if (n < 0) {
                                    input.close()
                                    null
                                } else {
                                    buf.copyOf(n)
                                }
                            },
                            close = { runCatching { input.close() } },
                        )
                    }
                    val reader = openReader()
                    onPicked(
                        PickedFile(
                            sourceId = f.canonicalPath,
                            name = f.name,
                            size = f.length(),
                            readChunk = reader.readChunk,
                            close = reader.close,
                            openReader = ::openReader,
                        ),
                    )
                }
            }
        }.start()
    }
