// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `lineHeight` as the editor shows it. From rendering defaults V6 it is a CSS multiplier of the
 * font size ([RenderingDefaults.cssLineHeight]), and a value below 1 no longer drops the last line
 * of a page header or footer ([RenderingDefaults.bandOverflowRoom]). Earlier versions render as
 * they did.
 */
class LineHeightRenderTest {

    private val longText = "This paragraph is long enough to wrap across several lines, so that the distance " +
        "between two baselines can be measured on the page for the line height tests."

    /** A body of one wrapping paragraph, with [lineHeight] and [fontSize] as document styles. */
    private fun body(lineHeight: Any, fontSize: String): TemplateDocument {
        val doc = BandDoc()
        return doc.build(doc.text("p", longText)).copy(documentStylesOverride = mapOf("lineHeight" to lineHeight, "fontSize" to fontSize))
    }

    /** Distance between consecutive baselines of the first page's body text. */
    private fun linePitch(document: TemplateDocument, defaults: RenderingDefaults): Float {
        val baselines = drawnText(renderBands(document, renderingDefaults = defaults)).getValue(1).map { it.y }.distinct().sortedDescending()
        val pitches = baselines.zipWithNext { above, below -> above - below }
        assertTrue(pitches.isNotEmpty(), "the paragraph must wrap")
        return pitches.average().toFloat()
    }

    @Test
    fun `from V6 lines sit font size times lineHeight apart`() {
        for ((fontSize, points) in listOf("10pt" to 10f, "20pt" to 20f)) {
            for (lineHeight in listOf(1.0, 1.25, 0.9)) {
                assertEquals((points * lineHeight).toFloat(), linePitch(body(lineHeight, fontSize), RenderingDefaults.V6), 0.02f, "$fontSize at $lineHeight")
            }
        }
    }

    @Test
    fun `a string lineHeight is read the same way`() {
        assertEquals(12.5f, linePitch(body("1.25", "10pt"), RenderingDefaults.V6), 0.02f)
    }

    @Test
    fun `a heading is spaced by its own font size`() {
        val heading = Node(
            id = "h",
            type = "text",
            props = mapOf(
                "content" to mapOf(
                    "type" to "doc",
                    "content" to listOf(mapOf("type" to "heading", "attrs" to mapOf("level" to 1), "content" to listOf(mapOf("type" to "text", "text" to longText)))),
                ),
            ),
        )
        val document = TemplateDocument(
            root = "root",
            nodes = mapOf("root" to Node(id = "root", type = "root", slots = listOf("root-slot")), "h" to heading),
            slots = mapOf("root-slot" to Slot("root-slot", "root", "children", listOf("h"))),
            documentStylesOverride = mapOf("lineHeight" to 1.5),
        )

        assertEquals(RenderingDefaults.V6.headingFontSize(1) * 1.5f, linePitch(document, RenderingDefaults.V6), 0.02f)
    }

    @Test
    fun `before V6 lineHeight scales the font's own line height`() {
        // Liberation Sans is 1.11 em from ascent to descent, so 1.0 spaces 10pt text 11.1pt apart.
        assertEquals(11.1f, linePitch(body(1.0, "10pt"), RenderingDefaults.V5), 0.02f)
    }

    /**
     * A footer in a band of 18pt: a separator above two 7pt lines, the second with a page number.
     * Its content is taller than 18pt, so the band grows to the measured height.
     */
    private fun withFooter(lineHeight: Any): TemplateDocument {
        val doc = BandDoc()
        val separator = "sep"
        val base = doc.build(
            doc.footer("f", doc.text("left", "FOOTER LEFT"), doc.expressionText("right", "FOOTER PAGE ", "sys.pages.current"), props = mapOf("height" to "18pt")),
            *doc.body("P", 2).toTypedArray(),
        )
        val small = mapOf("fontSize" to "7pt", "marginBottom" to "0sp")
        val nodes = base.nodes + mapOf(
            separator to Node(id = separator, type = "separator", props = mapOf("thickness" to "0.75pt", "width" to "100%", "style" to "solid"), styles = mapOf("marginBottom" to "1sp")),
            "left" to base.nodes.getValue("left").copy(styles = small),
            "right" to base.nodes.getValue("right").copy(styles = small),
        )
        val footerSlot = base.slots.getValue("f-slot")
        return base.copy(
            nodes = nodes,
            slots = base.slots + ("f-slot" to footerSlot.copy(children = listOf(separator) + footerSlot.children)),
            documentStylesOverride = mapOf("lineHeight" to lineHeight),
        )
    }

    private fun footerText(document: TemplateDocument, defaults: RenderingDefaults): String = drawnText(renderBands(document, renderingDefaults = defaults)).pageText(1)

    @Test
    fun `from V6 a lineHeight below 1 keeps the last line of the footer`() {
        val text = footerText(withFooter(0.9), RenderingDefaults.V6)

        assertTrue("FOOTER LEFT" in text, text)
        assertTrue("FOOTER PAGE 1" in text, text)
    }

    @Test
    fun `before V6 a lineHeight below 1 drops the last line of the footer`() {
        val text = footerText(withFooter(0.9), RenderingDefaults.V5)

        assertTrue("FOOTER LEFT" in text, text)
        assertFalse("FOOTER PAGE" in text, text)
    }

    @Test
    fun `the room below the band alone keeps the line, and moves nothing`() {
        val withRoom = RenderingDefaults.V5.copy(bandOverflowRoom = true)

        assertTrue("FOOTER PAGE 1" in footerText(withFooter(0.9), withRoom))
        for (lineHeight in listOf(1.0, 1.25)) {
            val document = withFooter(lineHeight)
            assertEquals(
                drawnText(renderBands(document, renderingDefaults = RenderingDefaults.V5)),
                drawnText(renderBands(document, renderingDefaults = withRoom)),
                "at $lineHeight every chunk keeps its position",
            )
        }
    }

    @Test
    fun `a header with a lineHeight below 1 keeps its last line too`() {
        val doc = BandDoc()
        val base = doc.build(
            doc.header("h", doc.text("first", "HEADER FIRST"), doc.text("second", "HEADER SECOND"), props = mapOf("height" to "10pt")),
            *doc.body("P", 2).toTypedArray(),
        )
        val sep = Node(id = "sep", type = "separator", props = mapOf("thickness" to "0.75pt"))
        val headerSlot = base.slots.getValue("h-slot")
        val document = base.copy(
            nodes = base.nodes + ("sep" to sep),
            slots = base.slots + ("h-slot" to headerSlot.copy(children = listOf("sep") + headerSlot.children)),
            documentStylesOverride = mapOf("lineHeight" to 0.9),
        )

        assertTrue("HEADER SECOND" in drawnText(renderBands(document, renderingDefaults = RenderingDefaults.V6)).pageText(1))
    }
}
