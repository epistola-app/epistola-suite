// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import app.epistola.template.model.TemplateDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Documents the positional model accepts (at most two root-level headers, one footer) must lay
 * out the same under the section-band model (#1020): every text chunk on every page, bands and
 * body alike, at the same position. RenderingDefaults V4 differs from V3 only in
 * `sectionPageBands`, so any difference here comes from the band model.
 */
class PageBandParityTest {

    private fun assertSameLayout(document: TemplateDocument, data: Map<String, Any?> = emptyMap()) {
        val positional = drawnText(renderBands(document, data, RenderingDefaults.V3))
        val sections = drawnText(renderBands(document, data, RenderingDefaults.V4))

        assertTrue(positional.size > 1, "the parity document should span several pages")
        assertEquals(positional.keys, sections.keys, "page count")
        positional.keys.forEach { page ->
            assertEquals(positional.getValue(page), sections.getValue(page), "page $page")
        }
    }

    @Test
    fun `one header and one footer`() {
        val doc = BandDoc()
        assertSameLayout(
            doc.build(
                doc.header("header", doc.text("header-text", "HEADER")),
                *doc.body("body", 40).toTypedArray(),
                doc.footer("footer", doc.expressionText("footer-text", "Page ", "sys.pages.current")),
            ),
        )
    }

    @Test
    fun `a tall first-page header and a running header`() {
        val doc = BandDoc()
        assertSameLayout(
            doc.build(
                doc.header(
                    "first",
                    doc.text("first-1", "FIRST PAGE HEADER"),
                    doc.text("first-2", "second line"),
                    doc.text("first-3", "third line"),
                    props = mapOf("height" to "40pt"),
                ),
                doc.header("running", doc.text("running-text", "RUNNING HEADER"), props = mapOf("height" to "30pt")),
                *doc.body("body", 40).toTypedArray(),
            ),
        )
    }

    @Test
    fun `a footer at the end of a document with page breaks, hidden on the first page`() {
        val doc = BandDoc()
        assertSameLayout(
            doc.build(
                doc.header("header", doc.text("header-text", "HEADER")),
                *doc.body("intro", 3).toTypedArray(),
                doc.pageBreak("break-1"),
                *doc.body("letter", 30).toTypedArray(),
                doc.pageBreak("break-2"),
                *doc.body("terms", 2).toTypedArray(),
                doc.footer("footer", doc.text("footer-text", "FOOTER"), props = mapOf("hideOnFirstPage" to true)),
            ),
        )
    }

    @Test
    fun `an address block under a header`() {
        val doc = BandDoc()
        assertSameLayout(
            doc.build(
                doc.addressBlock("address"),
                doc.header("header", doc.text("header-text", "HEADER"), doc.text("header-2", "letterhead line")),
                *doc.body("body", 40).toTypedArray(),
                doc.footer("footer", doc.text("footer-text", "FOOTER")),
            ),
        )
    }

    @Test
    fun `a footer with the total page count, rendered in two passes`() {
        val doc = BandDoc()
        assertSameLayout(
            doc.build(
                doc.header("header", doc.text("header-text", "HEADER")),
                *doc.body("body", 30).toTypedArray(),
                doc.pageBreak("break"),
                *doc.body("more", 4).toTypedArray(),
                doc.footer("footer", doc.expressionText("footer-text", "Total ", "sys.pages.total")),
            ),
        )
    }

    @Test
    fun `header and footer margin overrides`() {
        val doc = BandDoc()
        val header = doc.header("header", doc.text("header-text", "HEADER"))
        val footer = doc.footer("footer", doc.text("footer-text", "FOOTER"))
        val built = doc.build(header, *doc.body("body", 40).toTypedArray(), footer)
        val styled = built.copy(
            nodes = built.nodes +
                (header to built.nodes.getValue(header).copy(styles = mapOf("marginTop" to "10pt", "marginLeft" to "30pt"))) +
                (footer to built.nodes.getValue(footer).copy(styles = mapOf("marginBottom" to "5pt"))),
        )
        assertSameLayout(styled)
    }

    @Test
    fun `no header or footer, with page breaks`() {
        val doc = BandDoc()
        assertSameLayout(
            doc.build(
                *doc.body("one", 6).toTypedArray(),
                doc.pageBreak("break"),
                *doc.body("two", 6).toTypedArray(),
            ),
        )
    }
}
