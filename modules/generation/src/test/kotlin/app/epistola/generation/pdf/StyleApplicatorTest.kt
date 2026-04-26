package app.epistola.generation.pdf

import com.itextpdf.layout.element.Div
import com.itextpdf.layout.properties.Property
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
            inheritedStyles = emptyMap(),
            fontCache = fontCache,
            defaultStyles = mapOf("marginBottom" to "1.5sp"),
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
            inheritedStyles = mapOf("fontFamily" to "Arial"),
            fontCache = fontCache,
            defaultStyles = mapOf("marginBottom" to "1.5sp"),
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
            inheritedStyles = emptyMap(),
            fontCache = fontCache,
        )
    }

    // -----------------------------------------------------------------------
    // resolveInheritedStyles
    // -----------------------------------------------------------------------

    @Test
    fun `resolveInheritedStyles returns parent styles when no overrides`() {
        val parent = mapOf("fontSize" to "12pt" as Any, "color" to "#000" as Any)
        val result = StyleApplicator.resolveInheritedStyles(parent, null, emptyMap(), null)
        assertEquals(parent, result)
    }

    @Test
    fun `resolveInheritedStyles merges inline overrides filtered to inheritable keys`() {
        val parent = mapOf("fontSize" to "12pt" as Any)
        val inline = mapOf("fontSize" to "18pt" as Any, "marginBottom" to "8pt" as Any)
        val result = StyleApplicator.resolveInheritedStyles(parent, null, emptyMap(), inline)
        assertEquals("18pt", result["fontSize"]) // overridden
        assert("marginBottom" !in result) // non-inheritable filtered out
    }

    @Test
    fun `resolveInheritedStyles merges preset overrides filtered to inheritable keys`() {
        val parent = mapOf("fontSize" to "12pt" as Any)
        val presets = mapOf("heading" to mapOf("fontSize" to "24pt" as Any, "paddingTop" to "4pt" as Any))
        val result = StyleApplicator.resolveInheritedStyles(parent, "heading", presets, null)
        assertEquals("24pt", result["fontSize"]) // preset overrides parent
        assert("paddingTop" !in result) // non-inheritable filtered out
    }

    // -----------------------------------------------------------------------
    // sp unit support
    // -----------------------------------------------------------------------

    @Test
    fun `applyStylesWithPreset handles sp values in inline styles`() {
        val div = Div()
        StyleApplicator.applyStylesWithPreset(
            div,
            blockInlineStyles = mapOf("marginBottom" to "2sp"),
            blockStylePreset = null,
            blockStylePresets = emptyMap(),
            inheritedStyles = emptyMap(),
            fontCache = fontCache,
        )
    }

    @Test
    fun `applyStylesWithPreset handles sp values in default styles`() {
        val div = Div()
        StyleApplicator.applyStylesWithPreset(
            div,
            blockInlineStyles = null,
            blockStylePreset = null,
            blockStylePresets = emptyMap(),
            inheritedStyles = emptyMap(),
            fontCache = fontCache,
            defaultStyles = mapOf("marginBottom" to "1.5sp"),
        )
    }

    @Test
    fun `applyStylesWithPreset uses custom spacingUnit`() {
        val div = Div()
        StyleApplicator.applyStylesWithPreset(
            div,
            blockInlineStyles = mapOf("marginBottom" to "2sp"),
            blockStylePreset = null,
            blockStylePresets = emptyMap(),
            inheritedStyles = emptyMap(),
            fontCache = fontCache,
            spacingUnit = 6f, // 2sp = 12pt
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
            inheritedStyles = emptyMap(),
            fontCache = fontCache,
        )
    }

    // -----------------------------------------------------------------------
    // keepTogether / keepWithNext
    // -----------------------------------------------------------------------

    @Test
    fun `keepTogether true sets property on element`() {
        val div = Div()
        StyleApplicator.applyStylesWithPreset(
            div,
            blockInlineStyles = mapOf("keepTogether" to true),
            blockStylePreset = null,
            blockStylePresets = emptyMap(),
            inheritedStyles = emptyMap(),
            fontCache = fontCache,
        )
        assertTrue(div.getProperty<Boolean>(Property.KEEP_TOGETHER) == true)
    }

    @Test
    fun `keepTogether string true sets property on element`() {
        val div = Div()
        StyleApplicator.applyStylesWithPreset(
            div,
            blockInlineStyles = mapOf("keepTogether" to "true"),
            blockStylePreset = null,
            blockStylePresets = emptyMap(),
            inheritedStyles = emptyMap(),
            fontCache = fontCache,
        )
        assertTrue(div.getProperty<Boolean>(Property.KEEP_TOGETHER) == true)
    }

    @Test
    fun `keepWithNext true sets property on element`() {
        val div = Div()
        StyleApplicator.applyStylesWithPreset(
            div,
            blockInlineStyles = mapOf("keepWithNext" to true),
            blockStylePreset = null,
            blockStylePresets = emptyMap(),
            inheritedStyles = emptyMap(),
            fontCache = fontCache,
        )
        assertTrue(div.getProperty<Boolean>(Property.KEEP_WITH_NEXT) == true)
    }

    @Test
    fun `keepTogether absent does not set property`() {
        val div = Div()
        StyleApplicator.applyStylesWithPreset(
            div,
            blockInlineStyles = mapOf("marginBottom" to "4pt"),
            blockStylePreset = null,
            blockStylePresets = emptyMap(),
            inheritedStyles = emptyMap(),
            fontCache = fontCache,
        )
        assertEquals(null, div.getProperty<Boolean>(Property.KEEP_TOGETHER))
    }

    // -----------------------------------------------------------------------
    // parseSize unit handling
    // -----------------------------------------------------------------------

    @Test
    fun `parseSize handles pt`() {
        assertEquals(12f, StyleApplicator.parseSize("12pt"))
    }

    @Test
    fun `parseSize handles px as CSS pixels at 96dpi`() {
        assertEquals(12f, StyleApplicator.parseSize("16px"))
        assertEquals(30f, StyleApplicator.parseSize("40px"))
    }

    @Test
    fun `parseSize handles sp via spacing scale`() {
        assertEquals(8f, StyleApplicator.parseSize("2sp"))
    }

    @Test
    fun `parseSize handles mm`() {
        // 1mm ≈ 2.83465pt
        val result = StyleApplicator.parseSize("10mm")
        assertNotNull(result)
        assertEquals(28.3465f, result, 0.0001f)
    }

    @Test
    fun `parseSize returns null for unknown unit`() {
        assertEquals(null, StyleApplicator.parseSize("10rem"))
    }
}
