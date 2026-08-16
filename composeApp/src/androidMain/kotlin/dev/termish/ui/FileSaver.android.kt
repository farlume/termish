package dev.termish.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** Android：SAF CreateDocument 让用户选保存位置，返回可写流。 */
@Composable
actual fun rememberFileSaver(onReady: (name: String, sink: FileSink) -> Unit): (name: String) -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*"),
    ) { uri: Uri? ->
        if (uri != null) {
            val out = context.contentResolver.openOutputStream(uri)
            if (out != null) {
                onReady(queryName(context, uri) ?: "file", object : FileSink {
                    override fun write(bytes: ByteArray) = out.write(bytes)

                    override fun close() {
                        try {
                            out.close()
                        } catch (_: Exception) {
                        }
                    }
                })
            }
        }
    }
    return { name -> launcher.launch(name) }
}

private fun queryName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
    }
