package dev.termish.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.RectF
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.caverock.androidsvg.SVG

actual fun decodeImage(
    bytes: ByteArray,
    isSvg: Boolean,
): ImageBitmap? =
    runCatching {
        if (!isSvg) return@runCatching BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        // 不解析实体，不注册外部资源解析器：预览只渲染当前文档，不读取其他文件或网络。
        SVG.setInternalEntitiesEnabled(false)
        val svg = SVG.getFromString(bytes.decodeToString(throwOnInvalidSequence = true))
        val box = svg.documentViewBox
        val size = svgPreviewSize(svg.documentWidth, svg.documentHeight, box?.width() ?: 0f, box?.height() ?: 0f)
        svg.documentWidth = size.viewportWidth
        svg.documentHeight = size.viewportHeight
        val bitmap = Bitmap.createBitmap(size.pixelWidth, size.pixelHeight, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.scale(size.pixelWidth / size.viewportWidth, size.pixelHeight / size.viewportHeight)
            svg.renderToCanvas(canvas, RectF(0f, 0f, size.viewportWidth, size.viewportHeight))
            bitmap.asImageBitmap()
        } catch (e: Exception) {
            bitmap.recycle()
            throw e
        }
    }.getOrNull()
