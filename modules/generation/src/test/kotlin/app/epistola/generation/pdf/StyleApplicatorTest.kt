package app.epistola.generation.pdf

import app.epistola.template.model.DocumentStyles
import app.epistola.template.model.TextAlign
import com.itextpdf.layout.borders.DashedBorder
import com.itextpdf.layout.borders.SolidBorder
import com.itextpdf.layout.element.Div
import com.itextpdf.layout.properties.LineHeight
import com.itextpdf.layout.properties.OverflowPropertyValue
import com.itextpdf.layout.properties.OverflowWrapPropertyValue
import com.itextpdf.layout.properties.Property
import com.itextpdf.layout.properties.UnitValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StyleApplicatorTest {
    @Test
    fun `font weight compatibility supports keyword and numeric values`() {
        val boldWeights = listOf("bold", "bolder", "700", "800", "900")
        val nonBoldWeights = listOf("normal", "lighter", "100", "400", "600", "not-a-weight")

        boldWeights.forEach { weight ->
            assertTrue(
                StyleApplicator.isBoldFontWeight(weight),
                "Expected '$weight' to be treated as bold",
            )
        }

        nonBoldWeights.forEach { weight ->
            assertTrue(
                !StyleApplicator.isBoldFontWeight(weight),
                "Expected '$weight' not to be treated as bold",
            )
        }
    }

    @Test
    fun `document styles convert to inherited map`() {
        val inherited = StyleApplicator.documentStylesToInheritedMap(
            DocumentStyles(
                fontSize = "14px",
                color = "#333333",
                textAlign = TextAlign.Center,
                backgroundColor = "#f0f0f0",
            ),
        )

        assertEquals("14px", inherited["fontSize"])
        assertEquals("#333333", inherited["color"])
        assertEquals("center", inherited["textAlign"])
        assertEquals("#f0f0f0", inherited["backgroundColor"])
    }

    @Test
    fun `resolve inherited styles applies parent then preset then inline`() {
        val resolved = StyleApplicator.resolveInheritedStyles(
            parentInheritedStyles = mapOf(
                "fontSize" to "4rem",
                "color" to "#333333",
            ),
            presetName = "notice",
            blockStylePresets = mapOf(
                "notice" to mapOf(
                    "fontSize" to "3rem",
                    "backgroundColor" to "#ffeecc",
                    "paddingTop" to "8px",
                ),
            ),
            inlineStyles = mapOf(
                "fontSize" to "2rem",
                "color" to "#111111",
                "marginTop" to "12px",
            ),
        )

        assertEquals("2rem", resolved["fontSize"])
        assertEquals("#111111", resolved["color"])
        assertEquals("#ffeecc", resolved["backgroundColor"])
        // Non-inheritable keys are not carried through hierarchy
        assertEquals(null, resolved["paddingTop"])
        assertEquals(null, resolved["marginTop"])
    }

    @Test
    fun `fixture set resolves exactly as expected`() {
        for (fixture in StyleCascadeFixtures.cases) {
            var resolved = fixture.documentStyles ?: emptyMap()

            for (ancestorStyles in fixture.ancestorStyles) {
                resolved = StyleApplicator.resolveInheritedStyles(
                    parentInheritedStyles = resolved,
                    presetName = null,
                    blockStylePresets = emptyMap(),
                    inlineStyles = ancestorStyles,
                )
            }

            resolved = StyleApplicator.resolveInheritedStyles(
                parentInheritedStyles = resolved,
                presetName = null,
                blockStylePresets = emptyMap(),
                inlineStyles = fixture.blockStyles,
            )

            assertEquals(
                fixture.expected,
                resolved,
                "Fixture '${fixture.id}' failed: ${fixture.description}",
            )
        }
    }

    @Test
    fun `fixture ids align with shared cascade contract`() {
        val ids = StyleCascadeFixtures.cases.map { it.id }.toSet()
        val expectedIds = setOf(
            "doc_container_child_inherit_font_size",
            "child_override_wins",
            "deep_ancestor_chain",
            "non_inheritable_not_propagated",
            "background_color_inherited",
        )

        assertEquals(expectedIds, ids)
    }

    @Test
    fun `text align values are normalized during inherited resolution`() {
        val resolved = StyleApplicator.resolveInheritedStyles(
            parentInheritedStyles = mapOf("textAlign" to TextAlign.Center),
            presetName = "alignPreset",
            blockStylePresets = mapOf(
                "alignPreset" to mapOf("textAlign" to TextAlign.Right),
            ),
            inlineStyles = mapOf("textAlign" to "justify"),
        )

        assertEquals("justify", resolved["textAlign"])
        assertTrue(resolved["textAlign"] is String)
    }

    @Test
    fun `line height remains in inherited styles for compatibility`() {
        val inherited = StyleApplicator.documentStylesToInheritedMap(
            DocumentStyles(lineHeight = "1.6"),
        )

        val resolved = StyleApplicator.resolveInheritedStyles(
            parentInheritedStyles = inherited,
            presetName = null,
            blockStylePresets = emptyMap(),
            inlineStyles = null,
        )

        assertEquals("1.6", resolved["lineHeight"])
    }

    @Test
    fun `rem spacing values are applied to block element margins`() {
        val div = Div()

        StyleApplicator.applyStyles(
            element = div,
            blockStyles = mapOf("marginTop" to "2rem"),
            documentStyles = null,
            fontCache = FontCache(),
        )

        val marginTop = div.getProperty<Any>(Property.MARGIN_TOP)
        assertNotNull(marginTop)
        val marginTopPoints = when (marginTop) {
            is Number -> marginTop.toFloat()
            is UnitValue -> marginTop.value
            else -> error("Unexpected margin type: ${marginTop::class}")
        }
        assertEquals(24f, marginTopPoints, 0.001f)
    }

    @Test
    fun `document styles apply line height and font family`() {
        val div = Div()

        StyleApplicator.applyStyles(
            element = div,
            blockStyles = null,
            documentStyles = DocumentStyles(
                fontFamily = "Times-Roman",
                lineHeight = "1.8",
            ),
            fontCache = FontCache(),
        )

        val lineHeight = div.getProperty<Any>(Property.LINE_HEIGHT)
        val font = div.getProperty<Any>(Property.FONT)

        assertNotNull(lineHeight)
        assertNotNull(font)
        assertTrue(lineHeight is LineHeight)
        assertTrue((lineHeight as LineHeight).isMultipliedValue)
        assertEquals(1.8f, lineHeight.value, 0.001f)
    }

    @Test
    fun `block styles apply borders opacity and dimensions`() {
        val div = Div()

        StyleApplicator.applyStyles(
            element = div,
            blockStyles = mapOf(
                "borderStyle" to "dashed",
                "borderWidth" to "2px",
                "borderColor" to "#112233",
                "borderRadius" to "6px",
                "opacity" to "50%",
                "width" to "75%",
                "minHeight" to "2rem",
            ),
            documentStyles = null,
            fontCache = FontCache(),
        )

        val border = div.getProperty<Any>(Property.BORDER_TOP)
        val opacity = div.getProperty<Any>(Property.OPACITY)
        val width = div.getProperty<Any>(Property.WIDTH)
        val minHeight = div.getProperty<Any>(Property.MIN_HEIGHT)
        val topLeftRadius = div.getProperty<Any>(Property.BORDER_TOP_LEFT_RADIUS)

        assertNotNull(border)
        assertNotNull(opacity)
        assertNotNull(width)
        assertNotNull(minHeight)
        assertNotNull(topLeftRadius)

        assertTrue(border is DashedBorder)
        assertEquals(0.5f, opacity as Float, 0.001f)
        assertTrue(width is UnitValue)
        assertTrue((width as UnitValue).isPercentValue)
        assertEquals(75f, width.value, 0.001f)
        assertTrue(minHeight is UnitValue)
        assertEquals(24f, (minHeight as UnitValue).value, 0.001f)
    }

    @Test
    fun `block styles apply overflow white-space and word break`() {
        val div = Div()

        StyleApplicator.applyStyles(
            element = div,
            blockStyles = mapOf(
                "overflow" to "fit",
                "whiteSpace" to "nowrap",
                "wordBreak" to "break-all",
            ),
            documentStyles = null,
            fontCache = FontCache(),
        )

        assertEquals(OverflowPropertyValue.FIT, div.getProperty<Any>(Property.OVERFLOW_X))
        assertEquals(OverflowPropertyValue.FIT, div.getProperty<Any>(Property.OVERFLOW_Y))
        assertEquals(true, div.getProperty<Any>(Property.NO_SOFT_WRAP_INLINE))
        assertEquals(
            OverflowWrapPropertyValue.ANYWHERE,
            div.getProperty<Any>(Property.OVERFLOW_WRAP),
        )
    }

    @Test
    fun `unsupported font family names are ignored in strict mode`() {
        val div = Div()

        StyleApplicator.applyStyles(
            element = div,
            blockStyles = mapOf("fontFamily" to "Georgia, serif"),
            documentStyles = null,
            fontCache = FontCache(),
        )

        assertNull(div.getProperty<Any>(Property.FONT))
    }

    @Test
    fun `legacy overflow aliases are ignored in strict mode`() {
        val div = Div()

        StyleApplicator.applyStyles(
            element = div,
            blockStyles = mapOf("overflow" to "auto"),
            documentStyles = null,
            fontCache = FontCache(),
        )

        assertNull(div.getProperty<Any>(Property.OVERFLOW_X))
        assertNull(div.getProperty<Any>(Property.OVERFLOW_Y))
    }

    @Test
    fun `padding shorthand is expanded to individual sides`() {
        val div = Div()

        StyleApplicator.applyStyles(
            element = div,
            blockStyles = mapOf("padding" to "8px 12px"),
            documentStyles = null,
            fontCache = FontCache(),
        )

        val top = div.getProperty<Any>(Property.PADDING_TOP)
        val right = div.getProperty<Any>(Property.PADDING_RIGHT)
        val bottom = div.getProperty<Any>(Property.PADDING_BOTTOM)
        val left = div.getProperty<Any>(Property.PADDING_LEFT)

        assertNotNull(top)
        assertNotNull(right)
        assertNotNull(bottom)
        assertNotNull(left)
        assertEquals(6f, (top as UnitValue).value, 0.001f)
        assertEquals(9f, (right as UnitValue).value, 0.001f)
        assertEquals(6f, (bottom as UnitValue).value, 0.001f)
        assertEquals(9f, (left as UnitValue).value, 0.001f)
    }

    @Test
    fun `border shorthand applies and side border overrides`() {
        val div = Div()

        StyleApplicator.applyStyles(
            element = div,
            blockStyles = mapOf(
                "border" to "1px solid #112233",
                "borderTop" to "2px dashed #445566",
            ),
            documentStyles = null,
            fontCache = FontCache(),
        )

        val top = div.getProperty<Any>(Property.BORDER_TOP)
        val right = div.getProperty<Any>(Property.BORDER_RIGHT)

        assertNotNull(top)
        assertNotNull(right)
        assertTrue(top is DashedBorder)
        assertTrue(right is SolidBorder)
    }
}
