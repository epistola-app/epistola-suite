package app.epistola.generation.pdf

import app.epistola.generation.TipTapConverter
import app.epistola.generation.expression.CompositeExpressionEvaluator
import app.epistola.template.model.ExpressionLanguage
import app.epistola.template.model.Orientation
import app.epistola.template.model.PageBreakBlock
import app.epistola.template.model.PageFormat
import app.epistola.template.model.TemplateModel
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.AreaBreak
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
     */
    fun render(
        template: TemplateModel,
        data: Map<String, Any?>,
        outputStream: OutputStream,
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

        // Create render context
        val fontCache = FontCache()
        val tipTapConverter = TipTapConverter(expressionEvaluator, defaultExpressionLanguage)
        val context = RenderContext(
            data = data,
            loopContext = emptyMap(),
            documentStyles = template.documentStyles,
            expressionEvaluator = expressionEvaluator,
            tipTapConverter = tipTapConverter,
            defaultExpressionLanguage = defaultExpressionLanguage,
            fontCache = fontCache,
        )

        // Render all blocks
        for (block in template.blocks) {
            // Handle page breaks specially
            if (block is PageBreakBlock) {
                document.add(AreaBreak())
            } else {
                val elements = blockRendererRegistry.render(block, context)
                for (element in elements) {
                    document.add(element)
                }
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
            ),
        )
    }
}
