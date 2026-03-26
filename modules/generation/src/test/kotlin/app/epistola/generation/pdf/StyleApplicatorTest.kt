package app.epistola.generation.pdf

import com.itextpdf.layout.element.Div
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class StyleApplicatorTest {

    private val fontCache = FontCache()

    // -----------------------------------------------------------------------
    // INHERITABLE_KEYS
    // -----------------------------------------------------------------------

    @Test
    fun `INHERITABLE_KEYS contains all expected typography keys`() {
        val expected = setOf(
            "fontFamily",
            "fontSize",
            "fontWeight",
            "fontStyle",
            "color",
            "lineHeight",
            "letterSpacing",
            "textAlign",
        )
        assertEquals(expected, StyleApplicator.INHERITABLE_KEYS)
    }

    @Test
    fun `INHERITABLE_KEYS does not contain layout keys`() {
        val layoutKeys = listOf("marginTop", "marginBottom", "paddingTop", "backgroundColor", "borderWidth")
        for (key in layoutKeys) {
            assert(key !in StyleApplicator.INHERITABLE_KEYS) { "$key should not be inheritable" }
        }
    }

    // -----------------------------------------------------------------------
    // resolveBlockStyles
    // -----------------------------------------------------------------------

    @Test
    fun `resolveBlockStyles returns null when both preset and inline are null`() {
        val result = StyleApplicator.resolveBlockStyles(emptyMap(), null, null)
        assertEquals(null, result)
    }

    @Test
    fun `resolveBlockStyles returns inline when no preset`() {
        val inline = mapOf("marginBottom" to "10pt" as Any)
        val result = StyleApplicator.resolveBlockStyles(emptyMap(), null, inline)
        assertEquals(inline, result)
    }

    @Test
    fun `resolveBlockStyles returns preset when no inline`() {
        val presets = mapOf("compact" to mapOf("marginBottom" to "4pt" as Any))
        val result = StyleApplicator.resolveBlockStyles(presets, "compact", null)
        assertEquals(mapOf("marginBottom" to "4pt"), result)
    }

    @Test
    fun `resolveBlockStyles merges preset and inline with inline winning`() {
        val presets = mapOf("compact" to mapOf("marginBottom" to "4pt" as Any, "color" to "red" as Any))
        val inline = mapOf("marginBottom" to "8pt" as Any)
        val result = StyleApplicator.resolveBlockStyles(presets, "compact", inline)
        assertNotNull(result)
        assertEquals("8pt", result["marginBottom"]) // inline wins
        assertEquals("red", result["color"]) // preset value preserved
    }

    // -----------------------------------------------------------------------
    // applyStylesWithPreset (smoke tests)
    // -----------------------------------------------------------------------

    @Test
    fun `applyStylesWithPreset with defaultStyles does not throw`() {
        val div = Div()
        StyleApplicator.applyStylesWithPreset(
            div,
            blockInlineStyles = null,
            blockStylePreset = null,
            blockStylePresets = emptyMap(),
            documentStyles = null,
            fontCache = fontCache,
            defaultStyles = mapOf("marginBottom" to "sp(1.5)"),
        )
    }

    @Test
    fun `applyStylesWithPreset full cascade does not throw`() {
        val div = Div()
        val presets = mapOf("heading" to mapOf("fontSize" to "24pt" as Any))
        StyleApplicator.applyStylesWithPreset(
            div,
            blockInlineStyles = mapOf("color" to "#333"),
            blockStylePreset = "heading",
            blockStylePresets = presets,
            documentStyles = mapOf("fontFamily" to "Arial"),
            fontCache = fontCache,
            defaultStyles = mapOf("marginBottom" to "sp(1.5)"),
        )
    }

    @Test
    fun `applyStylesWithPreset with null defaultStyles does not throw`() {
        val div = Div()
        StyleApplicator.applyStylesWithPreset(
            div,
            blockInlineStyles = mapOf("fontSize" to "14pt"),
            blockStylePreset = null,
            blockStylePresets = emptyMap(),
            documentStyles = null,
            fontCache = fontCache,
        )
    }

    @Test
    fun `applyStylesWithPreset filters non-inheritable document styles`() {
        val div = Div()
        StyleApplicator.applyStylesWithPreset(
            div,
            blockInlineStyles = null,
            blockStylePreset = null,
            blockStylePresets = emptyMap(),
            documentStyles = mapOf("backgroundColor" to "#ff0000", "fontSize" to "14pt"),
            fontCache = fontCache,
        )
    }

    // -----------------------------------------------------------------------
    // sp() token support
    // -----------------------------------------------------------------------

    @Test
    fun `applyStylesWithPreset handles sp() tokens in inline styles`() {
        val div = Div()
        StyleApplicator.applyStylesWithPreset(
            div,
            blockInlineStyles = mapOf("marginBottom" to "sp(2)"),
            blockStylePreset = null,
            blockStylePresets = emptyMap(),
            documentStyles = null,
            fontCache = fontCache,
        )
    }

    @Test
    fun `applyStylesWithPreset handles sp() tokens in default styles`() {
        val div = Div()
        StyleApplicator.applyStylesWithPreset(
            div,
            blockInlineStyles = null,
            blockStylePreset = null,
            blockStylePresets = emptyMap(),
            documentStyles = null,
            fontCache = fontCache,
            defaultStyles = mapOf("marginBottom" to "sp(1.5)"),
        )
    }

    @Test
    fun `applyStylesWithPreset uses custom spacingUnit`() {
        val div = Div()
        StyleApplicator.applyStylesWithPreset(
            div,
            blockInlineStyles = mapOf("marginBottom" to "sp(2)"),
            blockStylePreset = null,
            blockStylePresets = emptyMap(),
            documentStyles = null,
            fontCache = fontCache,
            spacingUnit = 6f, // sp(2) = 12pt
        )
    }

    @Test
    fun `applyStylesWithPreset handles pt values`() {
        val div = Div()
        StyleApplicator.applyStylesWithPreset(
            div,
            blockInlineStyles = mapOf("marginBottom" to "16pt", "paddingTop" to "8pt"),
            blockStylePreset = null,
            blockStylePresets = emptyMap(),
            documentStyles = null,
            fontCache = fontCache,
        )
    }
}
