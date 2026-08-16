package dev.termish.ui

import androidx.compose.runtime.Composable

/**
 * 平台文件选择器：返回一个"打开选择器"的函数；选中后回调（名称 + 字节内容）。
 * Android/desktop 实现；iOS 暂为 no-op（后续接 UIDocumentPicker）。
 */
@Composable
expect fun rememberFilePicker(onPicked: (name: String, content: ByteArray) -> Unit): () -> Unit
