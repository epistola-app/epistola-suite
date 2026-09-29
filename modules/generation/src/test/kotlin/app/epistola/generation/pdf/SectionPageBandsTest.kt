// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Page headers and footers anywhere in the flow (#1020), end to end through the renderer.
 * Headers apply to what comes after them; footers apply to what comes before them, per page
 * section. The schedule itself is unit-tested in [PageBandScheduleTest].
 */
class SectionPageBandsTest {

    private fun Map<Int, List<DrawnText>>.headersOn(page: Int): List<String> = pageText(page).lines().filter { it.startsWith("HEADER") }

    private fun Map<Int, List<DrawnText>>.footersOn(page: Int): List<String> = pageText(page).lines().filter { it.startsWith("FOOTER") }

    @Test
    fun `a letter-shell stencil carries its letter's header and footer`() {
        val doc = BandDoc()
        val pages = drawnText(
            renderBands(
                doc.build(
                    doc.text("cover", "Cover page"),
                    doc.pageBreak("break"),
                    doc.stencil(
                        "shell",
                        doc.header("shell-header", doc.text("shell-header-text", "HEADER letterhead")),
                        *doc.body("letter", 30).toTypedArray(),
                        doc.footer("shell-footer", doc.text("shell-footer-text", "FOOTER letter")),
                    ),
                ),
            ),
        )

        assertTrue(pages.size >= 3, "the letter should run over several pages, got ${pages.size}")
        assertEquals(emptyList(), pages.headersOn(1), "the cover comes before the letter's header")
        (2..pages.size).forEach { assertEquals(listOf("HEADER letterhead"), pages.headersOn(it), "page $it") }
        // The footer covers its own section and the footer-less cover above it.
        (1..pages.size).forEach { assertEquals(listOf("FOOTER letter"), pages.footersOn(it), "page $it") }
    }

    @Test
    fun `a header after content takes over from the page after it lands`() {
        val doc = BandDoc()
        val pages = drawnText(
            renderBands(
                doc.build(
                    doc.header("intro", doc.text("intro-text", "HEADER intro")),
                    *doc.body("intro", 8).toTypedArray(),
                    doc.header("chapter", doc.text("chapter-text", "HEADER chapter")),
                    *doc.body("chapter", 40).toTypedArray(),
                ),
            ),
        )

        val landing = (1..pages.size).first { page -> pages.pageText(page).contains("chapter paragraph 1.") }
        (1..landing).forEach { assertEquals(listOf("HEADER intro"), pages.headersOn(it), "page $it") }
        (landing + 1..pages.size).forEach { assertEquals(listOf("HEADER chapter"), pages.headersOn(it), "page $it") }
        assertTrue(landing < pages.size, "the chapter should continue past the page its header lands on")
    }

    @Test
    fun `each section's footers cover it, and a section below the last footer has none`() {
        val doc = BandDoc()
        val pages = drawnText(
            renderBands(
                doc.build(
                    doc.text("cover", "Cover"),
                    doc.pageBreak("break-1"),
                    doc.text("letter", "Letter"),
                    doc.footer("letter-footer", doc.text("letter-footer-text", "FOOTER letter")),
                    doc.pageBreak("break-2"),
                    doc.text("terms", "Terms"),
                    doc.footer("terms-footer", doc.text("terms-footer-text", "FOOTER terms")),
                    doc.pageBreak("break-3"),
                    doc.text("appendix", "Appendix"),
                ),
            ),
        )

        assertEquals(4, pages.size)
        assertEquals(listOf("FOOTER letter"), pages.footersOn(1))
        assertEquals(listOf("FOOTER letter"), pages.footersOn(2))
        assertEquals(listOf("FOOTER terms"), pages.footersOn(3))
        assertEquals(emptyList(), pages.footersOn(4))
    }

    @Test
    fun `adjacent footers in a section are a first-page variant`() {
        val doc = BandDoc()
        val pages = drawnText(
            renderBands(
                doc.build(
                    *doc.body("body", 40).toTypedArray(),
                    doc.footer("first", doc.text("first-text", "FOOTER first")),
                    doc.footer("running", doc.text("running-text", "FOOTER running")),
                ),
            ),
        )

        assertEquals(listOf("FOOTER first"), pages.footersOn(1))
        (2..pages.size).forEach { assertEquals(listOf("FOOTER running"), pages.footersOn(it), "page $it") }
    }

    @Test
    fun `a header in a false conditional never applies`() {
        val doc = BandDoc()
        val pages = drawnText(
            renderBands(
                doc.build(
                    doc.conditional("shown", "true", doc.header("yes", doc.text("yes-text", "HEADER shown"))),
                    doc.conditional("hidden", "false", doc.header("no", doc.text("no-text", "HEADER hidden"))),
                    *doc.body("body", 30).toTypedArray(),
                ),
            ),
        )

        (1..pages.size).forEach { assertEquals(listOf("HEADER shown"), pages.headersOn(it), "page $it") }
    }

