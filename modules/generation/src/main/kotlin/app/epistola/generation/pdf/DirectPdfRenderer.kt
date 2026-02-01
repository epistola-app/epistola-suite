package app.epistola.generation.pdf

import app.epistola.generation.TipTapConverter
import app.epistola.generation.expression.CompositeExpressionEvaluator
import app.epistola.template.model.ExpressionLanguage
import app.epistola.template.model.Orientation
import app.epistola.template.model.PageFooterBlock
import app.epistola.template.model.PageFormat
import app.epistola.template.model.PageHeaderBlock
import app.epistola.template.model.TemplateModel
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.event.PdfDocumentEvent
import com.itextpdf.layout.Document
import java.io.OutputStream

/**
 * Main PDF renderer that orchestrates block rendering and outputs to a stream.
 * Uses iText Core for direct PDF generation without an intermediate HTML step.
 */
class DirectPdfRenderer(
    private val expressionEvaluator: CompositeExpressionEvaluator = CompositeExpressionEvaluator(),
    private val blockRendererRegistry: BlockRendererRegistry = createDefaultRegistry(),
    private val defaultExpressionLanguage: ExpressionLanguage = ExpressionLanguage.Jsonata,
) {

    /**
     * Renders a template to PDF and writes directly to the output stream.
     *
     * @param template The template model containing page settings and blocks
     * @param data The data context for expression evaluation
     * @param outputStream The output stream to write the PDF to
     * @param blockStylePresets Optional block style presets from theme (named style collections like CSS classes)
     * @param resolvedDocumentStyles Optional pre-resolved document styles (merging theme + template styles)
     */
    fun render(
        template: TemplateModel,
        data: Map<String, Any?>,
        outputStream: OutputStream,
        blockStylePresets: Map<String, Map<String, Any>> = emptyMap(),
        resolvedDocumentStyles: app.epistola.template.model.DocumentStyles? = null,
    ) {
        val writer = PdfWriter(outputStream)
        val pdfDocument = PdfDocument(writer)
        val pageSize = getPageSize(template.pageSettings.format, template.pageSettings.orientation)
        val document = Document(pdfDocument, pageSize)

        // Apply margins from page settings
        val margins = template.pageSettings.margins
        document.setMargins(
            margins.top.toFloat(),
            margins.right.toFloat(),
            margins.bottom.toFloat(),
            margins.left.toFloat(),
        )

        // Create render context with resolved styles
        val fontCache = FontCache()
        val tipTapConverter = TipTapConverter(expressionEvaluator, defaultExpressionLanguage)
        val effectiveDocumentStyles = resolvedDocumentStyles ?: template.documentStyles
        val context = RenderContext(
            data = data,
            loopContext = emptyMap(),
            documentStyles = effectiveDocumentStyles,
            expressionEvaluator = expressionEvaluator,
            tipTapConverter = tipTapConverter,
            defaultExpressionLanguage = defaultExpressionLanguage,
            fontCache = fontCache,
            blockStylePresets = blockStylePresets,
        )

        // Register page header event handler if present
        val headerBlock = template.blocks.filterIsInstance<PageHeaderBlock>().firstOrNull()
        if (headerBlock != null) {
            val headerHandler = PageHeaderEventHandler(
                headerBlock = headerBlock,
                context = context,
                blockRenderers = blockRendererRegistry,
            )
            pdfDocument.addEventHandler(PdfDocumentEvent.END_PAGE, headerHandler)
        }

        // Register page footer event handler if present
        val footerBlock = template.blocks.filterIsInstance<PageFooterBlock>().firstOrNull()
        if (footerBlock != null) {
            val footerHandler = PageFooterEventHandler(
                footerBlock = footerBlock,
                context = context,
                blockRenderers = blockRendererRegistry,
            )
            pdfDocument.addEventHandler(PdfDocumentEvent.END_PAGE, footerHandler)
        }

        // Render content blocks (exclude page header and page footer from normal flow)
        val contentBlocks = template.blocks.filterNot { it is PageHeaderBlock || it is PageFooterBlock }
        val elements = blockRendererRegistry.renderBlocks(contentBlocks, context)

        // Add elements to document
        for (element in elements) {
            when (element) {
                is com.itextpdf.layout.element.IBlockElement -> document.add(element)
                is com.itextpdf.layout.element.AreaBreak -> document.add(element)
                is com.itextpdf.layout.element.Image -> document.add(element)
            }
        }

        // Close document (flushes to output stream)
        document.close()
    }

    private fun getPageSize(format: PageFormat, orientation: Orientation): PageSize {
        val baseSize = when (format) {
            PageFormat.A4 -> PageSize.A4
            PageFormat.Letter -> PageSize.LETTER
            PageFormat.Custom -> PageSize.A4 // Default to A4 for custom
        }

        return when (orientation) {
            Orientation.Portrait -> baseSize
            Orientation.Landscape -> baseSize.rotate()
        }
    }

    companion object {
        /**
         * Creates the default block renderer registry with all built-in renderers.
         */
        fun createDefaultRegistry(): BlockRendererRegistry = BlockRendererRegistry(
            mapOf(
                "text" to TextBlockRenderer(),
                "container" to ContainerBlockRenderer(),
                "columns" to ColumnsBlockRenderer(),
                "table" to TableBlockRenderer(),
                "conditional" to ConditionalBlockRenderer(),
                "loop" to LoopBlockRenderer(),
                "pagebreak" to PageBreakBlockRenderer(),
                "pageheader" to PageHeaderBlockRenderer(),
                "pagefooter" to PageFooterBlockRenderer(),
            ),
        )
    }
}
