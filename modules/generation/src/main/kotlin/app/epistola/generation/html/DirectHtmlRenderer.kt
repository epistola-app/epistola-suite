package app.epistola.generation.html

import app.epistola.generation.AssetResolver
import app.epistola.generation.expression.CompositeExpressionEvaluator
import app.epistola.generation.pdf.RenderingDefaults
import app.epistola.template.model.DocumentStyles
import app.epistola.template.model.ExpressionLanguage
import app.epistola.template.model.Orientation
import app.epistola.template.model.PageFormat
import app.epistola.template.model.TemplateDocument

/**
 * Main HTML renderer that orchestrates node rendering and returns a complete HTML document string.
 * Parallel to [app.epistola.generation.pdf.DirectPdfRenderer].
 */
class DirectHtmlRenderer(
    private val expressionEvaluator: CompositeExpressionEvaluator = CompositeExpressionEvaluator(),
    private val nodeRendererRegistry: HtmlNodeRendererRegistry = createDefaultRegistry(),
    private val defaultExpressionLanguage: ExpressionLanguage = ExpressionLanguage.jsonata,
) {

    fun render(
        document: TemplateDocument,
        data: Map<String, Any?>,
        blockStylePresets: Map<String, Map<String, Any>> = emptyMap(),
        resolvedDocumentStyles: DocumentStyles? = null,
        assetResolver: AssetResolver? = null,
        renderingDefaults: RenderingDefaults = RenderingDefaults.CURRENT,
    ): String {
        val pageSettings = document.pageSettingsOverride ?: renderingDefaults.defaultPageSettings
        val effectiveDocumentStyles = resolvedDocumentStyles
            ?: document.documentStylesOverride
            ?: emptyMap()

        val tipTapHtmlConverter = TipTapHtmlConverter(expressionEvaluator, defaultExpressionLanguage, renderingDefaults)
        val context = HtmlRenderContext(
            data = data,
            loopContext = emptyMap(),
            documentStyles = effectiveDocumentStyles,
            expressionEvaluator = expressionEvaluator,
            tipTapHtmlConverter = tipTapHtmlConverter,
            defaultExpressionLanguage = defaultExpressionLanguage,
            blockStylePresets = blockStylePresets,
            document = document,
            assetResolver = assetResolver,
            renderingDefaults = renderingDefaults,
        )

        // Find header/footer nodes
        val headerNode = document.nodes.values.firstOrNull { it.type == "pageheader" }
        val footerNode = document.nodes.values.firstOrNull { it.type == "pagefooter" }

        // Render header and footer outside the main content flow
        val headerHtml = headerNode?.let {
            nodeRendererRegistry.renderNode(it.id, document, context)
        } ?: ""

        val footerHtml = footerNode?.let {
            nodeRendererRegistry.renderNode(it.id, document, context)
        } ?: ""

        // Render main content
        val bodyContent = nodeRendererRegistry.renderNode(document.root, document, context)

        // Build page CSS
        val margins = pageSettings.margins
        val pageCss = buildPageCss(pageSettings.format, pageSettings.orientation, margins, effectiveDocumentStyles, renderingDefaults)

        return buildString {
            append("<!DOCTYPE html>")
            append("<html>")
            append("<head>")
            append("""<meta charset="UTF-8">""")
            append("""<meta name="viewport" content="width=device-width, initial-scale=1.0">""")
            append("<style>")
            append(pageCss)
            append("</style>")
            append("</head>")
            append("<body>")
            append(headerHtml)
            append(bodyContent)
            append(footerHtml)
            append("</body>")
            append("</html>")
        }
    }

    private fun buildPageCss(
        format: PageFormat,
        orientation: Orientation,
        margins: app.epistola.template.model.Margins,
        documentStyles: DocumentStyles,
        renderingDefaults: RenderingDefaults,
    ): String {
        val pageSize = when (format) {
            PageFormat.A4 -> "A4"
            PageFormat.Letter -> "letter"
            PageFormat.Custom -> "A4"
        }
        val orientationStr = when (orientation) {
            Orientation.portrait -> "portrait"
            Orientation.landscape -> "landscape"
        }

        val fontFamily = (documentStyles["fontFamily"] as? String) ?: "Helvetica, Arial, sans-serif"
        val fontSize = (documentStyles["fontSize"] as? String) ?: "${renderingDefaults.baseFontSizePt}pt"
        val color = (documentStyles["color"] as? String) ?: "#000000"

        return buildString {
            append("@page { size: $pageSize $orientationStr; ")
            append("margin: ${margins.top}mm ${margins.right}mm ${margins.bottom}mm ${margins.left}mm; }")
            append(" * { box-sizing: border-box; }")
            append(" body { font-family: $fontFamily; font-size: $fontSize; color: $color; ")
            append("margin: ${margins.top}mm ${margins.right}mm ${margins.bottom}mm ${margins.left}mm; }")
        }
    }

    companion object {
        fun createDefaultRegistry(): HtmlNodeRendererRegistry = HtmlNodeRendererRegistry(
            mapOf(
                "root" to HtmlContainerNodeRenderer(),
                "text" to HtmlTextNodeRenderer(),
                "container" to HtmlContainerNodeRenderer(),
                "columns" to HtmlColumnsNodeRenderer(),
                "table" to HtmlTableNodeRenderer(),
                "conditional" to HtmlConditionalNodeRenderer(),
                "loop" to HtmlLoopNodeRenderer(),
                "datatable" to HtmlDatatableNodeRenderer(),
                "datatable-column" to HtmlDatatableColumnNodeRenderer(),
                "image" to HtmlImageNodeRenderer(),
                "pagebreak" to HtmlPageBreakNodeRenderer(),
                "pageheader" to HtmlPageHeaderNodeRenderer(),
                "pagefooter" to HtmlPageFooterNodeRenderer(),
            ),
        )
    }
}
