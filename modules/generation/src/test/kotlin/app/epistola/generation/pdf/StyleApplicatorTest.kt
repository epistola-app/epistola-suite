package app.epistola.generation.pdf

import app.epistola.template.model.DocumentStyles
import app.epistola.template.model.TextAlign
import org.junit.jupiter.api.Assertions.assertEquals
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
}
