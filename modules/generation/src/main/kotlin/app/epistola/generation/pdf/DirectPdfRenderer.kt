package app.epistola.generation.pdf

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
        val headerNode = document.nodes.values.firstOrNull { it.type == "pageheader" }
        val footerNode = document.nodes.values.firstOrNull { it.type == "pagefooter" }
        val hasHeaderFooter = headerNode != null || footerNode != null

        if (hasHeaderFooter) {
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
        val effectiveDocumentStyles = resolvedDocumentStyles
            ?: document.documentStylesOverride
            ?: emptyMap()

        performRender(
            outputStream = outputStream,
            data = data,
            effectiveDocumentStyles = effectiveDocumentStyles,
            headerNode = null,
            footerNode = null,
            document = document,
            metadata = metadata,
            pdfaCompliant = pdfaCompliant,
            pageSettings = pageSettings,
            topMargin = 0f,
            bottomMargin = 0f,
            rightMargin = pageSettings.margins.right.toFloat(),
            leftMargin = pageSettings.margins.left.toFloat(),
            blockStylePresets = blockStylePresets,
            assetResolver = assetResolver,
            renderingDefaults = renderingDefaults,
            spacingUnit = spacingUnit,
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

        val heightFontCache = FontCache(pdfaCompliant)
        val heightTipTapConverter = TipTapConverter(expressionEvaluator, defaultExpressionLanguage, renderingDefaults)
        val heightContext = RenderContext(
            data = data,
            loopContext = emptyMap(),
            documentStyles = effectiveDocumentStyles,
            expressionEvaluator = expressionEvaluator,
            tipTapConverter = heightTipTapConverter,
            defaultExpressionLanguage = defaultExpressionLanguage,
            fontCache = heightFontCache,
            blockStylePresets = blockStylePresets,
            document = document,
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

        val topMargin = margins.top.toFloat() +
            if (headerNode != null) renderingDefaults.pageHeaderPadding + headerHeight else 0f
        val bottomMargin = margins.bottom.toFloat() +
            if (footerNode != null) renderingDefaults.pageFooterPadding + footerHeight else 0f

        val firstPassFontCache = FontCache(pdfaCompliant)
        val firstPassTipTapConverter = TipTapConverter(expressionEvaluator, defaultExpressionLanguage, renderingDefaults)
        val firstPassContext = RenderContext(
            data = data,
            loopContext = emptyMap(),
            documentStyles = effectiveDocumentStyles,
            expressionEvaluator = expressionEvaluator,
            tipTapConverter = firstPassTipTapConverter,
            defaultExpressionLanguage = defaultExpressionLanguage,
            fontCache = firstPassFontCache,
            blockStylePresets = blockStylePresets,
            document = document,
            assetResolver = assetResolver,
            renderingDefaults = renderingDefaults,
            spacingUnit = spacingUnit,
        )

        val tempOutput = java.io.ByteArrayOutputStream()
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
            rightMargin = margins.right.toFloat(),
            leftMargin = margins.left.toFloat(),
        )

        val finalFontCache = FontCache(pdfaCompliant)
        val finalTipTapConverter = TipTapConverter(expressionEvaluator, defaultExpressionLanguage, renderingDefaults)
        val finalContext = RenderContext(
            data = data,
            loopContext = emptyMap(),
            documentStyles = effectiveDocumentStyles,
            expressionEvaluator = expressionEvaluator,
            tipTapConverter = finalTipTapConverter,
            defaultExpressionLanguage = defaultExpressionLanguage,
            fontCache = finalFontCache,
            blockStylePresets = blockStylePresets,
            document = document,
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
            rightMargin = margins.right.toFloat(),
            leftMargin = margins.left.toFloat(),
        )
    }

    private fun performRender(
        outputStream: OutputStream,
        data: Map<String, Any?>,
        effectiveDocumentStyles: DocumentStyles,
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
        blockStylePresets: Map<String, Map<String, Any>>,
        assetResolver: AssetResolver?,
        renderingDefaults: RenderingDefaults,
        spacingUnit: Float,
    ): Int {
        val fontCache = FontCache(pdfaCompliant)
        val tipTapConverter = TipTapConverter(expressionEvaluator, defaultExpressionLanguage, renderingDefaults)
        val context = RenderContext(
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
        )
        return performRenderWithContext(
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
            rightMargin = rightMargin,
            leftMargin = leftMargin,
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
    ): Int {
        val writer = PdfWriter(outputStream)
        val pdfDocument = createPdfDocument(writer, pdfaCompliant)
        applyMetadata(pdfDocument, metadata)

        val pageSize = getPageSize(pageSettings.format, pageSettings.orientation)
        val iTextDocument = Document(pdfDocument, pageSize)
        iTextDocument.setFont(context.fontCache.regular)
        iTextDocument.setMargins(topMargin, rightMargin, bottomMargin, leftMargin)

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

        val elements = nodeRendererRegistry.renderNode(document.root, document, context)
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
                "datatable" to DatatableNodeRenderer(),
                "datatable-column" to DatatableColumnNodeRenderer(),
                "image" to ImageNodeRenderer(),
                "qrcode" to QrCodeNodeRenderer(),
                "pagebreak" to PageBreakNodeRenderer(),
                "pageheader" to PageHeaderNodeRenderer(),
                "pagefooter" to PageFooterNodeRenderer(),
            ),
        )
    }
}
