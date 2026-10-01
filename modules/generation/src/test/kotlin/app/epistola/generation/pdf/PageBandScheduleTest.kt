// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PageBandScheduleTest {

    /** Builds occurrences in flow order, so each gets the next ordinal. */
    private class Flow {
        val occurrences = mutableListOf<PageBandOccurrence>()
        var sections = 1

        fun header(id: String, section: Int = 0, atStart: Boolean = true, landsOn: Int? = null) = add(PageBandKind.HEADER, id, section, atStart, landsOn)

        fun footer(id: String, landsOn: Int, section: Int = 0) = add(PageBandKind.FOOTER, id, section, false, landsOn)

        private fun add(kind: PageBandKind, id: String, section: Int, atStart: Boolean, landsOn: Int?): PageBandOccurrence {
            sections = maxOf(sections, section + 1)
            return PageBandOccurrence(kind, id, occurrences.size, section, atStart).also {
                it.landingPage = landsOn
                occurrences += it
            }
        }

        /** Schedules [pages] pages; [sectionStarts] maps a page to the section a page break starts on it. */
        fun schedule(pages: Int, sectionStarts: Map<Int, Int> = emptyMap()): List<Pair<String?, String?>> {
            val schedule = PageBandSchedule(occurrences, sections)
            return (1..pages).map { page ->
                val choice = schedule.next(page, sectionStarts[page])
                choice.header?.nodeId to schedule.footerOf(page)?.nodeId
            }
        }
    }

    private fun headers(result: List<Pair<String?, String?>>) = result.map { it.first }

    private fun footers(result: List<Pair<String?, String?>>) = result.map { it.second }

    @Test
    fun `one header at the top applies to every page`() {
        val flow = Flow().apply { header("h") }

        assertEquals(listOf("h", "h", "h"), headers(flow.schedule(3)))
    }

    @Test
    fun `two headers at the top are the first-page variant, the second running to the end`() {
        val flow = Flow().apply {
            header("first")
            header("running")
            sections = 2
        }

        // A page break on page 3 starts a section without headers: the running one continues.
        assertEquals(listOf("first", "running", "running", "running"), headers(flow.schedule(4, mapOf(3 to 1))))
    }

    @Test
    fun `a single-page document never shows the second header of a first-page variant`() {
        val flow = Flow().apply {
            header("first")
            header("running")
        }

        assertEquals(listOf("first"), headers(flow.schedule(1)))
    }

    @Test
    fun `pages before the first header have none`() {
        val flow = Flow().apply { header("letter", section = 1) }

        assertEquals(listOf(null, "letter", "letter"), headers(flow.schedule(3, mapOf(2 to 1))))
    }

    @Test
    fun `a section-start header after a page break applies from that page and gives each section its own first page`() {
        val flow = Flow().apply {
            header("cover")
            header("letter-first", section = 1)
            header("letter-running", section = 1)
            header("terms", section = 2)
        }

        assertEquals(
            listOf("cover", "letter-first", "letter-running", "letter-running", "terms", "terms"),
            headers(flow.schedule(6, mapOf(2 to 1, 5 to 2))),
        )
    }

    @Test
    fun `a header after content takes over from the page after it lands`() {
        val flow = Flow().apply {
            header("intro")
            header("chapter", atStart = false, landsOn = 2)
        }

        assertEquals(listOf("intro", "intro", "chapter", "chapter"), headers(flow.schedule(4)))
    }

    @Test
    fun `headers landing on the same page take over one page at a time in flow order`() {
        val flow = Flow().apply {
            header("a", atStart = false, landsOn = 1)
            header("b", atStart = false, landsOn = 1)
            header("c", atStart = false, landsOn = 3)
        }

        assertEquals(listOf(null, "a", "b", "c", "c"), headers(flow.schedule(5)))
    }

    @Test
    fun `a section-start header replaces switches still queued from the section before`() {
        val flow = Flow().apply {
            header("a", atStart = false, landsOn = 1)
            header("b", atStart = false, landsOn = 1)
            header("next", section = 1)
        }

        // "b" is still queued when the page break starts section 1 on page 3.
        assertEquals(listOf(null, "a", "next", "next"), headers(flow.schedule(4, mapOf(3 to 1))))
    }

    @Test
    fun `one footer at the end of a document covers every page`() {
        val flow = Flow().apply { footer("f", landsOn = 4, section = 2) }

        assertEquals(listOf("f", "f", "f", "f"), footers(flow.schedule(4, mapOf(2 to 1, 4 to 2))))
    }

    @Test
    fun `a footer applies from the page it lands on until the next one lands`() {
        val flow = Flow().apply {
            footer("letter", landsOn = 1)
            footer("terms", landsOn = 3)
        }

        assertEquals(listOf("letter", "letter", "terms", "terms"), footers(flow.schedule(4)))
    }

    @Test
    fun `later footers landing on a page that already has one are skipped`() {
        val flow = Flow().apply {
            footer("first", landsOn = 1)
            footer("skipped", landsOn = 1)
            footer("also-skipped", landsOn = 1)
            footer("later", landsOn = 2)
        }

        assertEquals(listOf("first", "later", "later"), footers(flow.schedule(3)))
    }

    @Test
    fun `pages before the first footer lands take the first footer`() {
        val flow = Flow().apply {
            footer("first", landsOn = 3)
            footer("second", landsOn = 4)
        }

        assertEquals(listOf("first", "first", "first", "second"), footers(flow.schedule(4)))
    }

    @Test
    fun `a page reserves room for the footer carried over and every footer still to land`() {
        val flow = Flow().apply {
            footer("a", landsOn = 1)
            footer("b", landsOn = 3)
        }
        val schedule = PageBandSchedule(flow.occurrences, flow.sections)
        // Landings are recorded as pages complete; replay that by hiding the future ones.
        val landings = flow.occurrences.associateWith { it.landingPage }
        fun candidatesOf(page: Int): List<String> {
            flow.occurrences.forEach { it.landingPage = landings.getValue(it)?.takeIf { landed -> landed < page } }
            return schedule.next(page, null).footerCandidates.map { it.nodeId }
        }

        assertEquals(listOf("a", "b"), candidatesOf(1))
        assertEquals(listOf("a", "b"), candidatesOf(2))
        assertEquals(listOf("a", "b"), candidatesOf(3))
        assertEquals(listOf("b"), candidatesOf(4))
    }

    @Test
    fun `without footers no page reserves a footer band`() {
        val flow = Flow().apply { header("h") }
        val schedule = PageBandSchedule(flow.occurrences, flow.sections)

        assertEquals(emptyList(), schedule.next(1, null).footerCandidates)
        assertNull(schedule.footerOf(1))
    }

    @Test
    fun `occurrences of one node from a loop with a page break give each iteration its own header and footer`() {
        val flow = Flow().apply {
            listOf(1, 2, 4).forEachIndexed { letter, firstPage ->
                header("letterhead", section = letter)
                footer("letterfoot", landsOn = firstPage, section = letter)
            }
        }

        val result = flow.schedule(4, mapOf(2 to 1, 4 to 2))
        val schedule = PageBandSchedule(flow.occurrences, flow.sections)
        val ordinals = (1..4).map { schedule.next(it, mapOf(2 to 1, 4 to 2)[it]).header?.ordinal }

        assertEquals(listOf("letterhead", "letterhead", "letterhead", "letterhead"), headers(result))
        assertEquals(listOf(0, 2, 2, 4), ordinals)
    }

    @Test
    fun `page one's header is the first header at the start of section zero`() {
        val flow = Flow().apply {
            header("switch", atStart = false, landsOn = 1)
            header("later", section = 1)
        }
        assertNull(PageBandSchedule(flow.occurrences, flow.sections).pageOneHeader)

        val withHeader = Flow().apply {
            header("first")
            header("running")
        }
        assertEquals("first", PageBandSchedule(withHeader.occurrences, withHeader.sections).pageOneHeader?.nodeId)
    }

    @Test
    fun `pages must be scheduled in order`() {
        val schedule = PageBandSchedule(emptyList(), 1)
        schedule.next(1, null)

        assertFailsWith<IllegalStateException> { schedule.next(3, null) }
    }
}
