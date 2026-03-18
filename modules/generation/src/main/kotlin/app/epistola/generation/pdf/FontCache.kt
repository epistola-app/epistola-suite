package app.epistola.generation.pdf

import com.itextpdf.io.font.PdfEncodings
import com.itextpdf.io.font.constants.StandardFonts
import com.itextpdf.kernel.font.PdfFont
import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.font.PdfFontFactory.EmbeddingStrategy

/**
 * Supported font families for PDF rendering.
 */
enum class FontFamily(val displayName: String) {
    SANS("Liberation Sans"),
    SERIF("Liberation Serif"),
    MONO("Liberation Mono"),
}

/**
 * Manages font creation and caching for a single PDF document.
 *
 * When [pdfaCompliant] is true, loads Liberation fonts as embedded TTF fonts
 * from classpath resources. Embedded fonts are required for PDF/A compliance.
 *
 * When [pdfaCompliant] is false, uses standard fonts (non-embedded) for smaller, faster PDFs.
 *
 * Fonts created by this cache are scoped to a specific PdfDocument instance
 * and should not be reused across different documents.
 */
class FontCache(private val pdfaCompliant: Boolean = false) {
    private val fonts = mutableMapOf<String, PdfFont>()

    /**
     * Gets or creates an embedded font for the given classpath resource path.
     */
    private fun getEmbeddedFont(resourcePath: String): PdfFont = fonts.getOrPut(resourcePath) {
        val bytes = FontCache::class.java.getResourceAsStream(resourcePath)?.readBytes()
            ?: throw IllegalStateException("Font resource not found: $resourcePath")
        PdfFontFactory.createFont(bytes, PdfEncodings.IDENTITY_H, EmbeddingStrategy.FORCE_EMBEDDED)
    }

    /**
     * Gets or creates a standard (non-embedded) font.
     */
    private fun getStandardFont(fontProgram: String): PdfFont = fonts.getOrPut(fontProgram) {
        PdfFontFactory.createFont(fontProgram)
    }

    /**
     * Gets the appropriate font based on family, weight, and style.
     *
     * @param family The font family (Liberation Sans, Serif, or Mono)
     * @param weight Font weight (100-900). Values >= 500 are treated as bold.
     * @param italic Whether to use italic style
     * @return The matching PdfFont
     */
    fun getFont(family: FontFamily = FontFamily.SANS, weight: Int = 400, italic: Boolean = false): PdfFont {
        val isBold = weight >= 500
        return when {
            isBold && italic -> getBoldItalic(family)
            isBold -> getBold(family)
            italic -> getItalic(family)
            else -> getRegular(family)
        }
    }

    /**
     * Legacy accessors default to Sans family for backward compatibility.
     */
    val regular: PdfFont get() = getRegular(FontFamily.SANS)
    val bold: PdfFont get() = getBold(FontFamily.SANS)
    val italic: PdfFont get() = getItalic(FontFamily.SANS)
    val boldItalic: PdfFont get() = getBoldItalic(FontFamily.SANS)

    private fun getRegular(family: FontFamily): PdfFont = when (family) {
        FontFamily.SANS -> if (pdfaCompliant) getEmbeddedFont(SANS_REGULAR) else getStandardFont(StandardFonts.HELVETICA)
        FontFamily.SERIF -> getEmbeddedFont(SERIF_REGULAR)
        FontFamily.MONO -> getEmbeddedFont(MONO_REGULAR)
    }

    private fun getBold(family: FontFamily): PdfFont = when (family) {
        FontFamily.SANS -> if (pdfaCompliant) getEmbeddedFont(SANS_BOLD) else getStandardFont(StandardFonts.HELVETICA_BOLD)
        FontFamily.SERIF -> getEmbeddedFont(SERIF_BOLD)
        FontFamily.MONO -> getEmbeddedFont(MONO_BOLD)
    }

    private fun getItalic(family: FontFamily): PdfFont = when (family) {
        FontFamily.SANS -> if (pdfaCompliant) getEmbeddedFont(SANS_ITALIC) else getStandardFont(StandardFonts.HELVETICA_OBLIQUE)
        FontFamily.SERIF -> getEmbeddedFont(SERIF_ITALIC)
        FontFamily.MONO -> getEmbeddedFont(MONO_ITALIC)
    }

    private fun getBoldItalic(family: FontFamily): PdfFont = when (family) {
        FontFamily.SANS -> if (pdfaCompliant) getEmbeddedFont(SANS_BOLD_ITALIC) else getStandardFont(StandardFonts.HELVETICA_BOLDOBLIQUE)
        FontFamily.SERIF -> getEmbeddedFont(SERIF_BOLD_ITALIC)
        FontFamily.MONO -> getEmbeddedFont(MONO_BOLD_ITALIC)
    }

    companion object {
        private const val FONT_DIR = "/fonts"

        // Liberation Sans
        private const val SANS_REGULAR = "$FONT_DIR/LiberationSans-Regular.ttf"
        private const val SANS_BOLD = "$FONT_DIR/LiberationSans-Bold.ttf"
        private const val SANS_ITALIC = "$FONT_DIR/LiberationSans-Italic.ttf"
        private const val SANS_BOLD_ITALIC = "$FONT_DIR/LiberationSans-BoldItalic.ttf"

        // Liberation Serif
        private const val SERIF_REGULAR = "$FONT_DIR/LiberationSerif-Regular.ttf"
        private const val SERIF_BOLD = "$FONT_DIR/LiberationSerif-Bold.ttf"
        private const val SERIF_ITALIC = "$FONT_DIR/LiberationSerif-Italic.ttf"
        private const val SERIF_BOLD_ITALIC = "$FONT_DIR/LiberationSerif-BoldItalic.ttf"

        // Liberation Mono
        private const val MONO_REGULAR = "$FONT_DIR/LiberationMono-Regular.ttf"
        private const val MONO_BOLD = "$FONT_DIR/LiberationMono-Bold.ttf"
        private const val MONO_ITALIC = "$FONT_DIR/LiberationMono-Italic.ttf"
        private const val MONO_BOLD_ITALIC = "$FONT_DIR/LiberationMono-BoldItalic.ttf"
    }
}
