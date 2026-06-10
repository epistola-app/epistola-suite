package app.epistola.generation.pdf

import app.epistola.catalog.protocol.FontRef
import com.itextpdf.io.font.PdfEncodings
import com.itextpdf.io.font.constants.StandardFonts
import com.itextpdf.kernel.font.PdfFont
import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.font.PdfFontFactory.EmbeddingStrategy
import com.itextpdf.layout.element.Text
import org.slf4j.LoggerFactory

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
 * built-in font. The resolver receives the exact requested CSS face
 * (numeric `weight` + `italic`) and is responsible for nearest-weight
 * matching. Unresolvable references fall back to the built-in font, mapping
 * `weight >= 700` to the bold built-in and `italic` to the oblique built-in.
 *
 * Fonts created by this cache are scoped to a specific PdfDocument instance
 * and should not be reused across different documents.
 */
class FontCache(
    private val pdfaCompliant: Boolean = false,
    private val fontFamilyResolver: FontFamilyResolver? = null,
) {
    private val fonts = mutableMapOf<String, PdfFont>()

    /** Font families already warned about (once per document) to avoid per-run log spam. */
    private val warnedUnresolved = mutableSetOf<String>()

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
     * requested CSS face ([weight] 1–1000, [italic]). When [ref] is null, no
     * resolver is configured, or the reference cannot be resolved, falls back
     * to the built-in font: `weight >= 700` selects the bold built-in and
     * [italic] selects the oblique built-in. Resolved fonts are cached per
     * document like the built-ins.
     */
    fun font(ref: FontRef?, weight: Int, italic: Boolean): PdfFont {
        if (ref != null) {
            resolveReferencedFont(ref, weight, italic)?.let { return it }
        }
        return builtIn(weight >= BOLD_THRESHOLD, italic)
    }

    /**
     * Returns a font guaranteed to render every glyph in [sample], preferring
     * [preferred] (the content font) when it already covers the sample. When it
     * doesn't — most importantly the standard WinAnsi font, which lacks the
     * `circle` (○) / `square` (■) bullet markers — falls back to the bundled,
     * always-embeddable Liberation Sans, which carries them. Without this a
     * marker glyph absent from the resolved font silently vanishes from the PDF
     * (see #401). Spaces are treated as always covered.
     */
    fun fontCoveringOrFallback(preferred: PdfFont, sample: String): PdfFont {
        val covered = sample.codePoints().allMatch { cp -> cp == ' '.code || preferred.containsGlyph(cp) }
        return if (covered) preferred else getEmbeddedFont(REGULAR)
    }

    /**
     * Builds the [Text] used as a list's bullet marker: the glyph [marker] rendered in
     * [contentFont], or the bundled fallback when that font lacks the glyph (see
     * [fontCoveringOrFallback]). Both list renderers — the `datalist` and the Text-component
     * `bullet_list` — construct their marker here, so the two paths stay glyph-for-glyph
     * identical with the same fallback behaviour (#401).
     */
    fun listMarker(marker: String, contentFont: PdfFont): Text = Text(marker).setFont(fontCoveringOrFallback(contentFont, marker))

    private fun builtIn(isBold: Boolean, isItalic: Boolean): PdfFont = when {
        isBold && isItalic -> boldItalic
        isBold -> bold
        isItalic -> italic
        else -> regular
    }

    private fun resolveReferencedFont(ref: FontRef, weight: Int, italic: Boolean): PdfFont? {
        val resolver = fontFamilyResolver ?: return null
        val cacheKey = "ref:${ref.catalogKey.orEmpty()}|${ref.slug}|$weight|$italic"
        fonts[cacheKey]?.let { return it }
        val bytes = resolver.resolve(ref.catalogKey, ref.slug, weight, italic)
        if (bytes == null) {
            val familyKey = "${ref.catalogKey.orEmpty()}|${ref.slug}"
            if (warnedUnresolved.add(familyKey)) {
                log.warn(
                    "Font ref '{}' (catalog '{}', weight {}, italic {}) could not be resolved; falling back to the built-in font",
                    ref.slug,
                    ref.catalogKey ?: "<owning>",
                    weight,
                    italic,
                )
            }
            return null
        }
        val font = PdfFontFactory.createFont(bytes, PdfEncodings.IDENTITY_H, EmbeddingStrategy.FORCE_EMBEDDED)
        fonts[cacheKey] = font
        return font
    }

    companion object {
        private val log = LoggerFactory.getLogger(FontCache::class.java)

        /** CSS `font-weight` at or above which the built-in fallback is bold. */
        const val BOLD_THRESHOLD = 700

        private const val FONT_DIR = "/fonts"
        private const val REGULAR = "$FONT_DIR/LiberationSans-Regular.ttf"
        private const val BOLD = "$FONT_DIR/LiberationSans-Bold.ttf"
        private const val ITALIC = "$FONT_DIR/LiberationSans-Italic.ttf"
        private const val BOLD_ITALIC = "$FONT_DIR/LiberationSans-BoldItalic.ttf"
    }
}
