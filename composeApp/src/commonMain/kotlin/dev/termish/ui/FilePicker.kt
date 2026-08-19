package dev.termish.ui

import androidx.compose.runtime.Composable

/**
 * 平台文件选择器：返回一个"打开选择器"的函数；选中后回调
 * （名称 + 总大小 + 取块函数——每调一次返回下一块、null = EOF，
 * 大文件流式上传不整体驻内存）。Android/desktop/iOS 均已实现。
 */
@Composable
expect fun rememberFilePicker(
    onPicked: (name: String, size: Long, readChunk: () -> ByteArray?) -> Unit,
): () -> Unit
