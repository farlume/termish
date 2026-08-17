package dev.termish.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.documentfile.provider.DocumentFile

/** Android：SAF OpenDocumentTree 选目标目录，按相对路径在树内创建文件。 */
@Composable
actual fun rememberDirectorySaver(onReady: (name: String, sink: DirectorySink) -> Unit): (name: String) -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (_: Exception) {
            }
            val root = DocumentFile.fromTreeUri(context, uri)
            val name = uri.lastPathSegment?.substringAfterLast(':')?.ifBlank { "download" } ?: "download"
            if (root != null) {
                onReady(name, object : DirectorySink {
                    override fun openFile(relativePath: String): FileSink {
                        val parts = relativePath.split('/')
                        var dir = root ?: throw IllegalStateException("保存目录不可用")
                        for (p in parts.dropLast(1)) {
                            dir = dir.findFile(p) ?: dir.createDirectory(p)
                                ?: throw IllegalStateException("无法创建目录: $p")
                        }
                        val file = dir.createFile("application/octet-stream", parts.last())
                            ?: throw IllegalStateException("无法创建文件: ${parts.last()}")
                        val out = context.contentResolver.openOutputStream(file.uri)
                            ?: throw IllegalStateException("无法写入: ${parts.last()}")
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
                })
            }
        }
    }
    return { name -> launcher.launch(null) }
}
