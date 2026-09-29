// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

/*
 * Page headers and footers placed anywhere in the flow (#1020).
 *
 * Page breaks divide the rendered flow into sections. Headers apply to what comes after them,
 * from their own page at the start of a section and from the next page after content. A footer
 * applies from the page it lands on; the first footer also covers the pages before it. The rules
 * are in `docs/generation.md` and ADR 0027.
 *
 * The body render registers every header and footer it meets as a [PageBandOccurrence] in the
 * [PageBandCollector]. Once the element tree is built, [PageBandSchedule] decides page by page
 * which header fills the top band when the page is created, how much bottom band to reserve,
 * and, once the page is complete, which footer fills that band.
 */

internal enum class PageBandKind { HEADER, FOOTER }

/**
 * One rendered `pageheader` or `pagefooter` in the body flow. A node inside a loop renders once
 * per iteration, so it yields one occurrence per iteration, each with its own data scope.
 */
internal class PageBandOccurrence(
    val kind: PageBandKind,
    val nodeId: String,
    /** Position in flow order among all occurrences. */
    val ordinal: Int,
    /** Index of the section (0-based, split by page breaks) the occurrence sits in. */
    val section: Int,
    /** True when no content comes before it in its section. */
    val atSectionStart: Boolean,
    val loopContext: Map<String, Any?> = emptyMap(),
    val parameterScopes: Map<String, Map<String, Any?>> = emptyMap(),
) {
    /** Band height in points: `max(height prop, measured content)`, set before layout. */
    var heightPt: Float = 0f

    /** Page the occurrence's anchor was drawn on, recorded during layout. */
    var landingPage: Int? = null

    /** The context band content renders with: page-level styles, the occurrence's data scope. */
    fun scope(context: RenderContext): RenderContext = context.copy(
        loopContext = loopContext,
        parameterScopes = parameterScopes,
        pageBands = null,
    )

    override fun toString(): String = "$kind $nodeId#$ordinal(section $section${if (atSectionStart) ", start" else ""})"
}

/**
 * Per-body-render state for page headers and footers. Only the body render carries one (see
 * [RenderContext.pageBands]); band content and band measurement render without it, so a header
 * never registers while its own content is drawn.
 */
class PageBandCollector {
    internal val occurrences = mutableListOf<PageBandOccurrence>()

    /** Number of sections met so far; section 0 starts on page 1. */
    internal var sectionCount = 1
        private set

    private var currentSection = 0
    private var contentSeenInSection = false
    private val pageOneTopListeners = mutableListOf<(Float) -> Unit>()

    /** Section announced by the page break that is about to start a new page. */
    private var pendingSection: Int? = null

    /** Called for a page break in the flow; returns the index of the section it starts. */
    internal fun startSection(): Int {
        currentSection = sectionCount++
        contentSeenInSection = false
        return currentSection
    }

    /** Called after a node that draws something has rendered. */
    internal fun markContent() {
        contentSeenInSection = true
    }

    internal fun register(kind: PageBandKind, nodeId: String, context: RenderContext): PageBandOccurrence = PageBandOccurrence(
        kind = kind,
        nodeId = nodeId,
        ordinal = occurrences.size,
        section = currentSection,
        atSectionStart = !contentSeenInSection,
        loopContext = context.loopContext,
        parameterScopes = context.parameterScopes,
    ).also(occurrences::add)

    /**
     * Registers work that depends on where page-1 body content begins, which is known only once
     * every header has been measured. The address block uses it to size its window reservation.
     */
    internal fun onPageOneBodyTop(listener: (Float) -> Unit) {
        pageOneTopListeners += listener
    }

    internal fun resolvePageOneBodyTop(topPt: Float) {
        pageOneTopListeners.forEach { it(topPt) }
    }

    internal fun announceSection(section: Int) {
        pendingSection = section
    }

    internal fun takePendingSection(): Int? = pendingSection.also { pendingSection = null }
}

/**
 * What a page gets when it is created: its header (null for none) and the footers that could
 * still fill its bottom band, whose tallest decides how much to reserve. Which of them it is
 * becomes known only when the page is complete ([PageBandSchedule.footerOf]).
 */
