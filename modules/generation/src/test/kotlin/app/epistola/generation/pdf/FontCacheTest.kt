package app.epistola.generation.pdf

import app.epistola.catalog.protocol.FontRef
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
    fun `warmUp preloads embedded and standard font faces without throwing`() {
        // Forces the iText font class graph to load single-threaded (issue #724).
        // Must be safe to call repeatedly and must not throw.
        FontCache.warmUp()
        FontCache.warmUp()

        // After warmup both paths still produce usable faces.
        assertTrue(FontCache(pdfaCompliant = true).regular.isEmbedded)
        assertFalse(FontCache(pdfaCompliant = false).regular.isEmbedded)
    }

    @Test
    fun `concurrent font builds after warmup do not deadlock`() {
        FontCache.warmUp()

        val threads = (1..16).map {
            Thread.ofVirtual().unstarted {
                val cache = FontCache(pdfaCompliant = true)
                listOf(cache.regular, cache.bold, cache.italic, cache.boldItalic)
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(30_000) }
        assertTrue(threads.none { it.isAlive }, "Font builds should complete without deadlocking after warmup")
    }

    @Test
    fun `caches font instances across calls`() {
        val cache = FontCache(pdfaCompliant = false)

        val first = cache.regular
        val second = cache.regular
        assertTrue(first === second, "Should return the same cached font instance")
    }

    // -----------------------------------------------------------------------
    // font(ref, weight, italic) — structured fontFamily resolution
    // -----------------------------------------------------------------------

    @Test
    fun `null ref falls back to the built-in face by weight and italic`() {
        val cache = FontCache(pdfaCompliant = false)

        assertSame(cache.regular, cache.font(null, weight = 400, italic = false))
        assertSame(cache.bold, cache.font(null, weight = 700, italic = false))
        assertSame(cache.italic, cache.font(null, weight = 400, italic = true))
        assertSame(cache.boldItalic, cache.font(null, weight = 700, italic = true))
    }

    @Test
    fun `ref without a configured resolver falls back to the built-in`() {
        val cache = FontCache(pdfaCompliant = false)
        val ref = FontRef(catalogKey = "system", slug = "inter")

        assertSame(cache.regular, cache.font(ref, weight = 400, italic = false))
    }

    @Test
    fun `ref resolves to an embedded font and is cached`() {
        val cache = FontCache(
            pdfaCompliant = false,
            fontFamilyResolver = { _, _, weight, italic ->
                if (weight == 400 && !italic) liberationRegularBytes else null
            },
        )
        val ref = FontRef(catalogKey = "system", slug = "inter")

        val resolved = cache.font(ref, weight = 400, italic = false)
        assertTrue(resolved.isEmbedded, "Resolver-provided fonts are force-embedded")
        assertTrue(resolved !== cache.regular, "Resolved font must not be the built-in Helvetica")
        assertSame(resolved, cache.font(ref, weight = 400, italic = false), "Resolved fonts are cached per document")
    }

    @Test
    fun `resolver returning bytes for any weight embeds that face`() {
        // The resolver owns nearest-weight matching: it returns the best
        // available face's bytes for the requested (weight, italic).
        val cache = FontCache(
            pdfaCompliant = false,
            fontFamilyResolver = { _, _, _, italic ->
                if (!italic) liberationRegularBytes else null
            },
        )
        val ref = FontRef(catalogKey = "system", slug = "inter")

        val resolved = cache.font(ref, weight = 700, italic = false)
        assertTrue(resolved.isEmbedded)
        assertTrue(resolved !== cache.bold, "Should not fall through to the built-in bold")
    }

    @Test
    fun `unresolvable ref falls back to the built-in face`() {
        val cache = FontCache(
            pdfaCompliant = false,
            fontFamilyResolver = { _, _, _, _ -> null },
        )
        val ref = FontRef(catalogKey = "system", slug = "missing")

        assertSame(cache.bold, cache.font(ref, weight = 700, italic = false))
    }
}
