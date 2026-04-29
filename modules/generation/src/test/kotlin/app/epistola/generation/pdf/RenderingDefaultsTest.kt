package app.epistola.generation.pdf

import app.epistola.template.model.Orientation
import app.epistola.template.model.PageFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class RenderingDefaultsTest {

    // -----------------------------------------------------------------------
    // Version registry
    // -----------------------------------------------------------------------

    @Test
    fun `forVersion returns V1 for version 1`() {
        assertSame(RenderingDefaults.V1, RenderingDefaults.forVersion(1))
    }

    @Test
    fun `forVersion throws for unknown version`() {
        assertFailsWith<IllegalArgumentException> {
            RenderingDefaults.forVersion(999)
        }
    }

    @Test
    fun `CURRENT is V1`() {
        assertSame(RenderingDefaults.V1, RenderingDefaults.CURRENT)
    }

    // -----------------------------------------------------------------------
    // Default page settings
    // -----------------------------------------------------------------------

    @Test
    fun `V1 default page is A4 portrait with 20mm margins`() {
        val ps = RenderingDefaults.V1.defaultPageSettings
        assertEquals(PageFormat.A4, ps.format)
        assertEquals(Orientation.portrait, ps.orientation)
        assertEquals(20, ps.margins.top)
        assertEquals(20, ps.margins.right)
        assertEquals(20, ps.margins.bottom)
        assertEquals(20, ps.margins.left)
    }

    // -----------------------------------------------------------------------
    // Component spacing (sp tokens)
    // -----------------------------------------------------------------------

    @Test
    fun `V1 component spacing contains entries for all block types`() {
        val expectedTypes = setOf("text", "container", "columns", "table", "datatable", "image", "qrcode", "separator")
        assertEquals(expectedTypes, RenderingDefaults.V1.componentSpacing.keys)
    }

    @Test
    fun `V1 component spacing all use sp tokens`() {
        for ((type, defaults) in RenderingDefaults.V1.componentSpacing) {
            val marginBottom = defaults["marginBottom"] as? String
            assertNotNull(marginBottom, "Expected marginBottom for $type")
            assert(marginBottom.endsWith("sp")) { "$type marginBottom should use sp unit, was: $marginBottom" }
        }
    }

    @Test
    fun `componentDefaults returns correct map for known type`() {
        val defaults = RenderingDefaults.V1.componentDefaults("text")
        assertNotNull(defaults)
        assertEquals("0sp", defaults["marginBottom"])
    }

    @Test
    fun `componentDefaults returns null for unknown type`() {
        assertEquals(null, RenderingDefaults.V1.componentDefaults("unknown"))
    }

    @Test
    fun `V1 paragraph and table-cell spacing default to zero`() {
        assertEquals(0f, RenderingDefaults.V1.paragraphMarginBottom)
        assertEquals(0f, RenderingDefaults.V1.tableCellPadding)
    }

    // -----------------------------------------------------------------------
    // Typography
    // -----------------------------------------------------------------------

    @Test
    fun `V1 heading font sizes`() {
        assertEquals(24f, RenderingDefaults.V1.headingFontSize(1))
        assertEquals(18f, RenderingDefaults.V1.headingFontSize(2))
        assertEquals(14f, RenderingDefaults.V1.headingFontSize(3))
    }

    @Test
    fun `headingFontSize falls back to baseFontSizePt for unknown level`() {
        assertEquals(RenderingDefaults.V1.baseFontSizePt, RenderingDefaults.V1.headingFontSize(4))
    }

    @Test
    fun `V1 heading margins are grid-aligned`() {
        val baseUnit = SpacingScale.DEFAULT_BASE_UNIT
        for ((level, margin) in RenderingDefaults.V1.headingMargins) {
            val remainder = margin % baseUnit
            assertEquals(0f, remainder, "H$level heading margin ${margin}pt should be a multiple of ${baseUnit}pt")
        }
    }

    @Test
    fun `headingMargin falls back to proportional value for unknown level`() {
        val expected = 0.2f * RenderingDefaults.V1.baseFontSizePt
        assertEquals(expected, RenderingDefaults.V1.headingMargin(4))
    }

    // -----------------------------------------------------------------------
    // Grid alignment
    // -----------------------------------------------------------------------

    @Test
    fun `V1 list spacing is grid-aligned`() {
        val baseUnit = SpacingScale.DEFAULT_BASE_UNIT
        assertEquals(0f, RenderingDefaults.V1.listMarginBottom % baseUnit, "listMarginBottom not grid-aligned")
        assertEquals(0f, RenderingDefaults.V1.listMarginLeft % baseUnit, "listMarginLeft not grid-aligned")
        assertEquals(0f, RenderingDefaults.V1.listItemMarginBottom % (baseUnit / 2f), "listItemMarginBottom not grid-aligned")
    }

    @Test
    fun `V1 table and column spacing is grid-aligned`() {
        val baseUnit = SpacingScale.DEFAULT_BASE_UNIT
        assertEquals(0f, RenderingDefaults.V1.tableCellPadding % baseUnit, "tableCellPadding not grid-aligned")
        assertEquals(0f, RenderingDefaults.V1.columnGap % baseUnit, "columnGap not grid-aligned")
    }

    // -----------------------------------------------------------------------
    // Engine version string
    // -----------------------------------------------------------------------

    @Test
    fun `engineVersionString follows expected format`() {
        val version = RenderingDefaults.V1.engineVersionString()
        assert(version.startsWith("epistola-gen-1+itext-")) { "Unexpected format: $version" }
    }
}
