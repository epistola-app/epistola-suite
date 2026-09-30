// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.Orientation
import app.epistola.template.model.PageFormat
import app.epistola.template.model.TemplateDocument
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.event.PdfDocumentEvent
import com.itextpdf.layout.Document
import org.slf4j.LoggerFactory
import java.io.OutputStream

/*
 * How [DirectPdfRenderer] lays out page header and footer bands: the positional model of
 * RenderingDefaults V1–V3 (at most two root-level headers, one footer) and the section-band model
 * of V4 (#1020, see PageBands.kt), plus the band measurement both share.
 */

private val log = LoggerFactory.getLogger("app.epistola.generation.pdf.PageBandLayout")

/** The iText page size for a template's page format and orientation. */
internal fun getPageSize(format: PageFormat, orientation: Orientation): PageSize {
    val baseSize = when (format) {
        PageFormat.A4 -> PageSize.A4
        PageFormat.Letter -> PageSize.LETTER
        PageFormat.Custom -> PageSize.A4 // Default to A4 for custom
    }

    return when (orientation) {
        Orientation.portrait -> baseSize
        Orientation.landscape -> baseSize.rotate()
    }
}

/** How the header and footer bands of one render are laid out. */
internal sealed interface BandPlan {
    /**
     * The positional model of [RenderingDefaults] V1–V3: at most two root-level headers
     * (page 1, pages 2–N) and one footer on every page, measured once before layout.
     */
    data class Positional(
        val headerNodes: List<Node>,
        val footerNode: Node?,
        val effectiveHeights: Map<String, Float>,
        val topMargin: Float,
        val bottomMargin: Float,
        val firstPageSpacer: Float,
    ) : BandPlan

    /** Headers and footers anywhere in the flow, chosen per page by section (#1020). */
    data class Sections(val fontFamilyResolver: FontFamilyResolver?) : BandPlan
}

internal fun bandPlan(
    document: TemplateDocument,
    context: RenderContext,
    pageSettings: app.epistola.template.model.PageSettings,
    renderingDefaults: RenderingDefaults,
    pdfaCompliant: Boolean,
    fontFamilyResolver: FontFamilyResolver?,
): BandPlan {
    if (renderingDefaults.sectionPageBands) return BandPlan.Sections(fontFamilyResolver)

    val headerNodes = pageHeaderNodesInDocumentOrder(document)
    val footerNode = document.nodes.values.firstOrNull { it.type == "pagefooter" }
    val layout = resolveBandLayout(document, headerNodes, footerNode, context, pageSettings, renderingDefaults, pdfaCompliant, fontFamilyResolver)

    // The body's page-edge margins follow the same cascade as headers/footers:
    // header/footer.margin{Side} → root.margin{Side} → pageSettings.margins.
    // Each page's body must sit below its own pageheader band: page 1 below
    // the first-page (index 0) header, pages 2+ below the running (index 1)
    // header. iText's Document margins are document-scoped, so we set the
    // *running* band as the body topMargin and prepend a spacer Div on page 1
    // sized to the extra first-page band height. See computeHeaderBands.
    val bottomMargin = if (footerNode != null) {
        effectivePageMarginPt(footerNode, "marginBottom", context) + layout.footerHeightPt
    } else {
        effectivePageMarginPt(null, "marginBottom", context)
    }
    return BandPlan.Positional(
        headerNodes = headerNodes,
        footerNode = footerNode,
        effectiveHeights = layout.effectiveHeights,
        topMargin = layout.bands.runningBand,
        bottomMargin = bottomMargin,
        firstPageSpacer = layout.bands.firstPageSpacer,
    )
}

internal fun registerPositionalBandHandlers(
    bands: BandPlan.Positional,
    pdfDocument: PdfDocument,
    document: TemplateDocument,
    context: RenderContext,
    registry: NodeRendererRegistry,
) {
    if (bands.headerNodes.isNotEmpty()) {
        pdfDocument.addEventHandler(
            PdfDocumentEvent.END_PAGE,
            PageHeaderEventHandler(
                headerNodeIds = bands.headerNodes.map { it.id },
                document = document,
                context = context,
                registry = registry,
                effectiveHeights = bands.effectiveHeights,
            ),
        )
    }
    bands.footerNode?.let {
        pdfDocument.addEventHandler(
            PdfDocumentEvent.END_PAGE,
            PageFooterEventHandler(
                footerNodeId = it.id,
                document = document,
                context = context,
                registry = registry,
                effectiveHeights = bands.effectiveHeights,
            ),
        )
    }
}

