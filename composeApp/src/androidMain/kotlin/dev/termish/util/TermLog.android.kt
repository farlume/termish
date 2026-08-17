package dev.termish.util

import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import dev.termish.AppContext
import java.io.File

/** Android：debuggable 构建才打点（release 自动关闭，无需 BuildConfig）。 */
actual val termLogEnabled: Boolean = runCatching {
    AppContext.get().applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
}.getOrDefault(false)

actual fun platformLog(level: Char, tag: String, msg: String) {
    when (level) {
        'D' -> Log.d(tag, msg)
        'I' -> Log.i(tag, msg)
        'W' -> Log.w(tag, msg)
        else -> Log.e(tag, msg)
    }
}

/** 日志目录：files/logs（App 私有，无需权限）。 */
actual fun logFileDirectory(): String? = runCatching {
    File(AppContext.get().filesDir, "logs").apply { mkdirs() }.absolutePath
}.getOrNull()

/** 系统分享日志文件（FileProvider）。 */
actual fun shareDiagnosticLogs() {
    val context = AppContext.get()
    val dir = logFileDirectory() ?: return
    val file = File(dir, "termish.log")
    if (!file.exists()) return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(Intent.createChooser(intent, "分享诊断日志").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
