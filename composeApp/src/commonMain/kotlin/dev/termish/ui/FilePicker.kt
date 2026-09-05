package dev.termish.ui

import androidx.compose.runtime.Composable

/** 选中文件的流式读取句柄：每调一次 [readChunk] 返回下一块、null = EOF。 */
data class PickedFile(
    /** 平台稳定标识（Android URI / iOS URL），用于跨批次去重。 */
    val sourceId: String,
    val name: String,
    val size: Long,
    val readChunk: () -> ByteArray?,
    /** 未读到 EOF（取消、移除、失败）时主动释放底层文件句柄。允许重复调用。 */
    val close: () -> Unit,
    /** 为可重试上传重新打开一个独立 reader；旧平台实现为空时退回一次性 reader。 */
    val openReader: (() -> PickedFileReader?)? = null,
)

data class PickedFileReader(
    val readChunk: () -> ByteArray?,
    val close: () -> Unit,
)

/** 追加附件并按平台文件标识去重；被拒绝的新句柄立即释放。 */
internal fun appendUniquePickedFile(
    files: List<PickedFile>,
    candidate: PickedFile,
): List<PickedFile> {
    if (files.any { it.sourceId == candidate.sourceId }) {
        candidate.close()
        return files
    }
    return files + candidate
}

/** 移除附件并释放其底层文件句柄。 */
internal fun removePickedFile(
    files: List<PickedFile>,
    removed: PickedFile,
): List<PickedFile> {
    removed.close()
    return files.filterNot { it.sourceId == removed.sourceId }
}

/** 清空附件前释放尚未读完的文件句柄。 */
internal fun closePickedFiles(files: List<PickedFile>) {
    files.forEach { it.close() }
}

/**
 * 平台文件选择器（**多选**）：返回一个"打开选择器"的函数；选中后**每个文件
 * 回调一次** [onPicked]（名称 + 总大小 + 取块函数——大文件流式上传不整体驻内存）。
 * Android SAF（OpenMultipleDocuments）/ iOS UIDocumentPicker（多选）。
 */
@Composable
expect fun rememberFilePicker(onPicked: (PickedFile) -> Unit): () -> Unit