/**
 * Renders the body flow with a [PageBandCollector], measures every header and footer it
 * met, and installs the per-page margins that the [PageBandSchedule] chooses. Returns the
 * body elements, ready to add to [iTextDocument]; [state] fills in as iText creates pages.
 */
internal fun renderWithSectionBands(
    iTextDocument: Document,
    hoistedDocument: TemplateDocument,
    context: RenderContext,
    registry: NodeRendererRegistry,
    bands: BandPlan.Sections,
    pageSettings: app.epistola.template.model.PageSettings,
    pdfaCompliant: Boolean,
    rightMargin: Float,
    leftMargin: Float,
    state: PageBandState,
): List<com.itextpdf.layout.element.IElement> {
    val collector = PageBandCollector()
    val elements = registry.renderNode(hoistedDocument.root, hoistedDocument, context.copy(pageBands = collector))

    val occurrences = collector.occurrences
    val heights = measureBandHeights(
        document = hoistedDocument,
        requests = occurrences.map { occurrence ->
            BandMeasureRequest(
                node = hoistedDocument.nodes.getValue(occurrence.nodeId),
                isHeader = occurrence.kind == PageBandKind.HEADER,
                scope = occurrence::scope,
            )
        },
        context = context,
        pageSettings = pageSettings,
        pdfaCompliant = pdfaCompliant,
        fontFamilyResolver = bands.fontFamilyResolver,
    )
    occurrences.zip(heights).forEach { (occurrence, height) -> occurrence.heightPt = height }

    fun topBand(header: PageBandOccurrence?): Float = header?.let {
        effectivePageMarginPt(hoistedDocument.nodes[it.nodeId], "marginTop", context) + it.heightPt
    } ?: effectivePageMarginPt(null, "marginTop", context)

    fun bottomBand(footer: PageBandOccurrence?): Float = footer?.let {
        effectivePageMarginPt(hoistedDocument.nodes[it.nodeId], "marginBottom", context) + it.heightPt
    } ?: effectivePageMarginPt(null, "marginBottom", context)

    val schedule = PageBandSchedule(occurrences, collector.sectionCount)
    state.schedule = schedule
    collector.resolvePageOneBodyTop(topBand(schedule.pageOneHeader))
    iTextDocument.setPageMargins { page: Int ->
        val choice = state.choices.getOrPut(page) { schedule.next(page, collector.takePendingSection()) }
        val bottom = choice.footerCandidates.maxOfOrNull(::bottomBand) ?: bottomBand(null)
        PageBandMargins(topBand(choice.header), rightMargin, bottom, leftMargin)
    }
    return elements
}

/**
 * Heights derived from the (up to two) pageheader nodes:
 *  - `runningBand`     — body topMargin used for the iText Document.
 *    Equals the running (index 1) header band, or the only header band if a
 *    single header is declared, or the page-edge margin fallback when no
 *    pageheader is present.
 *  - `firstPageSpacer` — extra height that page 1 needs to clear the
 *    first-page header. Injected as an invisible Div at the start of the
 *    body flow when > 0. From page 2 onward the spacer is consumed.
 */
private data class HeaderBands(val runningBand: Float, val firstPageSpacer: Float)

private fun computeHeaderBands(
    headerNodes: List<Node>,
    context: RenderContext,
    renderingDefaults: RenderingDefaults,
    effectiveHeights: Map<String, Float>,
): HeaderBands {
    fun band(node: Node): Float = effectivePageMarginPt(node, "marginTop", context) +
        (effectiveHeights[node.id] ?: parseNodeHeight(node, context) ?: renderingDefaults.pageHeaderHeight)

    val firstHeader = headerNodes.getOrNull(0)
    val runningHeader = headerNodes.getOrNull(1) ?: firstHeader

    val noHeaderMargin = effectivePageMarginPt(null, "marginTop", context)
    val runningBand = runningHeader?.let(::band) ?: noHeaderMargin
    val firstPageBand = firstHeader?.let(::band) ?: noHeaderMargin

    return HeaderBands(
        runningBand = runningBand,
        firstPageSpacer = (firstPageBand - runningBand).coerceAtLeast(0f),
    )
}

