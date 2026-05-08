package app.epistola.generation.pdf

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.common.HybridBinarizer
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Custom LuminanceSource that wraps a BufferedImage for ZXing decoding.
 * Avoids adding the zxing:javase dependency just for BufferedImageLuminanceSource.
 */
private class BufferedImageLuminanceSource(private val image: BufferedImage) : LuminanceSource(image.width, image.height) {

    override fun getRow(y: Int, row: ByteArray?): ByteArray {
        var output = row
        if (output == null || output.size < width) {
            output = ByteArray(width)
        }
        for (x in 0 until width) {
            output[x] = getLuminance(x, y)
        }
        return output
    }

    override fun getMatrix(): ByteArray {
        val matrix = ByteArray(width * height)
        var offset = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                matrix[offset++] = getLuminance(x, y)
            }
        }
        return matrix
    }

    override fun isCropSupported(): Boolean = true

    override fun crop(left: Int, top: Int, width: Int, height: Int): LuminanceSource {
        val cropped = image.getSubimage(left, top, width, height)
        return BufferedImageLuminanceSource(cropped)
    }

    private fun getLuminance(x: Int, y: Int): Byte {
        val rgb = image.getRGB(x, y)
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        // Standard luminance formula
        return ((0.299 * r + 0.587 * g + 0.114 * b).toInt()).toByte()
    }
}

/**
 * Decodes a QR code PNG byte array back to the original string using ZXing.
 */
private fun decodeQrCodePng(pngBytes: ByteArray): String? {
    val image = ByteArrayInputStream(pngBytes).use { ImageIO.read(it) }
        ?: return null

    val source = BufferedImageLuminanceSource(image)
    val bitmap = BinaryBitmap(HybridBinarizer(source))
    val reader = MultiFormatReader()
    val hints = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
    )
    return try {
        val result = reader.decode(bitmap, hints)
        result.text
    } catch (_: NotFoundException) {
        null
    }
}

/**
 * Creates a small square logo image for testing.
 */
private fun createTestLogo(size: Int = 32): BufferedImage {
    val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.color = java.awt.Color.RED
    g.fillRect(0, 0, size, size)
    g.dispose()
    return img
}

class QrCodeNodeRendererRoundTripTest {

    private val renderer = QrCodeNodeRenderer()

    @Test
    fun `standard QR decodes short string correctly`() {
        assertRoundTrip("Hello QR", "standard", SMOKE_SIZE_PT, null)
    }

    @Test
    fun `standard QR decodes medium string correctly`() {
        assertRoundTrip("a".repeat(100), "standard", SMOKE_SIZE_PT, null)
    }

    @Test
    fun `standard QR decodes near max string correctly`() {
        assertRoundTrip("c".repeat(2500), "standard", BOUNDARY_SIZE_PT, null)
    }

    @Test
    fun `logo QR decodes short string correctly`() {
        assertRoundTrip("Logo QR", "logo", SMOKE_SIZE_PT, createTestLogo())
    }

    @Test
    fun `logo QR decodes medium string correctly`() {
        assertRoundTrip("y".repeat(100), "logo", SMOKE_SIZE_PT, createTestLogo())
    }

    @Test
    fun `logo QR decodes near max string correctly`() {
        // Level H has lower capacity than L; ~1273 bytes is the practical byte-mode limit.
        assertRoundTrip("z".repeat(1200), "logo", BOUNDARY_SIZE_PT, createTestLogo())
    }

    @Test
    fun `decode returns null for invalid png`() {
        assertEquals(null, decodeQrCodePng(byteArrayOf(1, 2, 3, 4, 5)))
    }

    private fun assertRoundTrip(
        value: String,
        mode: String,
        sizePt: Float,
        logo: BufferedImage?,
    ) {
        val pngBytes = renderer.generateQrCodePng(value, sizePt, mode, logo)
        val decoded = decodeQrCodePng(pngBytes)

        assertEquals(value, decoded)
    }

    private companion object {
        private const val SMOKE_SIZE_PT = 120f
        private const val BOUNDARY_SIZE_PT = 300f
    }
}
