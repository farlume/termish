package dev.termish.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private const val CHUNK = 64 * 1024

@Composable
actual fun rememberFilePicker(
    onPicked: (name: String, size: Long, readChunk: () -> ByteArray?) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val name = queryName(context, uri) ?: "file"
            // ContentResolver 流式读：选择器回调只包流，readChunk 逐块拉取，
            // 任意大小文件内存峰值 = 64KB（此前 readBytes() 全量驻堆，大文件 OOM）
            val stream = context.contentResolver.openInputStream(uri)
            if (stream != null) {
                val size = querySize(context, uri)
                onPicked(name, size) {
                    val buf = ByteArray(CHUNK)
                    val n = stream.read(buf)
                    if (n < 0) {
                        stream.close()
                        null
                    } else {
                        buf.copyOf(n)
                    }
                }
            }
        }
    }
    return { launcher.launch(arrayOf("*/*")) }
}

private fun queryName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
    }

private fun querySize(context: Context, uri: Uri): Long =
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (idx >= 0 && cursor.moveToFirst()) {
            if (!cursor.isNull(idx)) cursor.getLong(idx).coerceAtLeast(0L) else 0L
        } else {
            0L
        }
    } ?: 0L
