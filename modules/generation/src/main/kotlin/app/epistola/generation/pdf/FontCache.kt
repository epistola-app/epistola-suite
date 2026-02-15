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

    private enum class FontFamilyKind {
        Sans,
        Serif,
        Mono,
    }

    /**
     * Gets or creates a font for the given standard font name.
     */
    fun getFont(fontName: String): PdfFont = fonts.getOrPut(fontName) {
        PdfFontFactory.createFont(fontName)
    }

    fun resolveFont(
        fontFamily: String?,
        isBold: Boolean,
        isItalic: Boolean,
    ): PdfFont? {
        val familyKind = resolveFamilyKind(fontFamily)
        if (familyKind == null && !isBold && !isItalic) return null

        val resolvedFamily = familyKind ?: FontFamilyKind.Sans
        val fontName = when (resolvedFamily) {
            FontFamilyKind.Sans -> when {
                isBold && isItalic -> StandardFonts.HELVETICA_BOLDOBLIQUE
                isBold -> StandardFonts.HELVETICA_BOLD
                isItalic -> StandardFonts.HELVETICA_OBLIQUE
                else -> StandardFonts.HELVETICA
            }

            FontFamilyKind.Serif -> when {
                isBold && isItalic -> StandardFonts.TIMES_BOLDITALIC
                isBold -> StandardFonts.TIMES_BOLD
                isItalic -> StandardFonts.TIMES_ITALIC
                else -> StandardFonts.TIMES_ROMAN
            }

            FontFamilyKind.Mono -> when {
                isBold && isItalic -> StandardFonts.COURIER_BOLDOBLIQUE
                isBold -> StandardFonts.COURIER_BOLD
                isItalic -> StandardFonts.COURIER_OBLIQUE
                else -> StandardFonts.COURIER
            }
        }

        return getFont(fontName)
    }

    private fun resolveFamilyKind(fontFamily: String?): FontFamilyKind? {
        val normalized = fontFamily?.trim()?.lowercase() ?: return null
        if (normalized.isEmpty()) return null

        return when (normalized) {
            "helvetica" -> FontFamilyKind.Sans
            "times-roman" -> FontFamilyKind.Serif
            "courier" -> FontFamilyKind.Mono
            else -> null
        }
    }

    val regular: PdfFont get() = getFont(StandardFonts.HELVETICA)
    val bold: PdfFont get() = getFont(StandardFonts.HELVETICA_BOLD)
    val italic: PdfFont get() = getFont(StandardFonts.HELVETICA_OBLIQUE)
    val boldItalic: PdfFont get() = getFont(StandardFonts.HELVETICA_BOLDOBLIQUE)
}