internal data class PageBandChoice(
    val header: PageBandOccurrence?,
    val footerCandidates: List<PageBandOccurrence>,
)

/**
 * Decides the header and footer of every page, one page at a time and in page order.
 *
 * Headers apply to what comes after them:
 * - headers at the start of a section become eligible on the section's first page, replacing
 *   any still queued, so several of them form a first-page variant;
 * - a header after content becomes eligible on the page after the one it landed on;
 * - one eligible header takes over per page, in flow order, and it lasts until replaced.
 *
 * Footers apply from the page they land on:
 * - the first footer that lands on a page is that page's footer, and it continues on the pages
 *   after it until another footer lands; later footers landing on the same page are skipped;
 * - pages before the first footer lands take the first footer, so a footer at the end of a
 *   document still covers all of it;
 * - since a page's bottom band is reserved before its content is laid out, each page reserves
 *   room for the tallest footer that could still fill it: the one carried over, and every footer
 *   that has not landed yet.
 */
internal class PageBandSchedule(
    occurrences: List<PageBandOccurrence>,
    sectionCount: Int,
) {
    private val startHeadersBySection: Map<Int, List<PageBandOccurrence>> = occurrences
        .filter { it.kind == PageBandKind.HEADER && it.atSectionStart }
        .sortedBy { it.ordinal }
        .groupBy { it.section }

    private val switchHeaders: List<PageBandOccurrence> = occurrences
        .filter { it.kind == PageBandKind.HEADER && !it.atSectionStart }
        .sortedBy { it.ordinal }

    private val footers: List<PageBandOccurrence> = occurrences
        .filter { it.kind == PageBandKind.FOOTER }
        .sortedBy { it.ordinal }

    /** Footer per page (index = page - 1), filled in page order by [footerOf]. */
    private val footerByPage = mutableListOf<PageBandOccurrence?>()

    private val queue = ArrayDeque<PageBandOccurrence>()
    private var active: PageBandOccurrence? = null
    private var section = 0
    private var pageInSection = -1
    private var lastPage = 0

    /** The header of page 1, known before layout because only section-start headers reach it. */
    val pageOneHeader: PageBandOccurrence? get() = startHeadersBySection[0]?.firstOrNull()

    /**
     * The bands of [page]. Pages must be asked for in order, and the landing pages of every
     * header drawn on earlier pages must already be recorded. [startedSection] is the section a
     * page break started on this page, or null when the page continues the current section.
     */
    fun next(page: Int, startedSection: Int?): PageBandChoice {
        check(page == lastPage + 1) { "pages must be scheduled in order: expected ${lastPage + 1}, got $page" }
        lastPage = page
        when {
            startedSection != null -> {
                section = startedSection
                pageInSection = 0
            }

            page == 1 -> {
                section = 0
                pageInSection = 0
            }

            else -> pageInSection++
        }

        switchHeaders.filter { it.landingPage == page - 1 }.forEach(queue::addLast)
        if (pageInSection == 0) {
            startHeadersBySection[section]?.let {
                queue.clear()
                queue.addAll(it)
            }
        }
        queue.removeFirstOrNull()?.let { active = it }

        val carried = if (page == 1) footers.firstOrNull() else footerOf(page - 1)
        val candidates = (listOfNotNull(carried) + footers.filter { it.landingPage == null }).distinct()
        return PageBandChoice(active, candidates)
    }

    /**
     * The footer of [page]. Every footer drawn on this page and the pages before it must already
     * have recorded its landing page, which holds once the page is complete.
     */
    fun footerOf(page: Int): PageBandOccurrence? {
        require(page >= 1) { "page numbers start at 1, got $page" }
        while (footerByPage.size < page) {
            val current = footerByPage.size + 1
            val landed = footers.firstOrNull { it.landingPage == current }
            footerByPage += landed ?: footerByPage.lastOrNull() ?: footers.firstOrNull()
        }
        return footerByPage[page - 1]
    }
}
