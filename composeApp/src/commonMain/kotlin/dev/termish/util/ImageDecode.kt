package dev.termish.util

import androidx.compose.ui.graphics.ImageBitmap

/**
 * 解码图片字节为 [ImageBitmap]（SFTP 图片预览用）。
 * 失败（损坏/不支持的格式）返回 null，由 UI 层提示。
 * Android=BitmapFactory，iOS=UIImage，桌面=ImageIO。
 */
expect fun decodeImage(bytes: ByteArray): ImageBitmap?
