// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.assets

import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import javax.imageio.ImageIO

/**
 * An image is decoded to its full pixel size whenever a document embeds it: a small file that
 * declares a huge size (a "decompression bomb") would take a render node's memory. Its size is read
 * from the header, without decoding, and refused above [ImagePixelPolicy.MAX_PIXELS].
 */
class ImagePixelPolicyTest {

    /** A PNG that is only a header claiming [width] × [height]: a few dozen bytes. */
    private fun pngClaiming(width: Int, height: Int): ByteArray {
        val ihdr = ByteBuffer.allocate(13).putInt(width).putInt(height).put(8).put(2).put(0).put(0).put(0).array()
        val chunk = ByteBuffer.allocate(4 + 13).put("IHDR".toByteArray()).put(ihdr).array()
        val crc = CRC32().apply { update(chunk) }.value.toInt()
        return byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) +
            ByteBuffer.allocate(4).putInt(13).array() + chunk + ByteBuffer.allocate(4).putInt(crc).array()
    }

    private fun realPng(width: Int, height: Int) = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()

    private fun upload(content: ByteArray) = UploadAsset(testCatalog(TenantKey("testtenant"), CatalogKey.DEFAULT), "image.png", AssetMediaType.fromMimeType("image/png"), content, null, null)

    @Test
    fun `the size is read from the header without decoding the pixels`() {
        assertThat(ImagePixelPolicy.dimensions(AssetMediaType.fromMimeType("image/png"), pngClaiming(50_000, 50_000))).isEqualTo(50_000 to 50_000)
        assertThat(ImagePixelPolicy.dimensions(AssetMediaType.fromMimeType("image/png"), realPng(40, 30))).isEqualTo(40 to 30)
    }

    @Test
    fun `an image claiming more pixels than a render can hold is refused`() {
        assertThatThrownBy { upload(pngClaiming(50_000, 50_000)) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("50000 × 50000")
    }

    @Test
    fun `an ordinary image is accepted`() {
        assertThat(upload(realPng(1200, 800)).name).isEqualTo("image.png")
    }
}
