// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import app.epistola.catalog.protocol.FontRef
import com.itextpdf.layout.element.Div
import com.itextpdf.layout.properties.Property
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
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
    // fontFamily resolution
    // -----------------------------------------------------------------------

    @Test
    fun `structured fontFamily ref applies the resolved font`() {
        val liberation = StyleApplicatorTest::class.java
            .getResourceAsStream("/fonts/LiberationSans-Regular.ttf")!!.readBytes()
        val cache = FontCache(fontFamilyResolver = { _, _, _, _ -> liberation })
        val div = Div()

        StyleApplicator.applyStylesWithPreset(
            div,
            blockInlineStyles = mapOf("fontFamily" to mapOf("slug" to "inter", "catalogKey" to "system")),
            blockStylePreset = null,
            blockStylePresets = emptyMap(),
            inheritedStyles = emptyMap(),
            fontCache = cache,
        )

        val applied = div.getProperty<Any?>(Property.FONT)
        assertNotNull(applied, "A structured fontFamily ref must apply a font")
        assertSame(cache.font(FontRef("system", "inter"), weight = 400, italic = false), applied)
    }

    @Test
    fun `legacy string fontFamily does not crash and applies no custom font`() {
        val div = Div()

        StyleApplicator.applyStylesWithPreset(
            div,
            blockInlineStyles = mapOf("fontFamily" to "Helvetica, Arial, sans-serif"),
            blockStylePreset = null,
            blockStylePresets = emptyMap(),
            inheritedStyles = emptyMap(),
            fontCache = fontCache,
        )

        // No structured ref, no bold/italic → font is left to document default.
        assertEquals(null, div.getProperty<Any?>(Property.FONT))
    }

    /**
     * Malformed `fontFamily` values must NOT parse into a [FontRef] (so the
     * resolver is never consulted) and must not throw. We prove "no ref
     * parsed" via behaviour: a resolver that would always supply a custom
     * font if asked, yet no FONT property is set because `parseFontRef`
     * returned null for the malformed input.
     */
    private fun assertMalformedFontFamilyAppliesNoFont(value: Any) {
        val liberation = StyleApplicatorTest::class.java
            .getResourceAsStream("/fonts/LiberationSans-Regular.ttf")!!.readBytes()
        // This resolver would embed a custom font for ANY ref it is asked about.
        val cache = FontCache(fontFamilyResolver = { _, _, _, _ -> liberation })
        val div = Div()

        StyleApplicator.applyStylesWithPreset(
            div,
            blockInlineStyles = mapOf("fontFamily" to value),
            blockStylePreset = null,
            blockStylePresets = emptyMap(),
            inheritedStyles = emptyMap(),
            fontCache = cache,
        )

        // parseFontRef returned null (malformed) → no ref → resolver never
        // consulted → no custom font applied, and no exception thrown.
        assertEquals(
            null,
            div.getProperty<Any?>(Property.FONT),
            "Malformed fontFamily ($value) must not apply a custom font",
        )
    }

    @Test
    fun `malformed fontFamily as a plain string applies no custom font`() {
        assertMalformedFontFamilyAppliesNoFont("Inter")
    }

    @Test
    fun `malformed fontFamily as a map without slug applies no custom font`() {
        assertMalformedFontFamilyAppliesNoFont(mapOf("catalogKey" to "system"))
    }

    @Test
    fun `malformed fontFamily as a map with blank slug applies no custom font`() {
        assertMalformedFontFamilyAppliesNoFont(mapOf("slug" to "   ", "catalogKey" to "system"))
    }

    // -----------------------------------------------------------------------
    // parseFontWeight — CSS font-weight normalisation (1..1000)
    // -----------------------------------------------------------------------

    @Test
    fun `parseFontWeight null defaults to 400`() {
        assertEquals(400, StyleApplicator.parseFontWeight(null))
    }

    @Test
    fun `parseFontWeight keyword normal is 400`() {
        assertEquals(400, StyleApplicator.parseFontWeight("normal"))
    }

    @Test
    fun `parseFontWeight keyword bold is 700`() {
        assertEquals(700, StyleApplicator.parseFontWeight("bold"))
    }

    @Test
    fun `parseFontWeight keyword lighter is 300`() {
        assertEquals(300, StyleApplicator.parseFontWeight("lighter"))
    }

    @Test
    fun `parseFontWeight keyword bolder is 600`() {
        assertEquals(600, StyleApplicator.parseFontWeight("bolder"))
    }

    @Test
    fun `parseFontWeight numeric Int is preserved`() {
        assertEquals(500, StyleApplicator.parseFontWeight(500))
    }

    @Test
    fun `parseFontWeight numeric string is preserved`() {
        assertEquals(500, StyleApplicator.parseFontWeight("500"))
    }

    @Test
    fun `parseFontWeight preserves the 699 700 boundary`() {
        assertEquals(699, StyleApplicator.parseFontWeight(699))
        assertEquals(700, StyleApplicator.parseFontWeight(700))
        assertEquals(699, StyleApplicator.parseFontWeight("699"))
        assertEquals(700, StyleApplicator.parseFontWeight("700"))
    }

    @Test
    fun `parseFontWeight clamps out-of-range numbers to 1 and 1000`() {
        assertEquals(1, StyleApplicator.parseFontWeight(0))
        assertEquals(1, StyleApplicator.parseFontWeight(-50))
        assertEquals(1000, StyleApplicator.parseFontWeight(2000))
        assertEquals(1000, StyleApplicator.parseFontWeight("2000"))
    }

    @Test
    fun `parseFontWeight unrecognised string defaults to 400`() {
        // Documented contract: "unrecognised → 400".
        assertEquals(400, StyleApplicator.parseFontWeight("garbage"))
    }

    // -----------------------------------------------------------------------
    // parseSize unit handling
    // -----------------------------------------------------------------------

    @Test
    fun `parseSize handles pt`() {
        assertEquals(12f, StyleApplicator.parseSize("12pt"))
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

    // -----------------------------------------------------------------------
    // parseSize clamping
    // -----------------------------------------------------------------------

    @Test
    fun `parseSize clamps negative values to zero`() {
        assertEquals(0f, StyleApplicator.parseSize("-10pt"))
        assertEquals(0f, StyleApplicator.parseSize("-5"))
        assertEquals(0f, StyleApplicator.parseSize("-2sp", spacingUnit = 4f))
        assertEquals(0f, StyleApplicator.parseSize("-1mm"))
    }

    @Test
    fun `parseSize preserves positive values`() {
        assertEquals(10f, StyleApplicator.parseSize("10pt"))
        assertEquals(5f, StyleApplicator.parseSize("5"))
        assertEquals(8f, StyleApplicator.parseSize("2sp", spacingUnit = 4f))
    }
}
