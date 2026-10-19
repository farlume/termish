package dev.termish.util

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import org.jetbrains.skia.impl.use
import org.jetbrains.skia.svg.SVGDOM
import org.jetbrains.skia.svg.SVGLength
import org.jetbrains.skia.svg.SVGLengthContext
import org.jetbrains.skia.svg.SVGLengthUnit

actual fun decodeImage(
    bytes: ByteArray,
    isSvg: Boolean,
): ImageBitmap? =
    runCatching {
        if (!isSvg) return@runCatching Image.makeFromEncoded(bytes).toComposeImageBitmap()
        // Skia 的默认 SVGDOM 不提供外部资源解析器，也不执行脚本。
        Data.makeFromBytes(bytes).use { data ->
            SVGDOM(data).use { svg ->
                val root = svg.root ?: return@runCatching null
                root.use {
                    val intrinsic = root.getIntrinsicSize(SVGLengthContext(0f, 0f, 96f))
                    val box = root.viewBox
                    val size =
                        svgPreviewSize(
                            if (root.width.unit == SVGLengthUnit.PERCENTAGE) 0f else intrinsic.x,
                            if (root.height.unit == SVGLengthUnit.PERCENTAGE) 0f else intrinsic.y,
                            box?.width ?: 0f,
                            box?.height ?: 0f,
                        )
                    root.width = SVGLength(size.viewportWidth)
                    root.height = SVGLength(size.viewportHeight)
                    svg.setContainerSize(size.viewportWidth, size.viewportHeight)
                    Surface.makeRasterN32Premul(size.pixelWidth, size.pixelHeight).use { surface ->
                        surface.canvas.clear(0)
                        surface.canvas.scale(size.pixelWidth / size.viewportWidth, size.pixelHeight / size.viewportHeight)
                        svg.render(surface.canvas)
                        surface.makeImageSnapshot().toComposeImageBitmap()
                    }
                }
            }
        }
    }.getOrNull()
