package app.epistola.generation.pdf

import app.epistola.generation.SystemParameterRegistry
import app.epistola.generation.TipTapConverter
import app.epistola.generation.expression.CompositeExpressionEvaluator
import app.epistola.template.model.DocumentStyles
import app.epistola.template.model.ExpressionLanguage
import app.epistola.template.model.Node
import app.epistola.template.model.Orientation
import app.epistola.template.model.PageFormat
import app.epistola.template.model.TemplateDocument
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfAConformance
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfOutputIntent
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.event.PdfDocumentEvent
import com.itextpdf.layout.Document
import com.itextpdf.pdfa.PdfADocument
import java.io.OutputStream

/**
 * Main PDF renderer that orchestrates node rendering and outputs to a stream.
 * Uses iText Core for direct PDF generation without an intermediate HTML step.
 *
 * When [pdfaCompliant] is true, output conforms to PDF/A-2b (ISO 19005-2, Level B)
 * for long-term archival compliance with embedded fonts and sRGB output intent.
 * When false (default), produces standard PDF with non-embedded Helvetica fonts
 * for smaller, faster output.
 *
 * Accepts the v2 [TemplateDocument] (normalized node/slot graph) and traverses
 * the graph starting from the root node through its slots and children.
 */
class DirectPdfRenderer(
    private val expressionEvaluator: CompositeExpressionEvaluator = CompositeExpressionEvaluator(),
    private val nodeRendererRegistry: NodeRendererRegistry = createDefaultRegistry(),
    private val defaultExpressionLanguage: ExpressionLanguage = ExpressionLanguage.jsonata,
) {

    /**
     * Renders a template document to PDF and writes directly to the output stream.
     *
     * When [pdfaCompliant] is true, produces PDF/A-2b with embedded fonts and ICC profile.
     * When false (default), produces standard PDF with non-embedded Helvetica fonts.
     *
     * @param document The template document containing the node/slot graph
     * @param data The data context for expression evaluation
     * @param outputStream The output stream to write the PDF to
     * @param blockStylePresets Optional block style presets from theme (named style collections like CSS classes)
     * @param resolvedDocumentStyles Optional pre-resolved document styles (merging theme + template styles)
     * @param metadata Optional document metadata (title, author, etc.)
     * @param pdfaCompliant Whether to produce PDF/A-2b compliant output (default: false)
     */
    fun render(
        document: TemplateDocument,
        data: Map<String, Any?>,
        outputStream: OutputStream,
        blockStylePresets: Map<String, Map<String, Any>> = emptyMap(),
        resolvedDocumentStyles: DocumentStyles? = null,
        metadata: PdfMetadata = PdfMetadata(),
        pdfaCompliant: Boolean = false,
        assetResolver: AssetResolver? = null,
        renderingDefaults: RenderingDefaults = RenderingDefaults.CURRENT,
        spacingUnit: Float = SpacingScale.DEFAULT_BASE_UNIT,
    ) {
        TwoPassAnalyzer.validate(document)

        if (TwoPassAnalyzer.requiresTwoPassRendering(document)) {
            val headerNode = document.nodes.values.firstOrNull { it.type == "pageheader" }
            val footerNode = document.nodes.values.firstOrNull { it.type == "pagefooter" }
            renderTwoPass(
                document = document,
                data = data,
                outputStream = outputStream,
                blockStylePresets = blockStylePresets,
                resolvedDocumentStyles = resolvedDocumentStyles,
                metadata = metadata,
                pdfaCompliant = pdfaCompliant,
                assetResolver = assetResolver,
                renderingDefaults = renderingDefaults,
                spacingUnit = spacingUnit,
                headerNode = headerNode,
                footerNode = footerNode,
            )
        } else {
            renderSinglePass(
                document = document,
                data = data,
                outputStream = outputStream,
                blockStylePresets = blockStylePresets,
                resolvedDocumentStyles = resolvedDocumentStyles,
                metadata = metadata,
                pdfaCompliant = pdfaCompliant,
                assetResolver = assetResolver,
                renderingDefaults = renderingDefaults,
                spacingUnit = spacingUnit,
            )
        }
    }

    private fun createRenderContext(
        data: Map<String, Any?>,
        effectiveDocumentStyles: DocumentStyles,
        document: TemplateDocument,
        blockStylePresets: Map<String, Map<String, Any>>,
        pdfaCompliant: Boolean,
        assetResolver: AssetResolver?,
        renderingDefaults: RenderingDefaults,
        spacingUnit: Float,
    ): RenderContext {
        val fontCache = FontCache(pdfaCompliant)
        val tipTapConverter = TipTapConverter(expressionEvaluator, defaultExpressionLanguage, renderingDefaults)
        return RenderContext(
            data = data,
            loopContext = emptyMap(),
            documentStyles = effectiveDocumentStyles,
            expressionEvaluator = expressionEvaluator,
            tipTapConverter = tipTapConverter,
            defaultExpressionLanguage = defaultExpressionLanguage,
            fontCache = fontCache,
            blockStylePresets = blockStylePresets,
            document = document,
            assetResolver = assetResolver,
            renderingDefaults = renderingDefaults,
            spacingUnit = spacingUnit,
            systemParams = SystemParameterRegistry.buildGlobalParams(),
        )
    }

    private fun renderSinglePass(
        document: TemplateDocument,
        data: Map<String, Any?>,
        outputStream: OutputStream,
        blockStylePresets: Map<String, Map<String, Any>>,
        resolvedDocumentStyles: DocumentStyles?,
        metadata: PdfMetadata,
        pdfaCompliant: Boolean,
        assetResolver: AssetResolver?,
        renderingDefaults: RenderingDefaults,
        spacingUnit: Float,
    ) {
        val pageSettings = document.pageSettingsOverride ?: renderingDefaults.defaultPageSettings
        val margins = pageSettings.margins
        val effectiveDocumentStyles = resolvedDocumentStyles
            ?: document.documentStylesOverride
            ?: emptyMap()
        val context = createRenderContext(
            data = data,
            effectiveDocumentStyles = effectiveDocumentStyles,
            document = document,
            blockStylePresets = blockStylePresets,
            pdfaCompliant = pdfaCompliant,
            assetResolver = assetResolver,
            renderingDefaults = renderingDefaults,
            spacingUnit = spacingUnit,
        )

        val headerNode = document.nodes.values.firstOrNull { it.type == "pageheader" }
        val footerNode = document.nodes.values.firstOrNull { it.type == "pagefooter" }

        val headerHeight = headerNode?.let {
            parseNodeHeight(it, context) ?: renderingDefaults.pageHeaderHeight
        } ?: 0f
        val footerHeight = footerNode?.let {
            parseNodeHeight(it, context) ?: renderingDefaults.pageFooterHeight
        } ?: 0f

        val mmToPt = 2.834645f
        val topMargin = margins.top.toFloat() * mmToPt +
            if (headerNode != null) renderingDefaults.pageHeaderPadding + headerHeight else 0f
        val bottomMargin = margins.bottom.toFloat() * mmToPt +
            if (footerNode != null) renderingDefaults.pageFooterPadding + footerHeight else 0f

        performRenderWithContext(
            outputStream = outputStream,
            context = context,
            headerNode = headerNode,
            footerNode = footerNode,
            document = document,
            metadata = metadata,
            pdfaCompliant = pdfaCompliant,
            pageSettings = pageSettings,
            topMargin = topMargin,
            bottomMargin = bottomMargin,
            rightMargin = margins.right.toFloat() * mmToPt,
            leftMargin = margins.left.toFloat() * mmToPt,
        )
    }

    private fun renderTwoPass(
        document: TemplateDocument,
        data: Map<String, Any?>,
        outputStream: OutputStream,
        blockStylePresets: Map<String, Map<String, Any>>,
        resolvedDocumentStyles: DocumentStyles?,
        metadata: PdfMetadata,
        pdfaCompliant: Boolean,
        assetResolver: AssetResolver?,
        renderingDefaults: RenderingDefaults,
        spacingUnit: Float,
        headerNode: Node?,
        footerNode: Node?,
    ) {
        val pageSettings = document.pageSettingsOverride ?: renderingDefaults.defaultPageSettings
        val margins = pageSettings.margins
        val effectiveDocumentStyles = resolvedDocumentStyles
            ?: document.documentStylesOverride
            ?: emptyMap()

        val heightContext = createRenderContext(
            data = data,
            effectiveDocumentStyles = effectiveDocumentStyles,
            document = document,
            blockStylePresets = blockStylePresets,
            pdfaCompliant = pdfaCompliant,
            assetResolver = assetResolver,
            renderingDefaults = renderingDefaults,
            spacingUnit = spacingUnit,
        )

        val headerHeight = headerNode?.let {
            parseNodeHeight(it, heightContext) ?: renderingDefaults.pageHeaderHeight
        } ?: 0f
        val footerHeight = footerNode?.let {
            parseNodeHeight(it, heightContext) ?: renderingDefaults.pageFooterHeight
        } ?: 0f

        val mmToPt = 2.834645f
        val topMargin = margins.top.toFloat() * mmToPt +
            if (headerNode != null) renderingDefaults.pageHeaderPadding + headerHeight else 0f
        val bottomMargin = margins.bottom.toFloat() * mmToPt +
            if (footerNode != null) renderingDefaults.pageFooterPadding + footerHeight else 0f

        // First pass: render to count total pages (bytes are discarded).
        // Use a 2-digit placeholder (99) so body expressions reserve enough
        // character width and the layout stays stable for documents up to 99 pages.
        val tempOutput = OutputStream.nullOutputStream()
        val firstPassContext = createRenderContext(
            data = data,
            effectiveDocumentStyles = effectiveDocumentStyles,
            document = document,
            blockStylePresets = blockStylePresets,
            pdfaCompliant = pdfaCompliant,
            assetResolver = assetResolver,
            renderingDefaults = renderingDefaults,
            spacingUnit = spacingUnit,
        ).withTotalPages(FIRST_PASS_PAGE_TOTAL_PLACEHOLDER)
        val totalPages = performRenderWithContext(
            outputStream = tempOutput,
            context = firstPassContext,
            headerNode = headerNode,
            footerNode = footerNode,
            document = document,
            metadata = metadata,
            pdfaCompliant = pdfaCompliant,
            pageSettings = pageSettings,
            topMargin = topMargin,
            bottomMargin = bottomMargin,
            rightMargin = margins.right.toFloat() * mmToPt,
            leftMargin = margins.left.toFloat() * mmToPt,
            enablePdfA = false,
            enableMetadata = false,
            enableHeaderFooter = false,
        )

        // Second pass: render with known total page count
        val finalContext = createRenderContext(
            data = data,
            effectiveDocumentStyles = effectiveDocumentStyles,
            document = document,
            blockStylePresets = blockStylePresets,
            pdfaCompliant = pdfaCompliant,
            assetResolver = assetResolver,
            renderingDefaults = renderingDefaults,
            spacingUnit = spacingUnit,
        ).withTotalPages(totalPages)

        performRenderWithContext(
            outputStream = outputStream,
            context = finalContext,
            headerNode = headerNode,
            footerNode = footerNode,
            document = document,
            metadata = metadata,
            pdfaCompliant = pdfaCompliant,
            pageSettings = pageSettings,
            topMargin = topMargin,
            bottomMargin = bottomMargin,
            rightMargin = margins.right.toFloat() * mmToPt,
            leftMargin = margins.left.toFloat() * mmToPt,
        )
    }

    private fun performRenderWithContext(
        outputStream: OutputStream,
        context: RenderContext,
        headerNode: Node?,
        footerNode: Node?,
        document: TemplateDocument,
        metadata: PdfMetadata,
        pdfaCompliant: Boolean,
        pageSettings: app.epistola.template.model.PageSettings,
        topMargin: Float,
        bottomMargin: Float,
        rightMargin: Float,
        leftMargin: Float,
        enablePdfA: Boolean = pdfaCompliant,
        enableMetadata: Boolean = true,
        enableHeaderFooter: Boolean = true,
    ): Int {
        val writer = PdfWriter(outputStream)
        val pdfDocument = createPdfDocument(writer, enablePdfA)
        if (enableMetadata) applyMetadata(pdfDocument, metadata)

        val pageSize = getPageSize(pageSettings.format, pageSettings.orientation)
        val iTextDocument = Document(pdfDocument, pageSize)
        iTextDocument.setFont(context.fontCache.regular)
        iTextDocument.setMargins(topMargin, rightMargin, bottomMargin, leftMargin)

        if (enableHeaderFooter) {
            val headerHandler = headerNode?.let {
                PageHeaderEventHandler(
                    headerNodeId = it.id,
                    document = document,
                    context = context,
                    registry = nodeRendererRegistry,
                )
            }
            val footerHandler = footerNode?.let {
                PageFooterEventHandler(
                    footerNodeId = it.id,
                    document = document,
                    context = context,
                    registry = nodeRendererRegistry,
                )
            }

            headerHandler?.let { pdfDocument.addEventHandler(PdfDocumentEvent.END_PAGE, it) }
            footerHandler?.let { pdfDocument.addEventHandler(PdfDocumentEvent.END_PAGE, it) }
        }

        // Address block: aside rendered in flow (hoisted to first child of root),
        // address content rendered at absolute coordinates via event handler.
        val renderDocument = hoistAddressBlock(document)
        val addressNode = renderDocument.nodes.values.firstOrNull { it.type == "addressblock" }
        addressNode?.let {
            pdfDocument.addEventHandler(
                PdfDocumentEvent.END_PAGE,
                AddressBlockEventHandler(it.id, renderDocument, context, nodeRendererRegistry),
            )
        }

        val documentContext = context.copy(pdfDocument = pdfDocument)
        val elements = nodeRendererRegistry.renderNode(renderDocument.root, renderDocument, documentContext)
        for (element in elements) {
            when (element) {
                is com.itextpdf.layout.element.IBlockElement -> iTextDocument.add(element)
                is com.itextpdf.layout.element.AreaBreak -> iTextDocument.add(element)
                is com.itextpdf.layout.element.Image -> iTextDocument.add(element)
            }
        }

        val totalPages = pdfDocument.numberOfPages
        iTextDocument.close()
        return totalPages
    }

    private fun createPdfDocument(writer: PdfWriter, pdfaCompliant: Boolean): PdfDocument = if (pdfaCompliant) {
        val outputIntent = createSrgbOutputIntent()
        PdfADocument(writer, PdfAConformance.PDF_A_2B, outputIntent)
    } else {
        PdfDocument(writer)
    }

    private fun createSrgbOutputIntent(): PdfOutputIntent {
        val iccStream = DirectPdfRenderer::class.java.getResourceAsStream(ICC_PROFILE_PATH)
            ?: throw IllegalStateException("sRGB ICC profile not found: $ICC_PROFILE_PATH")
        return PdfOutputIntent(
            "Custom",
            "",
            "http://www.color.org",
            "sRGB IEC61966-2.1",
            iccStream,
        )
    }

    private fun applyMetadata(pdfDocument: PdfDocument, metadata: PdfMetadata) {
        val info = pdfDocument.documentInfo
        metadata.title?.let { info.setTitle(it) }
        metadata.author?.let { info.setAuthor(it) }
        metadata.subject?.let { info.setSubject(it) }
        info.setCreator(metadata.creator)
        metadata.engineVersion?.let { info.setMoreInfo("EngineVersion", it) }
    }

    private fun getPageSize(format: PageFormat, orientation: Orientation): PageSize {
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

    companion object {
        private const val ICC_PROFILE_PATH = "/color/sRGB.icc"

        /**
         * Placeholder page total used during the first (counting) pass.
         * A 2-digit value reserves enough character width in body expressions
         * so that the actual total (up to 99 pages) never widens the text and
         * destabilizes the page count between passes.
         */
        internal const val FIRST_PASS_PAGE_TOTAL_PLACEHOLDER = 99

        /**
         * Creates the default node renderer registry with all built-in renderers.
         */
        fun createDefaultRegistry(): NodeRendererRegistry = NodeRendererRegistry(
            mapOf(
                "root" to ContainerNodeRenderer(),
                "text" to TextNodeRenderer(),
                "container" to ContainerNodeRenderer(),
                "stencil" to ContainerNodeRenderer(),
                "columns" to ColumnsNodeRenderer(),
                "table" to TableNodeRenderer(),
                "conditional" to ConditionalNodeRenderer(),
                "loop" to LoopNodeRenderer(),
                "datalist" to DataListNodeRenderer(),
                "datatable" to DatatableNodeRenderer(),
                "datatable-column" to DatatableColumnNodeRenderer(),
                "image" to ImageNodeRenderer(),
                "qrcode" to QrCodeNodeRenderer(),
                "separator" to SeparatorNodeRenderer(),
                "pagebreak" to PageBreakNodeRenderer(),
                "pageheader" to PageHeaderNodeRenderer(),
                "pagefooter" to PageFooterNodeRenderer(),
                "addressblock" to AddressBlockNodeRenderer(),
            ),
        )
    }

    /**
     * If the document contains an address block nested somewhere in the tree,
     * move it to be the first child of the root slot. This ensures it renders
     * on page 1 before any other content.
     *
     * Returns the original document if no address block exists or if it's
     * already the first child of root.
     */
    private fun hoistAddressBlock(document: TemplateDocument): TemplateDocument {
        val addressNode = document.nodes.values.firstOrNull { it.type == "addressblock" }
            ?: return document

        val rootNode = document.nodes[document.root] ?: return document
        val rootSlotId = rootNode.slots.firstOrNull() ?: return document
        val rootSlot = document.slots[rootSlotId] ?: return document

        // Already first child of root?
        if (rootSlot.children.firstOrNull() == addressNode.id) return document

        // Find the slot that currently contains the address block and remove it
        val mutableSlots = document.slots.toMutableMap()
        for ((slotId, slot) in document.slots) {
            if (addressNode.id in slot.children) {
                mutableSlots[slotId] = slot.copy(children = slot.children.filter { it != addressNode.id })
                break
            }
        }

        // Insert as first child of root slot
        val updatedRootSlot = mutableSlots[rootSlotId]!!
        mutableSlots[rootSlotId] = updatedRootSlot.copy(
            children = listOf(addressNode.id) + updatedRootSlot.children,
        )

        return document.copy(slots = mutableSlots)
    }
}
