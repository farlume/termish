package dev.termish.util

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageDecodeTest {
    private fun svg(
        attributes: String,
        content: String = "",
    ) = """<svg xmlns="http://www.w3.org/2000/svg" $attributes>$content</svg>""".encodeToByteArray()

    @Test
    fun rendersPathsAndPreservesTransparency() {
        val bytes = svg("""width="100" height="50"""", """<path fill="#ff0000" d="M0 0H50V50H0Z"/>""")
        val bitmap = assertNotNull(decodeImage(bytes, isSvg = true)).asAndroidBitmap()
        assertEquals(100, bitmap.width)
        assertEquals(50, bitmap.height)
        assertEquals(Color.RED, bitmap.getPixel(25, 25))
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(75, 25))
    }

    @Test
    fun viewBoxOnlyAndPercentageDimensionsRender() {
        for (dimensions in listOf("", """width="100%" height="100%"""")) {
            val bitmap =
                assertNotNull(
                    decodeImage(svg("""viewBox="0 0 80 40" $dimensions""", """<rect width="80" height="40" fill="blue"/>"""), true),
                ).asAndroidBitmap()
            assertEquals(80, bitmap.width)
            assertEquals(40, bitmap.height)
            assertEquals(Color.BLUE, bitmap.getPixel(79, 39))
        }
    }

    @Test
    fun downscalesLargeCanvasWithoutClippingContent() {
        val bitmap =
            assertNotNull(
                decodeImage(
                    svg("""width="100000" height="50000"""", """<rect x="50000" width="50000" height="50000" fill="red"/>"""),
                    true,
                ),
            ).asAndroidBitmap()
        assertEquals(2048, bitmap.width)
        assertEquals(1024, bitmap.height)
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(100, 500))
        assertEquals(Color.RED, bitmap.getPixel(2000, 500))
    }

    @Test
    fun usesDefaultViewportWhenSizeIsMissing() {
        val bitmap = assertNotNull(decodeImage(svg("", """<rect width="100%" height="100%" fill="red"/>"""), true)).asAndroidBitmap()
        assertEquals(300, bitmap.width)
        assertEquals(150, bitmap.height)
        assertEquals(Color.RED, bitmap.getPixel(299, 149))
    }

    @Test
    fun malformedSvgReturnsFailure() {
        assertNull(decodeImage("<svg><path".encodeToByteArray(), true))
        assertNull(decodeImage("not an image".encodeToByteArray(), true))
        assertNull(decodeImage(byteArrayOf(0x1f, 0x8b.toByte(), 0, 0), true))
    }

    @Test
    fun bitmapDecodingStillWorks() {
        val source = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream()
        source.compress(Bitmap.CompressFormat.PNG, 100, bytes)
        val bitmap = assertNotNull(decodeImage(bytes.toByteArray()))
        assertEquals(2, bitmap.width)
        assertEquals(3, bitmap.height)
    }
}
