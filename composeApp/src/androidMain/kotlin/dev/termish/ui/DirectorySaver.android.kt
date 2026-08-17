package dev.termish.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.documentfile.provider.DocumentFile

/** Android：SAF OpenDocumentTree 选目标目录，在树内创建 <远端目录名> 子目录再写入（与 iOS 语义一致）。 */
@Composable
actual fun rememberDirectorySaver(onReady: (name: String, sink: DirectorySink) -> Unit): (name: String) -> Unit {
    val context = LocalContext.current
    // 记住本次保存的远端目录名：用户选定目标目录后在其下建同名子目录
    //（OpenDocumentTree 无法预设目录名，只能在回调里拿到用户选择）
    var pendingName by remember { mutableStateOf("download") }
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
            val name = sanitizeDirName(pendingName)
            if (root != null) {
                onReady(name, object : DirectorySink {
                    /** 本次下载在用户选定目录下创建的 <远端目录名> 根目录（同名已存在则追加序号）。 */
                    var createdRoot: DocumentFile? = null

                    override fun openFile(relativePath: String): FileSink {
                        val parts = relativePath.split('/')
                        val base = root ?: throw IllegalStateException("保存目录不可用")
                        val rootDir = createdRoot ?: run {
                            var candidate = name
                            var n = 1
                            while (base.findFile(candidate) != null) {
                                candidate = "${name}_$n"
                                n++
                            }
                            val d = base.createDirectory(candidate)
                                ?: throw IllegalStateException("无法创建目录: $candidate")
                            createdRoot = d
                            d
                        }
                        var dir = rootDir
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
    return { name ->
        pendingName = name
        launcher.launch(null)
    }
}
