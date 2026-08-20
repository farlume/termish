package dev.termish.ui

import androidx.compose.runtime.Composable

/** 选中文件的流式读取句柄：每调一次 [readChunk] 返回下一块、null = EOF。 */
data class PickedFile(
    val name: String,
    val size: Long,
    val readChunk: () -> ByteArray?,
)

/**
 * 平台文件选择器（**多选**）：返回一个"打开选择器"的函数；选中后**每个文件
 * 回调一次** [onPicked]（名称 + 总大小 + 取块函数——大文件流式上传不整体驻内存）。
 * Android SAF（OpenMultipleDocuments）/ iOS UIDocumentPicker（多选）/ 桌面 JFileChooser。
 */
@Composable
expect fun rememberFilePicker(
    onPicked: (PickedFile) -> Unit,
): () -> Unit
