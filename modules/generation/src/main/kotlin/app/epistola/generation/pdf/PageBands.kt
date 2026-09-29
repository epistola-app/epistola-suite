// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

/*
 * Page headers and footers placed anywhere in the flow (#1020).
 *
 * Page breaks divide the rendered flow into sections. Headers apply to what comes after them;
 * footers apply to what comes before them. The rules, and why footers cannot switch in the
 * middle of a section, are in `docs/plans/flow-anchored-running-headers.md`.
 *
 * The body render registers every header and footer it meets as a [PageBandOccurrence] in the
 * [PageBandCollector]. Once the element tree is built, [PageBandSchedule] decides page by page
 * which occurrence fills the top and bottom band. Layout asks it when each page is created.
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

/** Which header and footer occurrence fill the bands of one page; null means no band. */
internal data class PageBandChoice(
    val header: PageBandOccurrence?,
    val footer: PageBandOccurrence?,
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
 * Footers apply to what comes before them, which is decided per section before layout:
 * - a section's own footers, in flow order, cover its first page, second page, and so on,
 *   the last one covering the rest;
 * - a section without footers takes the running (last) footer of the nearest section after it;
 * - sections after the last footer have none.
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

    private val footersBySection: List<List<PageBandOccurrence>> = run {
        val own = occurrences.filter { it.kind == PageBandKind.FOOTER }.sortedBy { it.ordinal }.groupBy { it.section }
        val result = MutableList(sectionCount) { own[it].orEmpty() }
        var below: PageBandOccurrence? = null
        for (section in sectionCount - 1 downTo 0) {
            if (result[section].isNotEmpty()) {
                below = result[section].last()
            } else if (below != null) {
                result[section] = listOf(below)
            }
        }
        result
    }

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

        val footers = footersBySection.getOrElse(section) { emptyList() }
        val footer = if (footers.isEmpty()) null else footers[minOf(pageInSection, footers.lastIndex)]
        return PageBandChoice(active, footer)
    }
}
