package app.epistola.generation.pdf

import app.epistola.generation.TipTapConverter
import app.epistola.generation.expression.CompositeExpressionEvaluator
import app.epistola.template.model.DocumentStyles
import app.epistola.template.model.ExpressionLanguage
import app.epistola.template.model.Margins
import app.epistola.template.model.Orientation
import app.epistola.template.model.PageFormat
import app.epistola.template.model.PageSettings
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
 * Default page settings used when a template document does not specify overrides.
 */
private val DEFAULT_PAGE_SETTINGS = PageSettings(
    format = PageFormat.A4,
    orientation = Orientation.portrait,
    margins = Margins(top = 20, right = 20, bottom = 20, left = 20),
)

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
    ) {
        val writer = PdfWriter(outputStream)
        val pdfDocument = if (pdfaCompliant) {
            val outputIntent = createSrgbOutputIntent()
            PdfADocument(writer, PdfAConformance.PDF_A_2B, outputIntent)
        } else {
            PdfDocument(writer)
        }

        // Set document metadata
        applyMetadata(pdfDocument, metadata)

        // Resolve page settings: template override, or default
        val pageSettings = document.pageSettingsOverride ?: DEFAULT_PAGE_SETTINGS
        val pageSize = getPageSize(pageSettings.format, pageSettings.orientation)
        val iTextDocument = Document(pdfDocument, pageSize)

        // Apply margins from page settings
        val margins = pageSettings.margins
        iTextDocument.setMargins(
            margins.top.toFloat(),
            margins.right.toFloat(),
            margins.bottom.toFloat(),
            margins.left.toFloat(),
        )

        // Resolve document styles: caller-provided (pre-merged theme+template), or template override, or empty
        val effectiveDocumentStyles = resolvedDocumentStyles
            ?: document.documentStylesOverride
            ?: emptyMap()

        // Create render context
        val fontCache = FontCache(pdfaCompliant)
        val tipTapConverter = TipTapConverter(expressionEvaluator, defaultExpressionLanguage)
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
        )

        // Set default font on the document so all text uses embedded Liberation Sans
        iTextDocument.setFont(fontCache.regular)

        // Find special nodes (page header/footer) from the document
        val headerNode = document.nodes.values.firstOrNull { it.type == "pageheader" }
        val footerNode = document.nodes.values.firstOrNull { it.type == "pagefooter" }

        // Register page header event handler if present
        if (headerNode != null) {
            val headerHandler = PageHeaderEventHandler(
                headerNodeId = headerNode.id,
                document = document,
                context = context,
                registry = nodeRendererRegistry,
            )
            pdfDocument.addEventHandler(PdfDocumentEvent.END_PAGE, headerHandler)
        }

        // Register page footer event handler if present
        if (footerNode != null) {
            val footerHandler = PageFooterEventHandler(
                footerNodeId = footerNode.id,
                document = document,
                context = context,
                registry = nodeRendererRegistry,
            )
            pdfDocument.addEventHandler(PdfDocumentEvent.END_PAGE, footerHandler)
        }

        // Render content: start from the root node.
        // The root node renderer will traverse its slots, which contain the content nodes.
        // Page header/footer nodes are excluded from content flow because they are registered
        // as no-op renderers (PageHeaderNodeRenderer / PageFooterNodeRenderer) -- they only
        // render via event handlers. They are typically not children of the root slot anyway,
        // but in case they are, the no-op renderers ensure they produce no elements.
        val elements = nodeRendererRegistry.renderNode(document.root, document, context)

        // Add elements to document
        for (element in elements) {
            when (element) {
                is com.itextpdf.layout.element.IBlockElement -> iTextDocument.add(element)
                is com.itextpdf.layout.element.AreaBreak -> iTextDocument.add(element)
                is com.itextpdf.layout.element.Image -> iTextDocument.add(element)
            }
        }

        // Close document (flushes to output stream)
        iTextDocument.close()
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
                "columns" to ColumnsNodeRenderer(),
                "table" to TableNodeRenderer(),
                "conditional" to ConditionalNodeRenderer(),
                "loop" to LoopNodeRenderer(),
                "datatable" to DatatableNodeRenderer(),
                "datatable-column" to DatatableColumnNodeRenderer(),
                "pagebreak" to PageBreakNodeRenderer(),
                "pageheader" to PageHeaderNodeRenderer(),
                "pagefooter" to PageFooterNodeRenderer(),
            ),
        )
    }
}
