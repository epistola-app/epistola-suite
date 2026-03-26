package app.epistola.generation.pdf

import com.itextpdf.io.font.PdfEncodings
import com.itextpdf.io.font.constants.StandardFonts
import com.itextpdf.kernel.font.PdfFont
import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.font.PdfFontFactory.EmbeddingStrategy

/**
 * Available font families for PDF rendering.
 * Each maps to a set of Liberation fonts (metrically compatible with standard fonts).
 */
enum class FontFamily(val displayName: String) {
    SANS("Liberation Sans"),
    SERIF("Liberation Serif"),
    MONO("Liberation Mono"),
}

/**
 * Manages font creation and caching for a single PDF document.
 *
 * When [pdfaCompliant] is true, loads Liberation fonts (metrically compatible with
 * Helvetica/Times/Courier) as embedded TTF fonts from classpath resources.
 * Embedded fonts are required for PDF/A compliance.
 *
 * When [pdfaCompliant] is false, uses standard Helvetica fonts for the Sans family
 * (non-embedded, smaller files). Serif and Mono always use embedded Liberation fonts.
 *
 * Fonts created by this cache are scoped to a specific PdfDocument instance
 * and should not be reused across different documents.
 */
class FontCache(private val pdfaCompliant: Boolean = false) {
    private val fonts = mutableMapOf<String, PdfFont>()

    private fun getEmbeddedFont(resourcePath: String): PdfFont = fonts.getOrPut(resourcePath) {
        val bytes = FontCache::class.java.getResourceAsStream(resourcePath)?.readBytes()
            ?: throw IllegalStateException("Font resource not found: $resourcePath")
        PdfFontFactory.createFont(bytes, PdfEncodings.IDENTITY_H, EmbeddingStrategy.FORCE_EMBEDDED)
    }

    private fun getStandardFont(fontProgram: String): PdfFont = fonts.getOrPut(fontProgram) {
        PdfFontFactory.createFont(fontProgram)
    }

    /**
     * Gets a font for the given family, weight, and style.
     * Weight >= 500 is treated as bold.
     */
    fun getFont(family: FontFamily = FontFamily.SANS, weight: Int = 400, italic: Boolean = false): PdfFont {
        val isBold = weight >= 500
        return when (family) {
            FontFamily.SANS -> getSansFont(isBold, italic)
            FontFamily.SERIF -> getSerifFont(isBold, italic)
            FontFamily.MONO -> getMonoFont(isBold, italic)
        }
    }

    private fun getSansFont(bold: Boolean, italic: Boolean): PdfFont {
        if (pdfaCompliant) {
            return getEmbeddedFont(
                when {
                    bold && italic -> SANS_BOLD_ITALIC
                    bold -> SANS_BOLD
                    italic -> SANS_ITALIC
                    else -> SANS_REGULAR
                },
            )
        }
        return getStandardFont(
            when {
                bold && italic -> StandardFonts.HELVETICA_BOLDOBLIQUE
                bold -> StandardFonts.HELVETICA_BOLD
                italic -> StandardFonts.HELVETICA_OBLIQUE
                else -> StandardFonts.HELVETICA
            },
        )
    }

    private fun getSerifFont(bold: Boolean, italic: Boolean): PdfFont = getEmbeddedFont(
        when {
            bold && italic -> SERIF_BOLD_ITALIC
            bold -> SERIF_BOLD
            italic -> SERIF_ITALIC
            else -> SERIF_REGULAR
        },
    )

    private fun getMonoFont(bold: Boolean, italic: Boolean): PdfFont = getEmbeddedFont(
        when {
            bold && italic -> MONO_BOLD_ITALIC
            bold -> MONO_BOLD
            italic -> MONO_ITALIC
            else -> MONO_REGULAR
        },
    )

    // Legacy accessors (default to Sans family)
    val regular: PdfFont get() = getFont(FontFamily.SANS, 400, false)
    val bold: PdfFont get() = getFont(FontFamily.SANS, 700, false)
    val italic: PdfFont get() = getFont(FontFamily.SANS, 400, true)
    val boldItalic: PdfFont get() = getFont(FontFamily.SANS, 700, true)

    companion object {
        private const val FONT_DIR = "/fonts"

        // Sans (Liberation Sans — metrically compatible with Helvetica)
        private const val SANS_REGULAR = "$FONT_DIR/LiberationSans-Regular.ttf"
        private const val SANS_BOLD = "$FONT_DIR/LiberationSans-Bold.ttf"
        private const val SANS_ITALIC = "$FONT_DIR/LiberationSans-Italic.ttf"
        private const val SANS_BOLD_ITALIC = "$FONT_DIR/LiberationSans-BoldItalic.ttf"

        // Serif (Liberation Serif — metrically compatible with Times New Roman)
        private const val SERIF_REGULAR = "$FONT_DIR/LiberationSerif-Regular.ttf"
        private const val SERIF_BOLD = "$FONT_DIR/LiberationSerif-Bold.ttf"
        private const val SERIF_ITALIC = "$FONT_DIR/LiberationSerif-Italic.ttf"
        private const val SERIF_BOLD_ITALIC = "$FONT_DIR/LiberationSerif-BoldItalic.ttf"

        // Mono (Liberation Mono — metrically compatible with Courier New)
        private const val MONO_REGULAR = "$FONT_DIR/LiberationMono-Regular.ttf"
        private const val MONO_BOLD = "$FONT_DIR/LiberationMono-Bold.ttf"
        private const val MONO_ITALIC = "$FONT_DIR/LiberationMono-Italic.ttf"
        private const val MONO_BOLD_ITALIC = "$FONT_DIR/LiberationMono-BoldItalic.ttf"

        /**
         * Resolves a CSS font-family string to a [FontFamily].
         * Matches on keywords: "serif", "mono", "monospace". Defaults to [FontFamily.SANS].
         */
        fun resolveFontFamily(fontFamilyCss: String?): FontFamily {
            if (fontFamilyCss == null) return FontFamily.SANS
            val lower = fontFamilyCss.lowercase()
            return when {
                "mono" in lower || "monospace" in lower || "courier" in lower -> FontFamily.MONO
                "serif" in lower && "sans" !in lower -> FontFamily.SERIF
                else -> FontFamily.SANS
            }
        }
    }
}
