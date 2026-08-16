package dev.termish.ui

import androidx.compose.runtime.Composable
import java.io.File
import javax.swing.JFileChooser

@Composable
actual fun rememberFilePicker(onPicked: (name: String, content: ByteArray) -> Unit): () -> Unit = {
    Thread {
        val chooser = JFileChooser()
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            val f: File = chooser.selectedFile
            val bytes = f.readBytes()
            onPicked(f.name, bytes)
        }
    }.start()
}