    @Test
    fun `a loop with a page break gives every letter its own letterhead and footer`() {
        val doc = BandDoc()
        val pages = drawnText(
            renderBands(
                doc.build(
                    doc.loop(
                        "letters",
                        "recipients",
                        doc.pageBreak("letter-break"),
                        doc.header("letterhead", doc.expressionText("letterhead-text", "HEADER for ", "item.name")),
                        doc.text("letter-body", "Dear reader"),
                        doc.footer("letterfoot", doc.expressionText("letterfoot-text", "FOOTER for ", "item.name")),
                    ),
                ),
                data = mapOf("recipients" to listOf(mapOf("name" to "Alice"), mapOf("name" to "Bob"), mapOf("name" to "Carol"))),
            ),
        )

        // The first break starts on an empty first page, so letters start on page 2.
        val letterPages = (1..pages.size).filter { pages.pageText(it).contains("Dear reader") }
        assertEquals(3, letterPages.size)
        listOf("Alice", "Bob", "Carol").zip(letterPages).forEach { (name, page) ->
            assertEquals(listOf("HEADER for $name"), pages.headersOn(page), "page $page")
            assertEquals(listOf("FOOTER for $name"), pages.footersOn(page), "page $page")
        }
    }

    @Test
    fun `a page break nested in a container starts a section`() {
        val doc = BandDoc()
        val pages = drawnText(
            renderBands(
                doc.build(
                    doc.header("first", doc.text("first-text", "HEADER first")),
                    doc.text("page-1", "Page one"),
                    doc.container(
                        "section",
                        doc.pageBreak("nested-break"),
                        doc.header("second", doc.text("second-text", "HEADER second")),
                        doc.text("page-2", "Page two"),
                    ),
                ),
            ),
        )

        assertEquals(2, pages.size)
        assertEquals(listOf("HEADER first"), pages.headersOn(1))
        assertEquals(listOf("HEADER second"), pages.headersOn(2))
    }

    @Test
    fun `each page reserves the band of its own header`() {
        val doc = BandDoc()
        val pages = drawnText(
            renderBands(
                doc.build(
                    doc.header(
                        "tall",
                        doc.text("tall-1", "HEADER tall"),
                        doc.text("tall-2", "line two"),
                        doc.text("tall-3", "line three"),
                        doc.text("tall-4", "line four"),
                        doc.text("tall-5", "line five"),
                        doc.text("tall-6", "line six"),
                        props = mapOf("height" to "10pt"),
                    ),
                    doc.text("first-body", "First body line"),
                    doc.pageBreak("break"),
                    doc.header("short", doc.text("short-text", "HEADER short"), props = mapOf("height" to "10pt")),
                    doc.text("second-body", "Second body line"),
                ),
            ),
        )

        val tallBottom = pages.baselineOf(1, "line six")
        val firstBody = pages.baselineOf(1, "First body line")
        val secondBody = pages.baselineOf(2, "Second body line")
        assertTrue(firstBody < tallBottom, "page 1's body must start below its six-line header")
        assertTrue(secondBody > firstBody + 50f, "page 2's body sits under its one-line header, well above page 1's")
    }

    @Test
    fun `a header's band height is measured per loop occurrence`() {
        val doc = BandDoc()
        val pages = drawnText(
            renderBands(
                doc.build(
                    doc.loop(
                        "letters",
                        "recipients",
                        doc.pageBreak("letter-break"),
                        doc.header(
                            "letterhead",
                            doc.expressionText("letterhead-text", "HEADER ", "item.name"),
                            doc.conditional("long", "item.long", doc.text("extra-1", "extra line one"), doc.text("extra-2", "extra line two"), doc.text("extra-3", "extra line three")),
                            props = mapOf("height" to "10pt"),
                        ),
                        doc.expressionText("letter-body", "Body for ", "item.name"),
                    ),
                ),
                data = mapOf("recipients" to listOf(mapOf("name" to "short", "long" to false), mapOf("name" to "long", "long" to true))),
            ),
        )

        val shortPage = (1..pages.size).first { pages.pageText(it).contains("Body for short") }
        val longPage = (1..pages.size).first { pages.pageText(it).contains("Body for long") }
        assertFalse(pages.pageText(shortPage).contains("extra line"))
        assertContains(pages.pageText(longPage), "extra line three")
        assertTrue(
            pages.baselineOf(longPage, "Body for long") < pages.baselineOf(shortPage, "Body for short") - 30f,
            "the three extra header lines must push that letter's body down",
        )
        assertTrue(pages.baselineOf(longPage, "Body for long") < pages.baselineOf(longPage, "extra line three"))
    }

    @Test
    fun `the page total is right in a footer of a sectioned document`() {
        val doc = BandDoc()
        val pages = drawnText(
            renderBands(
                doc.build(
                    doc.text("one", "One"),
                    doc.footer("footer-a", doc.expressionText("footer-a-text", "FOOTER a of ", "sys.pages.total")),
                    doc.pageBreak("break"),
                    doc.text("two", "Two"),
                    doc.footer("footer-b", doc.expressionText("footer-b-text", "FOOTER b of ", "sys.pages.total")),
                ),
            ),
        )

        assertEquals(listOf("FOOTER a of 2"), pages.footersOn(1))
        assertEquals(listOf("FOOTER b of 2"), pages.footersOn(2))
    }
}
