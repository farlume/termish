package dev.termish.ui

import androidx.compose.runtime.Composable

/** 文件保存目标：下载过程中流式写入；写入完成后必须 [close]。 */
interface FileSink {
    fun write(bytes: ByteArray)

    fun close()
}

/**
 * 平台文件保存器：返回"选择保存位置并开始写入"的函数（入参为建议文件名）。
 * 用户选定目标后回调 [onReady]，携带可写入的 [FileSink]；用户取消则不回调。
 * 下载完成 / 失败后由调用方负责 [FileSink.close]。
 */
@Composable
expect fun rememberFileSaver(onReady: (name: String, sink: FileSink) -> Unit): (name: String) -> Unit