/** Resolved header/footer band sizing for one render pass. */
private data class BandLayout(
    /** `nodeId → max(configured, measured content)` height, for the event handlers. */
    val effectiveHeights: Map<String, Float>,
    /** Body top-margin band(s) derived from the effective header heights. */
    val bands: HeaderBands,
    /** Effective footer band height in points (0 when there is no footer). */
    val footerHeightPt: Float,
)

/**
 * Measures the effective header/footer heights and derives the body bands +
 * footer height from them, in one place for both the single- and two-pass
 * render paths. [document] must already be address-block-hoisted.
 */
private fun resolveBandLayout(
    document: TemplateDocument,
    headerNodes: List<Node>,
    footerNode: Node?,
    context: RenderContext,
    pageSettings: app.epistola.template.model.PageSettings,
    renderingDefaults: RenderingDefaults,
    pdfaCompliant: Boolean,
    fontFamilyResolver: FontFamilyResolver?,
): BandLayout {
    val effectiveHeights = measureEffectiveBandHeights(
        document,
        headerNodes,
        footerNode,
        context,
        pageSettings,
        renderingDefaults,
        pdfaCompliant,
        fontFamilyResolver,
    )
    val bands = computeHeaderBands(headerNodes, context, renderingDefaults, effectiveHeights)
    val footerHeightPt = footerNode?.let {
        effectiveHeights[it.id] ?: parseNodeHeight(it, context) ?: renderingDefaults.pageFooterHeight
    } ?: 0f
    return BandLayout(effectiveHeights, bands, footerHeightPt)
}

/**
 * Pre-renders each page header / footer into a discarded layout context to
 * discover its natural content height, returning `nodeId → effective height`
 * where effective height is `max(configured height, content height)`. This is
 * what lets a header/footer grow to fit content instead of clipping it: the
 * returned heights drive both the reserved body margin (via
 * [computeHeaderBands] / the footer band) and the rectangle each event handler
 * draws into.
 *
 * The measurement runs in its own throwaway [PdfDocument] with its own
 * [FontCache] — `FontCache` PdfFonts are bound to a single document and must
 * not leak into the real render. Returns an empty map (and does no work) when
 * the document has neither a header nor a footer.
 */
private fun measureEffectiveBandHeights(
    document: TemplateDocument,
    headerNodes: List<Node>,
    footerNode: Node?,
    context: RenderContext,
    pageSettings: app.epistola.template.model.PageSettings,
    renderingDefaults: RenderingDefaults,
    pdfaCompliant: Boolean,
    fontFamilyResolver: FontFamilyResolver?,
): Map<String, Float> {
    val nodes = headerNodes + listOfNotNull(footerNode)
    val heights = measureBandHeights(
        document = document,
        requests = nodes.map { BandMeasureRequest(it, isHeader = it.type == "pageheader") },
        context = context,
        pageSettings = pageSettings,
        pdfaCompliant = pdfaCompliant,
        fontFamilyResolver = fontFamilyResolver,
    )
    return nodes.map { it.id }.zip(heights).toMap()
}

/** One header or footer to measure, with the data scope its content renders under. */
private class BandMeasureRequest(
    val node: Node,
    val isHeader: Boolean,
    val scope: (RenderContext) -> RenderContext = { it },
)

/**
 * Returns, per request, `max(configured height, content height)`. The measurement runs in
 * its own throwaway [PdfDocument] with its own [FontCache] — `FontCache` PdfFonts are bound
 * to a single document and must not leak into the real render. Does no work for no requests.
 */
