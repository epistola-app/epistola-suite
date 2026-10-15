// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A heading with `keepWithNext` before a `loop`. From rendering defaults V6 it keeps with the
 * loop's first item, as in Word ([RenderingDefaults.keepWithNextFirstBlock]); before, it waited
 * for the whole loop to fit and moved to the next page with it, leaving the end of a page empty.
 */
class KeepWithNextLoopRenderTest {

    private val items = mapOf("items" to listOf(mapOf("name" to "ONE"), mapOf("name" to "TWO"), mapOf("name" to "THREE")))

    /**
     * Seventeen filler paragraphs leave room on page 1 for the heading and the first card, but not
     * for all three cards (checked below with `keepWithNext` off).
     */
    private fun cardsAfterHeading(keepWithNext: Boolean): app.epistola.template.model.TemplateDocument {
        val doc = BandDoc()
        val heading = doc.text("heading", "HEADING")
        val card = doc.container(
            "card",
            doc.expressionText("card-text", "CARD ", "item.name & '. This card is long enough to wrap across a few lines, so three of them take more room than is left on the first page.'"),
        )
        val base = doc.build(*doc.body("F", 17).toTypedArray(), heading, doc.loop("cards", "items", card))
        return base.copy(nodes = base.nodes + ("heading" to base.nodes.getValue("heading").copy(styles = mapOf("keepWithNext" to keepWithNext))))
    }

    private fun pagesOf(document: app.epistola.template.model.TemplateDocument, defaults: RenderingDefaults): List<Int> {
        val pages = drawnText(renderBands(document, items, defaults))
        fun pageOf(prefix: String) = pages.keys.first { page -> pages.pageText(page).lines().any { it.startsWith(prefix) } }
        return listOf("HEADING", "CARD ONE", "CARD TWO", "CARD THREE").map(::pageOf)
    }

    @Test
    fun `the layout leaves room for the heading and one card on page 1`() {
        assertEquals(listOf(1, 1, 1, 2), pagesOf(cardsAfterHeading(keepWithNext = false), RenderingDefaults.V6))
    }

    @Test
    fun `from V6 the heading stays with the first card on page 1`() {
        assertEquals(listOf(1, 1, 1, 2), pagesOf(cardsAfterHeading(keepWithNext = true), RenderingDefaults.V6))
    }

    @Test
    fun `before V6 the heading moves to page 2 with the whole loop`() {
        assertEquals(listOf(2, 2, 2, 2), pagesOf(cardsAfterHeading(keepWithNext = true), RenderingDefaults.V5))
    }
}
