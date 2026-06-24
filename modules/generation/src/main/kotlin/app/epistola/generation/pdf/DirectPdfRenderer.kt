package app.epistola.generation.pdf

import app.epistola.generation.ProseMirrorConverter
import app.epistola.generation.RenderCulture
import app.epistola.generation.SystemParameterRegistry
import app.epistola.generation.expression.CompositeExpressionEvaluator
import app.epistola.template.model.DocumentStyles
import app.epistola.template.model.ExpressionLanguage
import app.epistola.template.model.Node
import app.epistola.template.model.Orientation
import app.epistola.template.model.PageFormat
import app.epistola.template.model.TemplateDocument
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PageLabelNumberingStyle
import com.itextpdf.kernel.pdf.PdfAConformance
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfOutline
import com.itextpdf.kernel.pdf.PdfOutputIntent
import com.itextpdf.kernel.pdf.PdfString
import com.itextpdf.kernel.pdf.PdfViewerPreferences
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.event.PdfDocumentEvent
import com.itextpdf.kernel.pdf.navigation.PdfNamedDestination
import com.itextpdf.kernel.xmp.XMPMetaFactory
import com.itextpdf.layout.Document
import com.itextpdf.pdfa.PdfADocument
import org.slf4j.LoggerFactory
import java.io.OutputStream
import java.time.Clock

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
    private val defaultExpressionLanguage: ExpressionLanguage = ExpressionLanguage.jsonata,
    /**
     * Pluggable schema lookup for parametrised nodes (today: stencils). Threaded
     * onto [RenderContext.parameterSchemaProvider] so [StencilNodeRenderer] can
     * push parameter scope without knowing where the schema comes from. Default
     * returns null (no parameters) — production wiring binds a real provider.
     */
    private val parameterSchemaProvider: (Node, TemplateDocument) -> Map<String, Any?>? = { _, _ -> null },
) {

    /**
     * Renders a template document to PDF and writes directly to the output stream.
     *
     * When [pdfaCompliant] is true, produces PDF/A-2b with embedded fonts and ICC profile.
     * When false (default), produces standard PDF with non-embedded Helvetica fonts.
     *
     * Page settings, document styles and block-style presets follow the cascade:
     * template-level overrides on [document] take precedence, then the
     * [resolvedTheme] bundle, then the engine defaults from [renderingDefaults].
     * For `pageSettings.margins` the cascade is walked per side (a layer with
     * a null side falls through to the next layer).
     *
     * @param document The template document containing the node/slot graph,
     *   including any `pageSettingsOverride` / `documentStylesOverride`.
     * @param data The data context for expression evaluation
     * @param outputStream The output stream to write the PDF to
     * @param resolvedTheme Theme-derived bundle (documentStyles, pageSettings,
     *   blockStylePresets, spacingUnit). Defaults to an empty bundle, which
     *   falls through to engine defaults for everything.
     * @param metadata Optional document metadata (title, author, etc.)
     * @param pdfaCompliant Whether to produce PDF/A-2b compliant output (default: false)
     * @param assetResolver Optional resolver for image/SVG assets referenced
     *   from the document.
     * @param fontFamilyResolver Optional resolver for font families referenced
     *   from theme/template/block styles. Null falls back to the built-in font.
     * @param renderingDefaults Engine defaults that supply the final fallback
     *   for page settings, document styles, font sizes and component defaults.
     * @param renderMode STRICT to fail on missing resources; tolerant modes
     *   degrade gracefully — see [RenderMode].
     */
    fun render(
        document: TemplateDocument,
        data: Map<String, Any?>,
        outputStream: OutputStream,
        resolvedTheme: ResolvedTheme = ResolvedTheme(),
        metadata: PdfMetadata = PdfMetadata(),
        pdfaCompliant: Boolean = false,
        assetResolver: AssetResolver? = null,
        fontFamilyResolver: FontFamilyResolver? = null,
        renderingDefaults: RenderingDefaults = RenderingDefaults.CURRENT,
        renderMode: RenderMode = RenderMode.STRICT,
        culture: RenderCulture = RenderCulture.DEFAULT,
        clock: Clock = Clock.systemUTC(),
    ) {
        TwoPassAnalyzer.validate(document)

        // Build a render-scoped evaluator chain bound to the effective culture.
        // `forCulture` is a no-op when `culture == RenderCulture.DEFAULT`, so the
        // untouched default path costs nothing — we only allocate when a
        // tenant/variant has actually opted into a different locale.
        val scopedEvaluator = expressionEvaluator.forCulture(culture)

        if (TwoPassAnalyzer.requiresTwoPassRendering(document)) {
            val headerNodes = pageHeaderNodesInDocumentOrder(document)
            val footerNode = document.nodes.values.firstOrNull { it.type == "pagefooter" }
            renderTwoPass(
                document = document,
                data = data,
                outputStream = outputStream,
                resolvedTheme = resolvedTheme,
                metadata = metadata,
                pdfaCompliant = pdfaCompliant,
                assetResolver = assetResolver,
                fontFamilyResolver = fontFamilyResolver,
                renderingDefaults = renderingDefaults,
                renderMode = renderMode,
                headerNodes = headerNodes,
                footerNode = footerNode,
                scopedEvaluator = scopedEvaluator,
                clock = clock,
            )
        } else {
            renderSinglePass(
                document = document,
                data = data,
                outputStream = outputStream,
                resolvedTheme = resolvedTheme,
                metadata = metadata,
                pdfaCompliant = pdfaCompliant,
                assetResolver = assetResolver,
                fontFamilyResolver = fontFamilyResolver,
                renderingDefaults = renderingDefaults,
                renderMode = renderMode,
                scopedEvaluator = scopedEvaluator,
                clock = clock,
            )
        }
    }

    private fun createRenderContext(
        data: Map<String, Any?>,
        effectiveDocumentStyles: DocumentStyles,
        document: TemplateDocument,
        resolvedTheme: ResolvedTheme,
        pdfaCompliant: Boolean,
        assetResolver: AssetResolver?,
        fontFamilyResolver: FontFamilyResolver?,
        renderingDefaults: RenderingDefaults,
        renderMode: RenderMode,
        scopedEvaluator: CompositeExpressionEvaluator = expressionEvaluator,
        clock: Clock = Clock.systemUTC(),
    ): RenderContext {
        val fontCache = FontCache(pdfaCompliant, fontFamilyResolver)
        val proseMirrorConverter = ProseMirrorConverter(scopedEvaluator, defaultExpressionLanguage, renderingDefaults)
        return RenderContext(
            data = data,
            loopContext = emptyMap(),
            documentStyles = effectiveDocumentStyles,
            expressionEvaluator = scopedEvaluator,
            proseMirrorConverter = proseMirrorConverter,
            defaultExpressionLanguage = defaultExpressionLanguage,
            fontCache = fontCache,
            blockStylePresets = resolvedTheme.blockStylePresets,
            document = document,
            assetResolver = assetResolver,
            renderMode = renderMode,
            renderingDefaults = renderingDefaults,
            spacingUnit = resolvedTheme.spacingUnit,
            systemParams = SystemParameterRegistry.buildGlobalParams(clock),
            resolvedPageSettings = resolvedTheme.pageSettings,
            parameterSchemaProvider = parameterSchemaProvider,
        )
    }

    private fun renderSinglePass(
        document: TemplateDocument,
        data: Map<String, Any?>,
        outputStream: OutputStream,
        resolvedTheme: ResolvedTheme,
        metadata: PdfMetadata,
        pdfaCompliant: Boolean,
        assetResolver: AssetResolver?,
        fontFamilyResolver: FontFamilyResolver?,
        renderingDefaults: RenderingDefaults,
        renderMode: RenderMode,
        scopedEvaluator: CompositeExpressionEvaluator = expressionEvaluator,
        clock: Clock = Clock.systemUTC(),
    ) {
        // pageSettings cascade: template override > theme-resolved > engine defaults.
        val pageSettings = document.pageSettingsOverride
            ?: resolvedTheme.pageSettings
            ?: renderingDefaults.defaultPageSettings
        // documentStyles cascade: theme is the base; template override wins per key.
        val effectiveDocumentStyles: DocumentStyles =
            (resolvedTheme.documentStyles ?: emptyMap()) +
                (document.documentStylesOverride ?: emptyMap())
        val context = createRenderContext(
            data = data,
            effectiveDocumentStyles = effectiveDocumentStyles,
            document = document,
            resolvedTheme = resolvedTheme,
            pdfaCompliant = pdfaCompliant,
            assetResolver = assetResolver,
            fontFamilyResolver = fontFamilyResolver,
            renderingDefaults = renderingDefaults,
            renderMode = renderMode,
            scopedEvaluator = scopedEvaluator,
            clock = clock,
        )

        val headerNodes = pageHeaderNodesInDocumentOrder(document)
        val footerNode = document.nodes.values.firstOrNull { it.type == "pagefooter" }

        // Reserve `max(configured, content)` for each header/footer so tall content
        // is never clipped; these heights drive both the body margins and the
        // rectangles the event handlers draw.
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

        val footerHeight = footerNode?.let {
            effectiveHeights[it.id] ?: parseNodeHeight(it, context) ?: renderingDefaults.pageFooterHeight
        } ?: 0f

        // The body's page-edge margins follow the same cascade as headers/footers:
        // header/footer.margin{Side} → root.margin{Side} → pageSettings.margins.
        // Each page's body must sit below its own pageheader band: page 1 below
        // the first-page (index 0) header, pages 2+ below the running (index 1)
        // header. iText's Document margins are document-scoped, so we set the
        // *running* band as the body topMargin and prepend a spacer Div on page 1
        // sized to the extra first-page band height. See computeHeaderBands.
        val footerBottomMargin = effectivePageMarginPt(footerNode, "marginBottom", context)
        val bodyLeftMargin = effectivePageMarginPt(null, "marginLeft", context)
        val bodyRightMargin = effectivePageMarginPt(null, "marginRight", context)

        val bands = computeHeaderBands(headerNodes, context, renderingDefaults, effectiveHeights)
        val topMargin = bands.runningBand
        val bottomMargin = if (footerNode != null) {
            footerBottomMargin + footerHeight
        } else {
            effectivePageMarginPt(null, "marginBottom", context)
        }

        performRenderWithContext(
            outputStream = outputStream,
            context = context,
            headerNodes = headerNodes,
            footerNode = footerNode,
            document = document,
            metadata = metadata,
            pdfaCompliant = pdfaCompliant,
            pageSettings = pageSettings,
            topMargin = topMargin,
            bottomMargin = bottomMargin,
            rightMargin = bodyRightMargin,
            leftMargin = bodyLeftMargin,
            firstPageSpacerHeight = bands.firstPageSpacer,
            effectiveHeights = effectiveHeights,
        )
    }

    private fun renderTwoPass(
        document: TemplateDocument,
        data: Map<String, Any?>,
        outputStream: OutputStream,
        resolvedTheme: ResolvedTheme,
        metadata: PdfMetadata,
        pdfaCompliant: Boolean,
        assetResolver: AssetResolver?,
        fontFamilyResolver: FontFamilyResolver?,
        renderingDefaults: RenderingDefaults,
        renderMode: RenderMode,
        headerNodes: List<Node>,
        footerNode: Node?,
        scopedEvaluator: CompositeExpressionEvaluator = expressionEvaluator,
        clock: Clock = Clock.systemUTC(),
    ) {
        // pageSettings cascade: template override > theme-resolved > engine defaults.
        val pageSettings = document.pageSettingsOverride
            ?: resolvedTheme.pageSettings
            ?: renderingDefaults.defaultPageSettings
        // documentStyles cascade: theme is the base; template override wins per key.
        val effectiveDocumentStyles: DocumentStyles =
            (resolvedTheme.documentStyles ?: emptyMap()) +
                (document.documentStylesOverride ?: emptyMap())

        val heightContext = createRenderContext(
            data = data,
            effectiveDocumentStyles = effectiveDocumentStyles,
            document = document,
            resolvedTheme = resolvedTheme,
            pdfaCompliant = pdfaCompliant,
            assetResolver = assetResolver,
            fontFamilyResolver = fontFamilyResolver,
            renderingDefaults = renderingDefaults,
            renderMode = renderMode,
            scopedEvaluator = scopedEvaluator,
            clock = clock,
        )

        // Reserve `max(configured, content)` for each header/footer so tall content
        // is never clipped; these heights drive both the body margins and the
        // rectangles the event handlers draw.
        val effectiveHeights = measureEffectiveBandHeights(
            document,
            headerNodes,
            footerNode,
            heightContext,
            pageSettings,
            renderingDefaults,
            pdfaCompliant,
            fontFamilyResolver,
        )

        val footerHeight = footerNode?.let {
            effectiveHeights[it.id] ?: parseNodeHeight(it, heightContext) ?: renderingDefaults.pageFooterHeight
        } ?: 0f

        // Body page-edge margins: header/footer.margin → root.margin → pageMargins cascade.
        // Per-page topMargin: body sits below the running (index 1) band; page 1
        // gets a spacer Div sized for the extra first-page (index 0) header height.
        val footerBottomMargin = effectivePageMarginPt(footerNode, "marginBottom", heightContext)
        val bodyLeftMargin = effectivePageMarginPt(null, "marginLeft", heightContext)
        val bodyRightMargin = effectivePageMarginPt(null, "marginRight", heightContext)
        val bands = computeHeaderBands(headerNodes, heightContext, renderingDefaults, effectiveHeights)
        val topMargin = bands.runningBand
        val firstPageSpacerHeight = bands.firstPageSpacer
        val bottomMargin = if (footerNode != null) {
            footerBottomMargin + footerHeight
        } else {
            effectivePageMarginPt(null, "marginBottom", heightContext)
        }

        // First pass: render to count total pages (bytes are discarded).
        // Use a 2-digit placeholder (99) so body expressions reserve enough
        // character width and the layout stays stable for documents up to 99 pages.
        val tempOutput = OutputStream.nullOutputStream()
        val firstPassContext = createRenderContext(
            data = data,
            effectiveDocumentStyles = effectiveDocumentStyles,
            document = document,
            resolvedTheme = resolvedTheme,
            pdfaCompliant = pdfaCompliant,
            assetResolver = assetResolver,
            fontFamilyResolver = fontFamilyResolver,
            renderingDefaults = renderingDefaults,
            renderMode = renderMode,
            scopedEvaluator = scopedEvaluator,
            clock = clock,
        ).withTotalPages(FIRST_PASS_PAGE_TOTAL_PLACEHOLDER)
        val totalPages = performRenderWithContext(
            outputStream = tempOutput,
            context = firstPassContext,
            headerNodes = headerNodes,
            footerNode = footerNode,
            document = document,
            metadata = metadata,
            pdfaCompliant = pdfaCompliant,
            pageSettings = pageSettings,
            topMargin = topMargin,
            bottomMargin = bottomMargin,
            rightMargin = bodyRightMargin,
            leftMargin = bodyLeftMargin,
            firstPageSpacerHeight = firstPageSpacerHeight,
            effectiveHeights = effectiveHeights,
            enablePdfA = false,
            enableMetadata = false,
            enableHeaderFooter = false,
        )

        // Second pass: render with known total page count
        val finalContext = createRenderContext(
            data = data,
            effectiveDocumentStyles = effectiveDocumentStyles,
            document = document,
            resolvedTheme = resolvedTheme,
            pdfaCompliant = pdfaCompliant,
            assetResolver = assetResolver,
            fontFamilyResolver = fontFamilyResolver,
            renderingDefaults = renderingDefaults,
            renderMode = renderMode,
            scopedEvaluator = scopedEvaluator,
            clock = clock,
        ).withTotalPages(totalPages)

        performRenderWithContext(
            outputStream = outputStream,
            context = finalContext,
            headerNodes = headerNodes,
            footerNode = footerNode,
            document = document,
            metadata = metadata,
            pdfaCompliant = pdfaCompliant,
            pageSettings = pageSettings,
            topMargin = topMargin,
            bottomMargin = bottomMargin,
            rightMargin = bodyRightMargin,
            leftMargin = bodyLeftMargin,
            firstPageSpacerHeight = firstPageSpacerHeight,
            effectiveHeights = effectiveHeights,
        )
    }

    private fun performRenderWithContext(
        outputStream: OutputStream,
        context: RenderContext,
        headerNodes: List<Node>,
        footerNode: Node?,
        document: TemplateDocument,
        metadata: PdfMetadata,
        pdfaCompliant: Boolean,
        pageSettings: app.epistola.template.model.PageSettings,
        topMargin: Float,
        bottomMargin: Float,
        rightMargin: Float,
        leftMargin: Float,
        firstPageSpacerHeight: Float = 0f,
        effectiveHeights: Map<String, Float> = emptyMap(),
        enablePdfA: Boolean = pdfaCompliant,
        enableMetadata: Boolean = true,
        enableHeaderFooter: Boolean = true,
    ): Int {
        val writer = PdfWriter(outputStream)
        val pdfDocument = createPdfDocument(writer, enablePdfA)
        if (enableMetadata) {
            // Enable tagged PDF so screen readers get a structure tree
            // (WCAG PDF3/PDF9/PDF11/PDF21). Skipped on the discarded
            // first counting pass.
            pdfDocument.setTagged()
            applyMetadata(pdfDocument, metadata)
        }

        val nodeRendererRegistry = createDefaultRegistry(pdfDocument)

        val pageSize = getPageSize(pageSettings.format, pageSettings.orientation)
        val iTextDocument = Document(pdfDocument, pageSize)
        iTextDocument.setFont(context.fontCache.regular)
        iTextDocument.setMargins(topMargin, rightMargin, bottomMargin, leftMargin)

        if (enableHeaderFooter) {
            val headerHandler = if (headerNodes.isNotEmpty()) {
                PageHeaderEventHandler(
                    headerNodeIds = headerNodes.map { it.id },
                    document = document,
                    context = context,
                    registry = nodeRendererRegistry,
                    effectiveHeights = effectiveHeights,
                )
            } else {
                null
            }
            val footerHandler = footerNode?.let {
                PageFooterEventHandler(
                    footerNodeId = it.id,
                    document = document,
                    context = context,
                    registry = nodeRendererRegistry,
                    effectiveHeights = effectiveHeights,
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

        // First-page spacer: when the first-page pageheader band is taller than the
        // running header band, prepend an invisible Div sized to the extra height so
        // body content on page 1 lands below the cover header. From page 2 onward
        // the spacer is already consumed and content sits at the running topMargin.
        //
        // An empty Div collapses in iText's layout engine; using `setMinHeight` plus
        // an empty Paragraph (zero-leading) guarantees the layout reserves the
        // requested vertical space without painting anything visible.
        if (firstPageSpacerHeight > 0f) {
            val spacer = com.itextpdf.layout.element.Div()
                .setMinHeight(firstPageSpacerHeight)
                .setMargin(0f)
                .setPadding(0f)
                .add(com.itextpdf.layout.element.Paragraph("").setMargin(0f).setFixedLeading(0f))
            iTextDocument.add(spacer)
        }

        val elements = nodeRendererRegistry.renderNode(renderDocument.root, renderDocument, context)
        for (element in elements) {
            when (element) {
                is com.itextpdf.layout.element.IBlockElement -> iTextDocument.add(element)
                is com.itextpdf.layout.element.AreaBreak -> iTextDocument.add(element)
                is com.itextpdf.layout.element.Image -> iTextDocument.add(element)
            }
        }

        val totalPages = pdfDocument.numberOfPages
        if (enableMetadata) {
            // Consistent page numbering for assistive tech (WCAG PDF17)
            if (pdfDocument.numberOfPages > 0) {
                pdfDocument.getPage(1).setPageLabel(PageLabelNumberingStyle.DECIMAL_ARABIC_NUMERALS, null, 1)
            }
            // Document outline / bookmarks from collected headings (WCAG PDF2)
            buildOutline(pdfDocument, context.bookmarkCollector)
        }
        iTextDocument.close()
        return totalPages
    }

    /**
     * Builds a nested document outline from the headings collected during
     * rendering (WCAG PDF2). Outlines are nested by heading level and each
     * points to a named destination anchored at the heading, so bookmarks
     * navigate to the heading's actual page.
     */
    private fun buildOutline(pdfDocument: PdfDocument, bookmarks: List<BookmarkEntry>) {
        if (bookmarks.isEmpty()) return

        val root = pdfDocument.getOutlines(true)
        // Stack of (level, outline) tracking the current ancestor chain.
        val stack = ArrayDeque<Pair<Int, PdfOutline>>()
        for (bookmark in bookmarks) {
            while (stack.isNotEmpty() && stack.last().first >= bookmark.level) {
                stack.removeLast()
            }
            val parent = if (stack.isEmpty()) root else stack.last().second
            val outline = parent.addOutline(bookmark.title)
            outline.addDestination(PdfNamedDestination(bookmark.destinationName))
            stack.addLast(bookmark.level to outline)
        }
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

        // Document language for assistive technology (WCAG PDF16)
        pdfDocument.catalog.lang = PdfString(metadata.language)

        // Show the document title (not the filename) in the viewer title bar (WCAG PDF18)
        if (metadata.title != null) {
            pdfDocument.catalog.viewerPreferences = PdfViewerPreferences().setDisplayDocTitle(true)
        }

        // PDF/UA-1 identification in XMP metadata (ISO 14289-1 §5)
        val xmpMeta = XMPMetaFactory.create()
        xmpMeta.setPropertyInteger("http://www.aiim.org/pdfua/ns/id/", "pdfuaid:part", 1)
        pdfDocument.xmpMetadata = xmpMeta
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
        private val log = LoggerFactory.getLogger(DirectPdfRenderer::class.java)

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
         * The [pdfDocument] is used to wire iText-specific SVG conversion into the image renderer.
         */
        fun createDefaultRegistry(pdfDocument: PdfDocument): NodeRendererRegistry {
            val svgConverter = ImageNodeRenderer.SvgImageConverter { svgBytes ->
                java.io.ByteArrayInputStream(svgBytes).use { svgStream ->
                    com.itextpdf.svg.converter.SvgConverter.convertToImage(svgStream, pdfDocument)
                }
            }
            return NodeRendererRegistry(
                mapOf(
                    "root" to ContainerNodeRenderer(),
                    "text" to TextNodeRenderer(),
                    "richTextVariable" to RichTextVariableRenderer(),
                    "container" to ContainerNodeRenderer(),
                    StencilNodeKeys.NODE_TYPE to StencilNodeRenderer(),
                    PlaceholderNodeKeys.NODE_TYPE to PlaceholderNodeRenderer(),
                    "columns" to ColumnsNodeRenderer(),
                    "table" to TableNodeRenderer(),
                    "conditional" to ConditionalNodeRenderer(),
                    "loop" to LoopNodeRenderer(),
                    "datalist" to DataListNodeRenderer(),
                    "datatable" to DatatableNodeRenderer(),
                    "datatable-column" to DatatableColumnNodeRenderer(),
                    "image" to ImageNodeRenderer(svgConverter),
                    "qrcode" to QrCodeNodeRenderer(),
                    "separator" to SeparatorNodeRenderer(),
                    "pagebreak" to PageBreakNodeRenderer(),
                    "pageheader" to PageHeaderNodeRenderer(),
                    "pagefooter" to PageFooterNodeRenderer(),
                    "addressblock" to AddressBlockNodeRenderer(),
                ),
            )
        }
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
        if (headerNodes.isEmpty() && footerNode == null) return emptyMap()

        val measureContext = context.copy(fontCache = FontCache(pdfaCompliant, fontFamilyResolver))
        val pdfDocument = PdfDocument(PdfWriter(OutputStream.nullOutputStream()))
        val pageSize = getPageSize(pageSettings.format, pageSettings.orientation)
        val iTextDocument = Document(pdfDocument, pageSize)
        iTextDocument.setFont(measureContext.fontCache.regular)
        val registry = createDefaultRegistry(pdfDocument)

        fun measureOne(
            node: Node,
            consumedMarginKeys: Set<String>,
            componentDefaultsKey: String,
            defaultHeight: Float,
        ): Float {
            val configured = parseNodeHeight(node, measureContext)
            return try {
                val left = effectivePageMarginPt(node, "marginLeft", measureContext)
                val right = effectivePageMarginPt(node, "marginRight", measureContext)
                val width = pageSize.width - left - right
                val wrapper = buildBandWrapper(
                    node = node,
                    document = document,
                    baseContext = measureContext,
                    registry = registry,
                    consumedMarginKeys = consumedMarginKeys,
                    componentDefaultsKey = componentDefaultsKey,
                    pageNumber = 1,
                    totalPages = FIRST_PASS_PAGE_TOTAL_PLACEHOLDER,
                )
                maxOf(configured ?: defaultHeight, measureBandContentHeight(wrapper, iTextDocument, width))
            } catch (e: Exception) {
                // Measurement must never make a previously-working render fail;
                // fall back to the configured/default height (the prior behaviour).
                log.warn("Failed to measure band height for node {} ({}); using configured height", node.id, node.type, e)
                configured ?: defaultHeight
            }
        }

        val result = mutableMapOf<String, Float>()
        try {
            for (node in headerNodes) {
                result[node.id] = measureOne(node, HEADER_CONSUMED_MARGINS, HEADER_COMPONENT_KEY, renderingDefaults.pageHeaderHeight)
            }
            footerNode?.let { node ->
                result[node.id] = measureOne(node, FOOTER_CONSUMED_MARGINS, FOOTER_COMPONENT_KEY, renderingDefaults.pageFooterHeight)
            }
        } finally {
            // The measurement document holds no flushed content; give it a page so
            // close() doesn't throw "Document has no pages", then discard it.
            if (pdfDocument.numberOfPages == 0) pdfDocument.addNewPage()
            iTextDocument.close()
        }
        return result
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
}