private fun measureBandHeights(
    document: TemplateDocument,
    requests: List<BandMeasureRequest>,
    context: RenderContext,
    pageSettings: app.epistola.template.model.PageSettings,
    pdfaCompliant: Boolean,
    fontFamilyResolver: FontFamilyResolver?,
): List<Float> {
    if (requests.isEmpty()) return emptyList()

    val measureContext = context.copy(fontCache = FontCache(pdfaCompliant, fontFamilyResolver))
    val renderingDefaults = context.renderingDefaults
    // The throwaway document is created before the try so it can be closed in the
    // finally; everything that could throw during setup is inside the try so a
    // failure there still releases the writer/streams.
    val pdfDocument = PdfDocument(PdfWriter(OutputStream.nullOutputStream()))
    try {
        val pageSize = getPageSize(pageSettings.format, pageSettings.orientation)
        val iTextDocument = Document(pdfDocument, pageSize)
        iTextDocument.setFont(measureContext.fontCache.regular)
        val registry = DirectPdfRenderer.createDefaultRegistry(pdfDocument)

        return requests.map { request ->
            val node = request.node
            val nodeContext = request.scope(measureContext)
            val defaultHeight = if (request.isHeader) renderingDefaults.pageHeaderHeight else renderingDefaults.pageFooterHeight
            val configured = parseNodeHeight(node, nodeContext)
            try {
                val left = effectivePageMarginPt(node, "marginLeft", nodeContext)
                val right = effectivePageMarginPt(node, "marginRight", nodeContext)
                val width = pageSize.width - left - right
                val wrapper = buildBandWrapper(
                    node = node,
                    document = document,
                    baseContext = nodeContext,
                    registry = registry,
                    consumedMarginKeys = if (request.isHeader) HEADER_CONSUMED_MARGINS else FOOTER_CONSUMED_MARGINS,
                    componentDefaultsKey = if (request.isHeader) HEADER_COMPONENT_KEY else FOOTER_COMPONENT_KEY,
                    pageNumber = 1,
                    totalPages = DirectPdfRenderer.FIRST_PASS_PAGE_TOTAL_PLACEHOLDER,
                )
                maxOf(configured ?: defaultHeight, measureBandContentHeight(wrapper, iTextDocument, width))
            } catch (e: Exception) {
                // Measurement must never make a previously-working render fail;
                // fall back to the configured/default height (the prior behaviour).
                log.warn("Failed to measure band height for node {} ({}); using configured height", node.id, node.type, e)
                configured ?: defaultHeight
            }
        }
    } finally {
        // Discard the throwaway document; give it a page first so close() doesn't
        // throw "Document has no pages" (we never added flushed content).
        if (pdfDocument.numberOfPages == 0) pdfDocument.addNewPage()
        pdfDocument.close()
    }
}

/**
 * Returns the `pageheader` nodes ordered by their position as children of
 * the root slot. The order is the positional selector for which header
 * applies to which page (index 0 → page 1; index 1 → page 2 and onward when
 * present). Document-order is also what the editor surfaces, so authors
 * control the mapping by reordering the nodes.
 *
 * The same invariants enforced by `PageHeaderCardinalityValidator` (server-
 * side, on `UpdateDraft`) are re-asserted here so render paths that don't
 * pass through that command — `PreviewDocument`, `PreviewVariant`, catalog
 * import, future entrypoints — can't silently render with undefined header
 * positioning when the document is malformed.
 */
private fun pageHeaderNodesInDocumentOrder(document: TemplateDocument): List<Node> {
    val allHeaderIds = document.nodes.values
        .asSequence()
        .filter { it.type == "pageheader" }
        .map { it.id }
        .toSet()
    if (allHeaderIds.isEmpty()) return emptyList()

    val rootNode = document.nodes[document.root]
        ?: throw IllegalArgumentException(
            "Cannot render: document declares pageheader nodes but has no resolvable root node",
        )

    val orderedFromRootSlot = rootNode.slots
        .asSequence()
        .mapNotNull { document.slots[it] }
        .flatMap { it.children.asSequence() }
        .mapNotNull { document.nodes[it] }
        .filter { it.type == "pageheader" }
        .toList()

    val rootSlotHeaderIds = orderedFromRootSlot.map { it.id }.toSet()
    val misplaced = allHeaderIds - rootSlotHeaderIds
    if (misplaced.isNotEmpty()) {
        throw IllegalArgumentException(
            "Cannot render: pageheader node(s) $misplaced must be direct children of the root slot",
        )
    }
    if (orderedFromRootSlot.size > 2) {
        throw IllegalArgumentException(
            "Cannot render: at most 2 pageheader nodes allowed, found ${orderedFromRootSlot.size}",
        )
    }
    return orderedFromRootSlot
}
