package app.epistola.generation.pdf

import com.itextpdf.io.font.PdfEncodings
import com.itextpdf.kernel.font.PdfFont
import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.font.PdfFontFactory.EmbeddingStrategy

/**
 * Manages font creation and caching for a single PDF document.
 *
 * Loads Liberation Sans (metrically compatible with Helvetica) as embedded TTF fonts
 * from classpath resources. Embedded fonts are required for PDF/A compliance.
 *
 * Fonts created by this cache are scoped to a specific PdfDocument instance
 * and should not be reused across different documents.
 */
class FontCache {
    private val fonts = mutableMapOf<String, PdfFont>()

    /**
     * Gets or creates an embedded font for the given classpath resource path.
     */
    private fun getEmbeddedFont(resourcePath: String): PdfFont = fonts.getOrPut(resourcePath) {
        val bytes = FontCache::class.java.getResourceAsStream(resourcePath)?.readBytes()
            ?: throw IllegalStateException("Font resource not found: $resourcePath")
        PdfFontFactory.createFont(bytes, PdfEncodings.IDENTITY_H, EmbeddingStrategy.FORCE_EMBEDDED)
    }

    val regular: PdfFont get() = getEmbeddedFont(REGULAR)
    val bold: PdfFont get() = getEmbeddedFont(BOLD)
    val italic: PdfFont get() = getEmbeddedFont(ITALIC)
    val boldItalic: PdfFont get() = getEmbeddedFont(BOLD_ITALIC)

    companion object {
        private const val FONT_DIR = "/fonts"
        private const val REGULAR = "$FONT_DIR/LiberationSans-Regular.ttf"
        private const val BOLD = "$FONT_DIR/LiberationSans-Bold.ttf"
        private const val ITALIC = "$FONT_DIR/LiberationSans-Italic.ttf"
        private const val BOLD_ITALIC = "$FONT_DIR/LiberationSans-BoldItalic.ttf"
    }
}
