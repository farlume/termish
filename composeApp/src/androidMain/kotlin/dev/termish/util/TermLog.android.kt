package dev.termish.util

import android.content.pm.ApplicationInfo
import android.util.Log
import dev.termish.AppContext

/** Android：debuggable 构建才打点（release 自动关闭，无需 BuildConfig）。 */
actual val termLogEnabled: Boolean = runCatching {
    AppContext.get().applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
}.getOrDefault(false)

actual fun platformLog(level: Char, tag: String, msg: String) {
    when (level) {
        'D' -> Log.d(tag, msg)
        'I' -> Log.i(tag, msg)
        'W' -> Log.w(tag, msg)
        else -> Log.e(tag, msg)
    }
}
