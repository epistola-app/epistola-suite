// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class StyleShorthandsTest {

    @Test
    fun `a single padding value applies to all four sides`() {
        assertEquals(
            mapOf("paddingTop" to "8pt", "paddingRight" to "8pt", "paddingBottom" to "8pt", "paddingLeft" to "8pt"),
            StyleShorthands.expand(mapOf("padding" to "8pt")),
        )
    }

    @Test
    fun `two, three and four margin values follow CSS order`() {
        assertEquals(
            mapOf("marginTop" to "1pt", "marginRight" to "2sp", "marginBottom" to "1pt", "marginLeft" to "2sp"),
            StyleShorthands.expand(mapOf("margin" to "1pt 2sp")),
        )
        assertEquals(
            mapOf("marginTop" to "1pt", "marginRight" to "2pt", "marginBottom" to "3pt", "marginLeft" to "2pt"),
            StyleShorthands.expand(mapOf("margin" to "1pt 2pt 3pt")),
        )
        assertEquals(
            mapOf("marginTop" to "1pt", "marginRight" to "2pt", "marginBottom" to "3pt", "marginLeft" to "4pt"),
            StyleShorthands.expand(mapOf("margin" to "  1pt 2pt   3pt 4pt ")),
        )
    }

    @Test
    fun `border applies the same shorthand to all four sides`() {
        assertEquals(
            mapOf(
                "borderTop" to "1pt solid #cbd5e1",
                "borderRight" to "1pt solid #cbd5e1",
                "borderBottom" to "1pt solid #cbd5e1",
                "borderLeft" to "1pt solid #cbd5e1",
            ),
            StyleShorthands.expand(mapOf("border" to "1pt solid #cbd5e1")),
        )
    }

    @Test
    fun `an explicit side in the same map wins over the shorthand, whatever the key order`() {
        val sideFirst = linkedMapOf<String, Any>("paddingTop" to "0pt", "padding" to "8pt")
        val shorthandFirst = linkedMapOf<String, Any>("padding" to "8pt", "paddingTop" to "0pt")

        val expected = mapOf("paddingTop" to "0pt", "paddingRight" to "8pt", "paddingBottom" to "8pt", "paddingLeft" to "8pt")
        assertEquals(expected, StyleShorthands.expand(sideFirst))
        assertEquals(expected, StyleShorthands.expand(shorthandFirst))
    }

    @Test
    fun `other keys are kept`() {
        assertEquals(
            mapOf("backgroundColor" to "#fff", "borderRadius" to "4pt", "paddingTop" to "2pt", "paddingRight" to "2pt", "paddingBottom" to "2pt", "paddingLeft" to "2pt"),
            StyleShorthands.expand(mapOf("backgroundColor" to "#fff", "borderRadius" to "4pt", "padding" to "2pt")),
        )
    }

    @Test
    fun `a value that is not one to four parts is dropped, as an unparseable side would be`() {
        assertEquals(emptyMap(), StyleShorthands.expand(mapOf("padding" to "1pt 2pt 3pt 4pt 5pt")))
        assertEquals(emptyMap(), StyleShorthands.expand(mapOf("margin" to "   ")))
        assertEquals(emptyMap(), StyleShorthands.expand(mapOf("padding" to 8)))
    }

    @Test
    fun `a map without shorthands is returned as is`() {
        val styles = mapOf<String, Any>("paddingTop" to "2pt", "color" to "#000")
        assertSame(styles, StyleShorthands.expand(styles))
    }
}
