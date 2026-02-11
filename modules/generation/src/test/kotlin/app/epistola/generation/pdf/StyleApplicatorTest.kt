package app.epistola.generation.pdf

import app.epistola.template.model.DocumentStyles
import app.epistola.template.model.TextAlign
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StyleApplicatorTest {
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
}
