package dev.termish.ui

import androidx.compose.runtime.Composable

/** 目录保存目标：按相对路径写入文件（自动建父目录），全部完成后 [close]。 */
interface DirectorySink {
    /** 打开/创建 [relativePath] 对应的文件，返回流式写入器；写完必须 close()。 */
    fun openFile(relativePath: String): FileSink

    /**
     * 全部文件写入完成后调用（iOS 在此弹出导出面板）。
     * 仅在【全部成功】时调用；失败路径不调用（避免导出半成品目录）。
     */
    fun close()
}

/** 目录名清洗：去掉路径分隔符与危险字符，空则回退 "download"。三端保存目录命名统一。 */
internal fun sanitizeDirName(name: String): String =
    name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "download" }

/**
 * 平台目录保存器：返回"选择保存位置并开始写入目录"的函数（入参为目录名）。
 * 用户选定目标后回调 [onReady]；取消则不回调。
 */
@Composable
expect fun rememberDirectorySaver(onReady: (name: String, sink: DirectorySink) -> Unit): (name: String) -> Unit
