package app.epistola.generation.pdf

import com.itextpdf.io.font.PdfEncodings
import com.itextpdf.io.font.constants.StandardFonts
import com.itextpdf.kernel.font.PdfFont
import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.font.PdfFontFactory.EmbeddingStrategy

/**
 * Manages font creation and caching for a single PDF document.
 *
 * When [pdfaCompliant] is true, loads Liberation Sans (metrically compatible with Helvetica)
 * as embedded TTF fonts from classpath resources. Embedded fonts are required for PDF/A compliance.
 *
 * When [pdfaCompliant] is false, uses standard Helvetica fonts (non-embedded) for smaller, faster PDFs.
 *
 * When a [fontFamilyResolver] is supplied and a style carries a structured
 * `fontFamily` reference, the referenced font is embedded instead of the
 * built-in font. Unresolvable references fall back to the built-in font.
 *
 * Fonts created by this cache are scoped to a specific PdfDocument instance
 * and should not be reused across different documents.
 */
class FontCache(
    private val pdfaCompliant: Boolean = false,
    private val fontFamilyResolver: FontFamilyResolver? = null,
) {
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

    val regular: PdfFont get() = if (pdfaCompliant) getEmbeddedFont(REGULAR) else getStandardFont(StandardFonts.HELVETICA)
    val bold: PdfFont get() = if (pdfaCompliant) getEmbeddedFont(BOLD) else getStandardFont(StandardFonts.HELVETICA_BOLD)
    val italic: PdfFont get() = if (pdfaCompliant) getEmbeddedFont(ITALIC) else getStandardFont(StandardFonts.HELVETICA_OBLIQUE)
    val boldItalic: PdfFont get() = if (pdfaCompliant) getEmbeddedFont(BOLD_ITALIC) else getStandardFont(StandardFonts.HELVETICA_BOLDOBLIQUE)

    /**
     * Resolves the [PdfFont] for an optional structured font reference and the
     * given bold/italic flags. When [ref] is null, no resolver is configured, or
     * the reference cannot be resolved, falls back to the built-in font for the
     * variant. Resolved fonts are cached per document like the built-ins.
     */
    fun font(ref: ResolvedFontRef?, isBold: Boolean, isItalic: Boolean): PdfFont {
        val variant = when {
            isBold && isItalic -> FontVariant.BOLD_ITALIC
            isBold -> FontVariant.BOLD
            isItalic -> FontVariant.ITALIC
            else -> FontVariant.REGULAR
        }
        if (ref != null) {
            resolveReferencedFont(ref, variant)?.let { return it }
        }
        return builtIn(variant)
    }

    private fun builtIn(variant: FontVariant): PdfFont = when (variant) {
        FontVariant.REGULAR -> regular
        FontVariant.BOLD -> bold
        FontVariant.ITALIC -> italic
        FontVariant.BOLD_ITALIC -> boldItalic
    }

    private fun resolveReferencedFont(ref: ResolvedFontRef, variant: FontVariant): PdfFont? {
        val resolver = fontFamilyResolver ?: return null
        val cacheKey = "ref:${ref.catalogKey.orEmpty()}/${ref.slug}/$variant"
        fonts[cacheKey]?.let { return it }
        val bytes = resolver.resolve(ref.catalogKey, ref.slug, variant)
            ?: resolver.resolve(ref.catalogKey, ref.slug, FontVariant.REGULAR)
            ?: return null
        val font = PdfFontFactory.createFont(bytes, PdfEncodings.IDENTITY_H, EmbeddingStrategy.FORCE_EMBEDDED)
        fonts[cacheKey] = font
        return font
    }

    companion object {
        private const val FONT_DIR = "/fonts"
        private const val REGULAR = "$FONT_DIR/LiberationSans-Regular.ttf"
        private const val BOLD = "$FONT_DIR/LiberationSans-Bold.ttf"
        private const val ITALIC = "$FONT_DIR/LiberationSans-Italic.ttf"
        private const val BOLD_ITALIC = "$FONT_DIR/LiberationSans-BoldItalic.ttf"
    }
}
