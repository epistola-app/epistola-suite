// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.assets

import app.epistola.suite.validation.validate
import javax.imageio.ImageIO

/**
 * How large an image may be, in pixels, and its size read without decoding it.
 *
 * Every document that embeds an image decodes it to its full pixel size, so a small file that
 * declares a huge size (a "decompression bomb") would take a render node's memory, and with it every
 * tenant's documents. Its size is read from the file's header only, never by decoding the pixels.
 */
object ImagePixelPolicy {

    /** 40 million pixels: a 7,500 × 5,300 photograph; about 160 MB once decoded. */
    const val MAX_PIXELS: Long = 40_000_000

    /** The width and height [content] declares; null for an SVG, or a file whose header is unreadable. */
    fun dimensions(mediaType: AssetMediaType, content: ByteArray): Pair<Int, Int>? = if (mediaType == AssetMediaType.SVG) null else dimensions(content)

    /** The width and height a raster image declares in its header; null when there is none to read. */
    fun dimensions(content: ByteArray): Pair<Int, Int>? {
        return runCatching {
            ImageIO.createImageInputStream(content.inputStream()).use { input ->
                val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
                try {
                    reader.setInput(input, true, true)
                    reader.getWidth(0) to reader.getHeight(0)
                } finally {
                    reader.dispose()
                }
            }
        }.getOrNull()
    }

    /** Refuses an image that declares more than [MAX_PIXELS] pixels. */
    fun requireReasonable(mediaType: AssetMediaType, content: ByteArray) {
        val (width, height) = dimensions(mediaType, content) ?: return
        validate("content", width.toLong() * height <= MAX_PIXELS) {
            "This image is $width × $height pixels; an image may have at most 40 million. Make it smaller and upload it again."
        }
    }
}
