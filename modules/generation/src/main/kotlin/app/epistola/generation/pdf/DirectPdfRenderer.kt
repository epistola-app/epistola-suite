// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

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
        watermarkText: String? = null,
    ) {
        TwoPassAnalyzer.validate(document)

        // Build a render-scoped evaluator chain bound to the effective culture.
        // `forCulture` is a no-op when `culture == RenderCulture.DEFAULT`, so the
        // untouched default path costs nothing — we only allocate when a
        // tenant/variant has actually opted into a different locale.
        val scopedEvaluator = expressionEvaluator.forCulture(culture)

        if (TwoPassAnalyzer.requiresTwoPassRendering(document)) {
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
                scopedEvaluator = scopedEvaluator,
                clock = clock,
                watermarkText = watermarkText,
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
                watermarkText = watermarkText,
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
        watermarkText: String? = null,
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

        // Render against the address-block-hoisted graph: an address block is a
        // page-absolute element, so when authored inside a header/footer it must not
        // inflate that band — hoisting moves it to the body root for header, footer,
        // body and band measurement alike.
        val renderDocument = hoistAddressBlock(document)
        val bands = bandPlan(renderDocument, context, pageSettings, renderingDefaults, pdfaCompliant, fontFamilyResolver)

        performRenderWithContext(
            outputStream = outputStream,
            context = context,
            bands = bands,
            hoistedDocument = renderDocument,
            metadata = metadata,
            pdfaCompliant = pdfaCompliant,
            pageSettings = pageSettings,
            rightMargin = effectivePageMarginPt(null, "marginRight", context),
            leftMargin = effectivePageMarginPt(null, "marginLeft", context),
            watermarkText = watermarkText,
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
        scopedEvaluator: CompositeExpressionEvaluator = expressionEvaluator,
        clock: Clock = Clock.systemUTC(),
        watermarkText: String? = null,
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

        // Render against the address-block-hoisted graph (see renderSinglePass). The positional
        // band layout is measured once and reused for both passes; section bands are scheduled
        // inside each pass, because they follow that pass's layout.
        val renderDocument = hoistAddressBlock(document)
        val bands = bandPlan(renderDocument, heightContext, pageSettings, renderingDefaults, pdfaCompliant, fontFamilyResolver)
        val bodyLeftMargin = effectivePageMarginPt(null, "marginLeft", heightContext)
        val bodyRightMargin = effectivePageMarginPt(null, "marginRight", heightContext)

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
            bands = bands,
            hoistedDocument = renderDocument,
            metadata = metadata,
            pdfaCompliant = pdfaCompliant,
            pageSettings = pageSettings,
            rightMargin = bodyRightMargin,
            leftMargin = bodyLeftMargin,
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
            bands = bands,
            hoistedDocument = renderDocument,
            metadata = metadata,
            pdfaCompliant = pdfaCompliant,
            pageSettings = pageSettings,
            rightMargin = bodyRightMargin,
            leftMargin = bodyLeftMargin,
            watermarkText = watermarkText,
        )
    }

    private fun performRenderWithContext(
        outputStream: OutputStream,
        context: RenderContext,
        bands: BandPlan,
        /** Must already be address-block-hoisted (see [hoistAddressBlock]); the callers do this. */
        hoistedDocument: TemplateDocument,
        metadata: PdfMetadata,
        pdfaCompliant: Boolean,
        pageSettings: app.epistola.template.model.PageSettings,
        rightMargin: Float,
        leftMargin: Float,
        enablePdfA: Boolean = pdfaCompliant,
        enableMetadata: Boolean = true,
        enableHeaderFooter: Boolean = true,
        watermarkText: String? = null,
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

        // [hoistedDocument] is address-block-hoisted by the caller: the address block
        // is moved to the body root, so it never renders inside — and inflates — a
        // header/footer band, and the bands were measured against this same graph.

        // Section bands: the schedule and per-page choices are filled in below, and the painter
        // is registered here so it runs before the address block and watermark.
        val bandState = PageBandState()
        when (bands) {
            is BandPlan.Positional -> {
                iTextDocument.setMargins(bands.topMargin, rightMargin, bands.bottomMargin, leftMargin)
                if (enableHeaderFooter) registerPositionalBandHandlers(bands, pdfDocument, hoistedDocument, context, nodeRendererRegistry)
            }

            is BandPlan.Sections -> {
                iTextDocument.setMargins(
                    effectivePageMarginPt(null, "marginTop", context),
                    rightMargin,
                    effectivePageMarginPt(null, "marginBottom", context),
                    leftMargin,
                )
                if (enableHeaderFooter) {
                    pdfDocument.addEventHandler(
                        PdfDocumentEvent.END_PAGE,
                        PageBandEventHandler(bandState, hoistedDocument, context, nodeRendererRegistry),
                    )
                }
            }
        }

        // Address block: aside rendered in flow (hoisted to first child of root),
        // address content rendered at absolute coordinates via event handler.
        val addressNode = hoistedDocument.nodes.values.firstOrNull { it.type == "addressblock" }
        addressNode?.let {
            pdfDocument.addEventHandler(
                PdfDocumentEvent.END_PAGE,
                AddressBlockEventHandler(it.id, hoistedDocument, context, nodeRendererRegistry),
            )
        }

        // Preview watermark: stamped over the content on every page so the fast
        // synchronous preview can't be passed off as final output. Registered last
        // so it paints on top of header/footer/address. Drawn only on END_PAGE, it
        // never affects layout — a watermarked preview paginates like the final.
        if (watermarkText != null) {
            pdfDocument.addEventHandler(
                PdfDocumentEvent.END_PAGE,
                WatermarkEventHandler(watermarkText, context.fontCache.regular),
            )
        }

        val elements = when (bands) {
            is BandPlan.Positional -> {
                // First-page spacer: when the first-page pageheader band is taller than the
                // running header band, prepend an invisible Div sized to the extra height so
                // body content on page 1 lands below the cover header. From page 2 onward
                // the spacer is already consumed and content sits at the running topMargin.
                //
                // An empty Div collapses in iText's layout engine; using `setMinHeight` plus
                // an empty Paragraph (zero-leading) guarantees the layout reserves the
                // requested vertical space without painting anything visible.
                if (bands.firstPageSpacer > 0f) {
                    val spacer = com.itextpdf.layout.element.Div()
                        .setMinHeight(bands.firstPageSpacer)
                        .setMargin(0f)
                        .setPadding(0f)
                        .add(com.itextpdf.layout.element.Paragraph("").setMargin(0f).setFixedLeading(0f))
                    iTextDocument.add(spacer)
                }

                // Resolved page-1 body content top (page margin + effective first-page band
                // + spacer). A hoisted address block reserves its window space relative to
                // THIS, so it accounts for the real (auto-grown) header height.
                val bodyContext = context.copy(bodyContentTopPt = bands.topMargin + bands.firstPageSpacer)
                nodeRendererRegistry.renderNode(hoistedDocument.root, hoistedDocument, bodyContext)
            }

            is BandPlan.Sections -> renderWithSectionBands(
                iTextDocument = iTextDocument,
                hoistedDocument = hoistedDocument,
                context = context,
                registry = nodeRendererRegistry,
                bands = bands,
                pageSettings = pageSettings,
                pdfaCompliant = pdfaCompliant,
                rightMargin = rightMargin,
                leftMargin = leftMargin,
                state = bandState,
            )
        }
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
                defaultRenderers(svgConverter),
            )
        }

        private fun defaultRenderers(svgConverter: ImageNodeRenderer.SvgImageConverter): Map<String, NodeRenderer> {
            val renderers = mapOf(
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
            )
            check(renderers.keys == SupportedNodeTypes.all) {
                "Renderer registry and supported node type set are out of sync"
            }
            return renderers
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
}
