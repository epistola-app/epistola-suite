package app.epistola.generation.pdf

import com.itextpdf.io.font.constants.StandardFonts
import com.itextpdf.kernel.font.PdfFont
import com.itextpdf.kernel.font.PdfFontFactory

/**
 * Manages font creation and caching for a single PDF document.
 *
 * Fonts created by this cache are scoped to a specific PdfDocument instance
 * and should not be reused across different documents.
 */
class FontCache {
    private val fonts = mutableMapOf<String, PdfFont>()

    /**
     * Gets or creates a font for the given standard font name.
     */
    fun getFont(fontName: String): PdfFont = fonts.getOrPut(fontName) {
        PdfFontFactory.createFont(fontName)
    }

    val regular: PdfFont get() = getFont(StandardFonts.HELVETICA)
    val bold: PdfFont get() = getFont(StandardFonts.HELVETICA_BOLD)
    val italic: PdfFont get() = getFont(StandardFonts.HELVETICA_OBLIQUE)
    val boldItalic: PdfFont get() = getFont(StandardFonts.HELVETICA_BOLDOBLIQUE)
}
