package app.epistola.generation.pdf

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FontCacheTest {

    private val liberationRegularBytes: ByteArray =
        FontCacheTest::class.java.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")!!.readBytes()

    @Test
    fun `standard mode uses non-embedded Helvetica fonts`() {
        val cache = FontCache(pdfaCompliant = false)

        val regular = cache.regular
        assertNotNull(regular)
        assertFalse(regular.isEmbedded, "Standard mode fonts should not be embedded")
    }

    @Test
    fun `pdfa mode uses embedded Liberation Sans fonts`() {
        val cache = FontCache(pdfaCompliant = true)

        val regular = cache.regular
        assertNotNull(regular)
        assertTrue(regular.isEmbedded, "PDF/A mode fonts should be embedded")
    }

    @Test
    fun `standard mode provides all font variants`() {
        val cache = FontCache(pdfaCompliant = false)

        assertNotNull(cache.regular)
        assertNotNull(cache.bold)
        assertNotNull(cache.italic)
        assertNotNull(cache.boldItalic)
    }

    @Test
    fun `pdfa mode provides all font variants`() {
        val cache = FontCache(pdfaCompliant = true)

        assertNotNull(cache.regular)
        assertNotNull(cache.bold)
        assertNotNull(cache.italic)
        assertNotNull(cache.boldItalic)
    }

    @Test
    fun `caches font instances across calls`() {
        val cache = FontCache(pdfaCompliant = false)

        val first = cache.regular
        val second = cache.regular
        assertTrue(first === second, "Should return the same cached font instance")
    }

    // -----------------------------------------------------------------------
    // font(ref, isBold, isItalic) — structured fontFamily resolution
    // -----------------------------------------------------------------------

    @Test
    fun `null ref falls back to the built-in variant`() {
        val cache = FontCache(pdfaCompliant = false)

        assertSame(cache.regular, cache.font(null, isBold = false, isItalic = false))
        assertSame(cache.bold, cache.font(null, isBold = true, isItalic = false))
        assertSame(cache.italic, cache.font(null, isBold = false, isItalic = true))
        assertSame(cache.boldItalic, cache.font(null, isBold = true, isItalic = true))
    }

    @Test
    fun `ref without a configured resolver falls back to the built-in`() {
        val cache = FontCache(pdfaCompliant = false)
        val ref = ResolvedFontRef(catalogKey = "system", slug = "inter")

        assertSame(cache.regular, cache.font(ref, isBold = false, isItalic = false))
    }

    @Test
    fun `ref resolves to an embedded font and is cached`() {
        val cache = FontCache(
            pdfaCompliant = false,
            fontFamilyResolver = { _, _, variant ->
                if (variant == FontVariant.REGULAR) liberationRegularBytes else null
            },
        )
        val ref = ResolvedFontRef(catalogKey = "system", slug = "inter")

        val resolved = cache.font(ref, isBold = false, isItalic = false)
        assertTrue(resolved.isEmbedded, "Resolver-provided fonts are force-embedded")
        assertTrue(resolved !== cache.regular, "Resolved font must not be the built-in Helvetica")
        assertSame(resolved, cache.font(ref, isBold = false, isItalic = false), "Resolved fonts are cached per document")
    }

    @Test
    fun `missing variant falls back to the family regular variant`() {
        val cache = FontCache(
            pdfaCompliant = false,
            fontFamilyResolver = { _, _, variant ->
                if (variant == FontVariant.REGULAR) liberationRegularBytes else null
            },
        )
        val ref = ResolvedFontRef(catalogKey = "system", slug = "inter")

        // BOLD has no bytes; resolver returns REGULAR as the family fallback.
        val resolved = cache.font(ref, isBold = true, isItalic = false)
        assertTrue(resolved.isEmbedded)
        assertTrue(resolved !== cache.bold, "Should not fall through to the built-in bold")
    }

    @Test
    fun `unresolvable ref falls back to the built-in variant`() {
        val cache = FontCache(
            pdfaCompliant = false,
            fontFamilyResolver = { _, _, _ -> null },
        )
        val ref = ResolvedFontRef(catalogKey = "system", slug = "missing")

        assertSame(cache.bold, cache.font(ref, isBold = true, isItalic = false))
    }
}
