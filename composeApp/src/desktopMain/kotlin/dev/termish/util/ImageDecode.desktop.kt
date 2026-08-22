package dev.termish.util

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

actual fun decodeImage(bytes: ByteArray): ImageBitmap? = ImageIO.read(ByteArrayInputStream(bytes))?.toComposeImageBitmap()
