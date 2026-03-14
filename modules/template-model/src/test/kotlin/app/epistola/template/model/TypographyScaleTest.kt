package app.epistola.template.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tests for the TypographyScale system.
 *
 * The typography scale provides font size multipliers for text elements,
 * calculated as: baseFontSize × multiplier = effectiveFontSize
 */
class TypographyScaleTest {

    // -----------------------------------------------------------------------
    // Data loading
    // -----------------------------------------------------------------------

    @Test
    fun `typographyScale is loaded from JSON`() {
        val scale = DefaultStyleSystem.typographyScale
        assertNotNull(scale)
    }

    @Test
    fun `typographyScale contains all required elements`() {
        val scale = DefaultStyleSystem.typographyScale
        assertNotNull(scale.paragraph)
        assertNotNull(scale.heading1)
        assertNotNull(scale.heading2)
        assertNotNull(scale.heading3)
    }

    @Test
    fun `paragraph has multiplier of 1_0`() {
        assertEquals(1.0f, DefaultStyleSystem.typographyScale.paragraph.fontSizeMultiplier.toFloat())
    }

    @Test
    fun `heading1 has multiplier of 2_0`() {
        assertEquals(2.0f, DefaultStyleSystem.typographyScale.heading1.fontSizeMultiplier.toFloat())
    }

    @Test
    fun `heading2 has multiplier of 1_5`() {
        assertEquals(1.5f, DefaultStyleSystem.typographyScale.heading2.fontSizeMultiplier.toFloat())
    }

    @Test
    fun `heading3 has multiplier of 1_25`() {
        assertEquals(1.25f, DefaultStyleSystem.typographyScale.heading3.fontSizeMultiplier.toFloat())
    }

    // -----------------------------------------------------------------------
    // Font size calculation
    // -----------------------------------------------------------------------

    @Test
    fun `calculateFontSize for paragraph with 12pt base returns 12pt`() {
        val result = DefaultStyleSystem.calculateFontSize(
            DefaultStyleSystem.TypographyElementType.PARAGRAPH,
            12f,
            null,
        )
        assertEquals(12f, result)
    }

    @Test
    fun `calculateFontSize for heading1 with 12pt base returns 24pt`() {
        val result = DefaultStyleSystem.calculateFontSize(
            DefaultStyleSystem.TypographyElementType.HEADING1,
            12f,
            null,
        )
        assertEquals(24f, result)
    }

    @Test
    fun `calculateFontSize for heading2 with 12pt base returns 18pt`() {
        val result = DefaultStyleSystem.calculateFontSize(
            DefaultStyleSystem.TypographyElementType.HEADING2,
            12f,
            null,
        )
        assertEquals(18f, result)
    }

    @Test
    fun `calculateFontSize for heading3 with 12pt base returns 15pt`() {
        val result = DefaultStyleSystem.calculateFontSize(
            DefaultStyleSystem.TypographyElementType.HEADING3,
            12f,
            null,
        )
        assertEquals(15f, result) // 12 × 1.25 = 15
    }

    @Test
    fun `calculateFontSize with different base font size`() {
        val result = DefaultStyleSystem.calculateFontSize(
            DefaultStyleSystem.TypographyElementType.HEADING1,
            16f,
            null,
        )
        assertEquals(32f, result) // 16 × 2.0 = 32
    }

    @Test
    fun `calculateFontSize with explicit font size uses explicit value`() {
        val result = DefaultStyleSystem.calculateFontSize(
            DefaultStyleSystem.TypographyElementType.HEADING1,
            12f,
            "30pt",
        )
        assertEquals(30f, result) // Explicit value overrides calculation
    }

    @Test
    fun `calculateFontSize parses pt units`() {
        val result = DefaultStyleSystem.calculateFontSize(
            DefaultStyleSystem.TypographyElementType.HEADING1,
            12f,
            "18pt",
        )
        assertEquals(18f, result)
    }

    @Test
    fun `calculateFontSize parses px units`() {
        val result = DefaultStyleSystem.calculateFontSize(
            DefaultStyleSystem.TypographyElementType.HEADING1,
            12f,
            "24px",
        )
        assertEquals(18f, result) // 24px × 0.75 = 18pt
    }

    @Test
    fun `calculateFontSize parses em units relative to base`() {
        val result = DefaultStyleSystem.calculateFontSize(
            DefaultStyleSystem.TypographyElementType.HEADING1,
            12f,
            "2em",
        )
        assertEquals(24f, result) // 2em × 12pt base = 24pt
    }

    // -----------------------------------------------------------------------
    // Multiplier access
    // -----------------------------------------------------------------------

    @Test
    fun `getFontSizeMultiplier returns correct values`() {
        assertEquals(1.0f, DefaultStyleSystem.getFontSizeMultiplier(DefaultStyleSystem.TypographyElementType.PARAGRAPH))
        assertEquals(2.0f, DefaultStyleSystem.getFontSizeMultiplier(DefaultStyleSystem.TypographyElementType.HEADING1))
        assertEquals(1.5f, DefaultStyleSystem.getFontSizeMultiplier(DefaultStyleSystem.TypographyElementType.HEADING2))
        assertEquals(1.25f, DefaultStyleSystem.getFontSizeMultiplier(DefaultStyleSystem.TypographyElementType.HEADING3))
    }
}
