package dev.termish.ui

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Android：直接下载到公共 Download 目录（MediaStore.Downloads，Android 10+），
 * 不弹「另存为」对话框——移动端惯例是「下载即进 Download 目录」。同名文件由
 * MediaStore 自动去重（name (1).ext），IS_PENDING 占位直到写完。
 * Android 9 及以下（无 scoped storage）回退到 SAF 另存为。
 */
@Composable
actual fun rememberFileSaver(onReady: (name: String, sink: FileSink) -> Unit): (name: String) -> Unit {
    val context = LocalContext.current
    // API < 29 回退：SAF CreateDocument 让用户选保存位置
    val safLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
        if (uri != null) {
            val out = context.contentResolver.openOutputStream(uri)
            if (out != null) {
                onReady(queryName(context, uri) ?: "file", object : FileSink {
                    override fun write(bytes: ByteArray) = out.write(bytes)

                    override val openUri: String? = uri.toString()

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
    return { name ->
        if (Build.VERSION.SDK_INT >= 29) {
            val uri = insertDownload(context, name)
            if (uri != null) {
                val out = context.contentResolver.openOutputStream(uri)
                if (out != null) {
                    // 取实际文件名（MediaStore 同名自动去重后可能变成 name (1).ext）
                    val actualName = queryName(context, uri) ?: name
                    onReady(actualName, object : FileSink {
                        override fun write(bytes: ByteArray) = out.write(bytes)

                        override val openUri: String? = uri.toString()

                        override fun close() {
                            try {
                                out.close()
                            } catch (_: Exception) {
                            }
                            // 清除 IS_PENDING，文件转为可见的完成态
                            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                            context.contentResolver.update(uri, done, null, null)
                        }
                    })
                }
            }
        } else {
            safLauncher.launch(name)
        }
    }
}

/** MediaStore.Downloads 插入（IS_PENDING=1 占位）；同名由 MediaStore 自动去重。 */
@RequiresApi(29)
private fun insertDownload(context: Context, name: String): Uri? {
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, name)
        put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    return context.contentResolver.insert(
        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        values,
    )
}

private fun queryName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
    }
