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
    // V1 default page settings
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
    // Component spacing
    // -----------------------------------------------------------------------

    @Test
    fun `V1 component spacing contains entries for all block types`() {
        val expectedTypes = setOf("text", "container", "columns", "table", "datatable", "image")
        assertEquals(expectedTypes, RenderingDefaults.V1.componentSpacing.keys)
    }

    @Test
    fun `V1 component spacing all have marginBottom 0_5em`() {
        for ((type, defaults) in RenderingDefaults.V1.componentSpacing) {
            assertEquals("0.5em", defaults["marginBottom"], "Expected marginBottom for $type")
        }
    }

    @Test
    fun `componentDefaults returns correct map for known type`() {
        val defaults = RenderingDefaults.V1.componentDefaults("text")
        assertNotNull(defaults)
        assertEquals("0.5em", defaults["marginBottom"])
    }

    @Test
    fun `componentDefaults returns null for unknown type`() {
        assertEquals(null, RenderingDefaults.V1.componentDefaults("unknown"))
    }

    // -----------------------------------------------------------------------
    // Typography
    // -----------------------------------------------------------------------
    // Note: Heading font sizes now come from DefaultStyleSystem.typographyScale
    // These tests verify the margin settings which are still managed by RenderingDefaults

    @Test
    fun `V1 heading margins`() {
        assertEquals(9.6f, RenderingDefaults.V1.headingMargin(1))
        assertEquals(5.4f, RenderingDefaults.V1.headingMargin(2))
        assertEquals(2.8f, RenderingDefaults.V1.headingMargin(3))
    }

    @Test
    fun `headingMargin falls back to proportional value for unknown level`() {
        val expected = 0.2f * RenderingDefaults.V1.baseFontSizePt
        assertEquals(expected, RenderingDefaults.V1.headingMargin(4))
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
