package app.epistola.generation.pdf

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FontCacheTest {

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
}
