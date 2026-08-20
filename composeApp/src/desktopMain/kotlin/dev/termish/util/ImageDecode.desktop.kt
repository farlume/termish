package dev.termish.util

import javax.imageio.ImageIO
import java.io.ByteArrayInputStream
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap

actual fun decodeImage(bytes: ByteArray): ImageBitmap? =
    ImageIO.read(ByteArrayInputStream(bytes))?.toComposeImageBitmap()
