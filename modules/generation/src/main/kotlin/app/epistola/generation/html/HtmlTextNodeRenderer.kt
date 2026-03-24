package app.epistola.generation.html

import app.epistola.generation.filterNonNullValues
import app.epistola.template.model.Node
import app.epistola.template.model.TemplateDocument

/**
 * Renders a "text" node containing TipTap content to HTML.
 */
class HtmlTextNodeRenderer : HtmlNodeRenderer {
    override fun render(
        node: Node,
        document: TemplateDocument,
        context: HtmlRenderContext,
        registry: HtmlNodeRendererRegistry,
    ): String {
        val style = HtmlStyleApplicator.buildStyleAttribute(
            node.styles?.filterNonNullValues(),
            node.stylePreset,
            context.blockStylePresets,
            context.documentStyles,
            context.renderingDefaults.componentDefaults("text"),
        )

        @Suppress("UNCHECKED_CAST")
        val content = node.props?.get("content") as? Map<String, Any>
        val html = context.tipTapHtmlConverter.convert(content, context.effectiveData, context.loopContext)

        return if (style.isNotEmpty()) {
            """<div style="$style">$html</div>"""
        } else {
            "<div>$html</div>"
        }
    }
}
