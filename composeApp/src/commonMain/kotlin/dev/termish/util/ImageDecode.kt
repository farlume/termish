package dev.termish.util

import androidx.compose.ui.graphics.ImageBitmap

/**
 * 解码图片字节为 [ImageBitmap]（SFTP 图片预览用）。
 * 失败（损坏/不支持的格式）返回 null，由 UI 层提示。
 * 位图使用平台解码器；SVG 静态栅格化，最长边不超过 2048 像素。
 */
expect fun decodeImage(
    bytes: ByteArray,
    isSvg: Boolean = false,
): ImageBitmap?
