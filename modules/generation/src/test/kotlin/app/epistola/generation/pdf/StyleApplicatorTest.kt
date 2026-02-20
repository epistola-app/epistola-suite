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
    // COMPONENT_DEFAULTS
    // -----------------------------------------------------------------------

    @Test
    fun `COMPONENT_DEFAULTS contains entries for all block types`() {
        val expectedTypes = setOf("text", "container", "columns", "table", "datatable", "image")
        assertEquals(expectedTypes, StyleApplicator.COMPONENT_DEFAULTS.keys)
    }

    @Test
    fun `COMPONENT_DEFAULTS all have marginBottom 0_5em`() {
        for ((type, defaults) in StyleApplicator.COMPONENT_DEFAULTS) {
            assertEquals("0.5em", defaults["marginBottom"], "Expected marginBottom for $type")
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
        val inline = mapOf("marginBottom" to "10px" as Any)
        val result = StyleApplicator.resolveBlockStyles(emptyMap(), null, inline)
        assertEquals(inline, result)
    }

    @Test
    fun `resolveBlockStyles returns preset when no inline`() {
        val presets = mapOf("compact" to mapOf("marginBottom" to "5px" as Any))
        val result = StyleApplicator.resolveBlockStyles(presets, "compact", null)
        assertEquals(mapOf("marginBottom" to "5px"), result)
    }

    @Test
    fun `resolveBlockStyles merges preset and inline with inline winning`() {
        val presets = mapOf("compact" to mapOf("marginBottom" to "5px" as Any, "color" to "red" as Any))
        val inline = mapOf("marginBottom" to "10px" as Any)
        val result = StyleApplicator.resolveBlockStyles(presets, "compact", inline)
        assertNotNull(result)
        assertEquals("10px", result["marginBottom"]) // inline wins
        assertEquals("red", result["color"]) // preset value preserved
    }

    // -----------------------------------------------------------------------
    // applyStylesWithPreset (smoke tests, verifies no exceptions)
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
            defaultStyles = mapOf("marginBottom" to "0.5em"),
        )
    }

    @Test
    fun `applyStylesWithPreset full cascade does not throw`() {
        val div = Div()
        val presets = mapOf("heading" to mapOf("fontSize" to "24px" as Any))
        StyleApplicator.applyStylesWithPreset(
            div,
            blockInlineStyles = mapOf("color" to "#333"),
            blockStylePreset = "heading",
            blockStylePresets = presets,
            documentStyles = mapOf("fontFamily" to "Arial"),
            fontCache = fontCache,
            defaultStyles = mapOf("marginBottom" to "0.5em"),
        )
    }

    @Test
    fun `applyStylesWithPreset with null defaultStyles is backward compatible`() {
        val div = Div()
        StyleApplicator.applyStylesWithPreset(
            div,
            blockInlineStyles = mapOf("fontSize" to "14px"),
            blockStylePreset = null,
            blockStylePresets = emptyMap(),
            documentStyles = null,
            fontCache = fontCache,
        )
    }

    @Test
    fun `applyStylesWithPreset filters non-inheritable document styles`() {
        // This is a structural test - backgroundColor in docStyles should not throw
        // and should not propagate to block elements
        val div = Div()
        StyleApplicator.applyStylesWithPreset(
            div,
            blockInlineStyles = null,
            blockStylePreset = null,
            blockStylePresets = emptyMap(),
            documentStyles = mapOf("backgroundColor" to "#ff0000", "fontSize" to "14px"),
            fontCache = fontCache,
        )
    }
}
